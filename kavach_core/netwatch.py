"""Network Watch: the third KAVACH pipeline, next to the call (Guardian) and the message/app
(Shield) pipelines.

Three detectors, all on-device and explainable:

1. App traffic watch (no INTERNET permission needed). Android's NetworkStatsManager reports
   how many bytes each app sent and received. Every poll, the Shield app feeds per-app byte
   deltas to NetWatch.observe(). Two patterns are flagged, and ONLY for "suspect" apps
   (sideloaded, installed recently, remote-access apps, or apps AppInspector already marks
   suspicious), so that normal cloud backups and chat apps never raise alerts:
     suspicious_upload  a suspect app sends a lot of data (default >= 5 MB in 5 min), mostly
                        while it is in the background, and sends far more than it receives
                        (stolen photos / SMS / contacts leaving the phone).
     beaconing_app      a suspect app talks to the network in small bursts at a steady rhythm
                        while in the background (a remote-control "heartbeat").
2. Domain check (DomainModel): scores a domain name for being machine-generated (DGA) or a
   look-alike, using the same hashed linear model format as the other KAVACH models.
   Used by the optional DNS-watch VPN flavour and the host pcap demo -> kind malicious_domain.
3. Flow model (FlowModel): a tiny MLP over per-connection statistics a phone-side flow tracker
   can compute (duration, packets, bytes, rates, recent-connection counts). Trained on
   UNSW-NB15 (models/netwatch/train_ids.py). Used by the host pcap replay demo; the phone
   path needs a full VPN tunnel and is documented, not shipped in the main apps.

The Kotlin port is kavach.core.NetWatch / DomainModel / FlowModel; parity vectors in
tests/make_parity_vectors.py.
"""
from __future__ import annotations

import json
import math
from collections import deque
from dataclasses import dataclass
from pathlib import Path

from .features import LinearModel, _sigmoid, vectorize_tokens
from .killchain import Event
from .url_check import MULTI_SUFFIXES

# ----------------------------------------------------------------------------- domain model

CONSONANTS = set("bcdfghjklmnpqrstvwxz")
VOWELS = set("aeiouy")


def domain_core(host: str) -> tuple[list[str], str]:
    """Labels left of the public suffix, and the suffix: 'a.b.example.co.in' -> (['a','b','example'], 'co.in')."""
    h = host.strip().lower().strip(".")
    if h.startswith("www."):
        h = h[4:]
    parts = [p for p in h.split(".") if p]
    if len(parts) >= 3 and ".".join(parts[-2:]) in MULTI_SUFFIXES:
        return parts[:-2], ".".join(parts[-2:])
    if len(parts) >= 2:
        return parts[:-1], parts[-1]
    return parts, ""


def label_stats(label: str) -> dict[str, float]:
    n = len(label)
    counts: dict[str, int] = {}
    for ch in label:
        counts[ch] = counts.get(ch, 0) + 1
    ent = 0.0
    for c in counts.values():
        p = c / n
        ent -= p * (math.log(p) / math.log(2.0))
    run = best = 0
    for ch in label:
        run = run + 1 if ch in CONSONANTS else 0
        best = max(best, run)
    return {
        "len": n,
        "digits": sum(ch.isdigit() for ch in label),
        "vowels": sum(ch in VOWELS for ch in label),
        "entropy": ent,
        "cons_run": best,
    }


def domain_tokens(host: str) -> list[str]:
    core, tld = domain_core(host)
    if not core:
        return []
    main = core[0]
    for lab in core:
        if len(lab) > len(main):
            main = lab
    toks: list[str] = []
    for lab in core:
        toks.extend(x for x in lab.split("-") if x)
    st = label_stats(main)
    n = max(st["len"], 1)
    toks += [
        "#tld:" + tld,
        "#len:%d" % min(st["len"] // 3, 10),
        "#dig:%d" % min(st["digits"], 6),
        "#ent:%d" % int(st["entropy"] * 2 + 1e-9),
        "#cons:%d" % min(st["cons_run"], 8),
        "#vow:%d" % int(10 * st["vowels"] / n),
        "#nlab:%d" % min(len(core), 4),
    ]
    return toks


class DomainModel:
    """Machine-generated / malicious domain score (0..1). Wraps a kavach-hashed-linear-v1 model."""

    def __init__(self, model: LinearModel):
        self.m = model

    @classmethod
    def load(cls, directory: Path | str, name: str = "domain_model") -> "DomainModel":
        return cls(LinearModel.load(directory, name))

    @property
    def threshold(self) -> float:
        return self.m.threshold

    def score(self, host: str) -> float:
        toks = domain_tokens(host)
        if not toks:
            return 0.0
        return _sigmoid(self.m.logit_tokens(toks))


def domain_matrix(hosts: list[str], dim: int):
    from array import array

    import numpy as np
    from scipy.sparse import csr_matrix

    indptr, cols, vals = array("q", [0]), array("i"), array("f")
    for h in hosts:
        vec, _ = vectorize_tokens(domain_tokens(h), dim, None)
        cols.extend(vec.keys())
        vals.extend(vec.values())
        indptr.append(len(cols))
    return csr_matrix((np.frombuffer(vals, dtype=np.float32), np.frombuffer(cols, dtype=np.int32),
                       np.frombuffer(indptr, dtype=np.int64)), shape=(len(hosts), dim))


# ----------------------------------------------------------------------------- flow model

FLOW_NUM = ["dur", "spkts", "dpkts", "sbytes", "dbytes", "rate", "smean", "dmean",
            "ct_srv_src", "ct_dst_ltm", "ct_src_dport_ltm", "ct_dst_sport_ltm", "ct_dst_src_ltm", "ct_srv_dst"]
FLOW_CAT = {
    "proto": ["tcp", "udp", "icmp"],
    "state": ["FIN", "CON", "INT", "REQ", "RST"],
    "service": ["-", "dns", "http", "ssl", "ftp", "ftp-data", "smtp", "ssh", "pop3"],
}


def flow_vector(flow: dict) -> list[float]:
    """Raw (un-standardised) feature vector: log1p of counts/bytes/times, one-hot categories (+ 'other')."""
    x = [math.log1p(max(float(flow.get(k, 0.0) or 0.0), 0.0)) for k in FLOW_NUM]
    for k, vals in FLOW_CAT.items():
        v = str(flow.get(k, ""))
        x.extend(1.0 if v == c else 0.0 for c in vals)
        x.append(0.0 if v in vals else 1.0)
    return x


class FlowModel:
    """Tiny MLP: standardise -> [dense+relu]* -> dense -> sigmoid. JSON format kavach-mlp-v1."""

    def __init__(self, spec: dict):
        self.spec = spec
        self.mean = spec["mean"]
        self.std = spec["std"]
        self.layers = spec["layers"]  # [{"w": [[out x in]], "b": [out]}]
        self.threshold = float(spec.get("threshold", 0.5))

    @classmethod
    def load(cls, path: Path | str) -> "FlowModel":
        return cls(json.loads(Path(path).read_text(encoding="utf-8")))

    def score(self, flow: dict) -> float:
        h = [(v - m) / s for v, m, s in zip(flow_vector(flow), self.mean, self.std)]
        for li, layer in enumerate(self.layers):
            out = []
            for row, b in zip(layer["w"], layer["b"]):
                z = b
                for wi, xi in zip(row, h):
                    z += wi * xi
                out.append(z)
            h = [max(0.0, z) for z in out] if li < len(self.layers) - 1 else out
        return _sigmoid(h[0])


# ----------------------------------------------------------------------------- app traffic watch


@dataclass
class NetWatchConfig:
    window_s: float = 300.0          # upload window
    upload_min_bytes: float = 5e6    # >= 5 MB sent in the window
    upload_ratio: float = 3.0        # sent >= 3x received
    bg_frac: float = 0.8             # >= 80% of the sent bytes while the app was in the background
    history_s: float = 3600.0
    slow_min_bytes: float = 4e6      # slow exfiltration: >= 4 MB sent in the background over the history ...
    slow_ratio: float = 10.0         # ... >= 10x what was received ...
    slow_min_bursts: int = 6         # ... in >= 6 separate background upload bursts
    beacon_min_events: int = 8       # >= 8 background bursts ...
    beacon_min_span_s: float = 600.0  # ... spread over >= 10 minutes
    beacon_min_period_s: float = 45.0
    beacon_max_cv: float = 0.25      # steady rhythm: interval std / mean <= 0.25
    beacon_max_burst: float = 64000.0  # each burst small (<= 64 KB)
    cooldown_s: float = 3600.0      # at most one alert per app and kind per hour
    w_upload: float = 2.0
    w_beacon: float = 1.5


def _mb(nbytes: float) -> str:
    """bytes -> 'x.y' MB, rounding half up (identical in Kotlin)"""
    tenths = int(math.floor(nbytes / 1e5 + 0.5))
    return "%d.%d" % (tenths // 10, tenths % 10)


class NetWatch:
    def __init__(self, config: NetWatchConfig | None = None):
        self.cfg = config or NetWatchConfig()
        self.samples: dict[str, deque] = {}
        self.last_fired: dict[tuple[str, str], float] = {}

    def reset(self) -> None:
        self.samples.clear()
        self.last_fired.clear()

    def observe(self, t: float, app: str, tx: float, rx: float, foreground: bool, suspect: bool) -> list[Event]:
        """tx / rx: bytes the app sent / received since its previous sample."""
        cfg = self.cfg
        q = self.samples.setdefault(app, deque())
        q.append((t, float(tx), float(rx), bool(foreground)))
        while q and t - q[0][0] > cfg.history_s:
            q.popleft()
        if not suspect:
            return []
        out: list[Event] = []
        # -- suspicious upload
        win = [s for s in q if t - s[0] <= cfg.window_s]
        sent = sum(s[1] for s in win)
        recv = sum(s[2] for s in win)
        bg_sent = sum(s[1] for s in win if not s[3])
        if sent >= cfg.upload_min_bytes and sent >= cfg.upload_ratio * (recv + 1.0) and bg_sent >= cfg.bg_frac * sent:
            ev = self._fire(t, app, "suspicious_upload", cfg.w_upload,
                            "%s sent %s MB in the background in %d min" % (app, _mb(sent), math.floor(cfg.window_s / 60 + 0.5)))
            if ev:
                out.append(ev)
        else:
            # -- slow exfiltration (stays under the 5-minute limit but keeps going)
            bg = [s for s in q if not s[3]]
            h_sent = sum(s[1] for s in bg)
            h_recv = sum(s[2] for s in q)
            n_up = sum(1 for s in bg if s[1] >= 50000)
            if h_sent >= cfg.slow_min_bytes and h_sent >= cfg.slow_ratio * (h_recv + 1.0) and n_up >= cfg.slow_min_bursts:
                ev = self._fire(t, app, "suspicious_upload", cfg.w_upload,
                                "%s sent %s MB in the background in the last hour" % (app, _mb(h_sent)))
                if ev:
                    out.append(ev)
        # -- beaconing
        bursts = [s[0] for s in q if not s[3] and 0 < s[1] + s[2] <= cfg.beacon_max_burst]
        big = any(not s[3] and s[1] + s[2] > cfg.beacon_max_burst for s in q)
        if len(bursts) >= cfg.beacon_min_events and not big:
            recent = bursts[-12:]
            iv = [b - a for a, b in zip(recent, recent[1:])]
            mean = sum(iv) / len(iv)
            var = sum((x - mean) ** 2 for x in iv) / len(iv)
            cv = math.sqrt(var) / mean if mean > 0 else 1e9
            if recent[-1] - recent[0] >= cfg.beacon_min_span_s and mean >= cfg.beacon_min_period_s and cv <= cfg.beacon_max_cv:
                ev = self._fire(t, app, "beaconing_app", cfg.w_beacon,
                                "%s contacts the internet every %d s in the background" % (app, math.floor(mean + 0.5)))
                if ev:
                    out.append(ev)
        return out

    def _fire(self, t: float, app: str, kind: str, w: float, detail: str) -> Event | None:
        key = (app, kind)
        if key in self.last_fired and t - self.last_fired[key] < self.cfg.cooldown_s:
            return None
        self.last_fired[key] = t
        return Event(t, "network", kind, w, detail)
