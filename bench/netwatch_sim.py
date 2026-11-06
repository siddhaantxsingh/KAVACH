"""Simulated per-app traffic traces for the Network Watch app-traffic detector.

NetworkStatsManager gives byte counters per app; the Shield app polls them every 30 s.
This script simulates 2-hour traces of benign and malicious app behaviour at that
granularity and measures what NetWatch catches, how fast, and how often it false-alarms.
The traces are synthetic (we have no labelled phone traffic corpus): the numbers show
the rule design works as intended and where its blind spots are, not field accuracy.

    python bench/netwatch_sim.py            # writes bench/results/netwatch_sim.json
"""
from __future__ import annotations

import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from kavach_core.netwatch import NetWatch  # noqa: E402

POLL = 30.0
DUR = 7200.0

# name -> (malicious?, suspect app?, description)
SCENARIOS = {
    # benign
    "chat_foreground": (False, True, "sideloaded chat app used in the foreground"),
    "photo_share_foreground": (False, True, "user sends a 25 MB video while the app is open"),
    "video_streaming": (False, True, "streaming: big downloads, small uploads"),
    "cloud_backup_store_app": (False, False, "Play-store backup app uploads 60 MB in the background"),
    "cloud_backup_sideloaded": (False, True, "SIDELOADED backup app uploads 60 MB in the background (hard case)"),
    "push_sync_irregular": (False, True, "background sync every 3-20 min, irregular"),
    "regular_sync_sideloaded": (False, True, "sideloaded app syncing exactly every 2 min (hard case)"),
    # malicious
    "spyware_photo_exfil": (True, True, "spyware uploads 8-40 MB of photos in the background"),
    "spyware_sms_contacts": (True, True, "spyware uploads ~6 MB of SMS/contacts/call logs in 2 min"),
    "rat_beacon_60s": (True, True, "remote-control heartbeat every 60 s (+-5%)"),
    "rat_beacon_300s": (True, True, "remote-control heartbeat every 5 min (+-5%)"),
    "slow_exfil": (True, True, "spyware trickles 400 KB every 5 min (designed to stay under limits)"),
    "rat_beacon_jittery": (True, True, "heartbeat every 2 min with +-40% random jitter"),
}


def trace(name: str, seed: int) -> list[tuple[float, str, float, float, bool, bool]]:
    """List of (t, app, tx, rx, foreground, suspect) poll samples for one app."""
    rng = random.Random(f"{name}:{seed}")
    mal, suspect, _ = SCENARIOS[name]
    app = "com.example." + name
    bins: dict[int, list[float]] = {}  # poll index -> [tx, rx, fg]

    def add(t: float, tx: float, rx: float, fg: bool = False) -> None:
        i = int(t // POLL)
        b = bins.setdefault(i, [0.0, 0.0, 0.0])
        b[0] += tx
        b[1] += rx
        b[2] = max(b[2], 1.0 if fg else 0.0)

    if name == "chat_foreground":
        t = rng.uniform(0, 600)
        while t < DUR:
            for k in range(rng.randint(5, 30)):
                add(t + 10 * k, rng.uniform(500, 300000), rng.uniform(500, 300000), True)
            t += rng.uniform(600, 1800)
    elif name == "photo_share_foreground":
        t0 = rng.uniform(600, 3600)
        for k in range(8):
            add(t0 + 15 * k, 25e6 / 8, 20000, True)
    elif name == "video_streaming":
        t0 = rng.uniform(0, 1800)
        for k in range(int(rng.uniform(1200, 3600) / 10)):
            add(t0 + 10 * k, rng.uniform(5000, 20000), rng.uniform(5e5, 2e6), k % 7 != 0)
    elif name.startswith("cloud_backup"):
        t0 = rng.uniform(600, 3600)
        for k in range(20):
            add(t0 + 15 * k, 60e6 / 20, 30000, False)
    elif name == "push_sync_irregular":
        t = rng.uniform(0, 300)
        while t < DUR:
            add(t, rng.uniform(300, 4000), rng.uniform(300, 8000))
            t += rng.uniform(180, 1200)
    elif name == "regular_sync_sideloaded":
        t = rng.uniform(0, 120)
        while t < DUR:
            add(t, rng.uniform(500, 2000), rng.uniform(500, 4000))
            t += 120
    elif name == "spyware_photo_exfil":
        t0 = rng.uniform(600, 3600)
        total = rng.uniform(8e6, 40e6)
        n = rng.randint(6, 16)
        for k in range(n):
            add(t0 + 15 * k, total / n, total / n * 0.02)
    elif name == "spyware_sms_contacts":
        t0 = rng.uniform(600, 3600)
        for k in range(8):
            add(t0 + 15 * k, 6e6 / 8, 4000)
    elif name.startswith("rat_beacon"):
        period = {"rat_beacon_60s": 60.0, "rat_beacon_300s": 300.0, "rat_beacon_jittery": 120.0}[name]
        jit = 0.4 if name == "rat_beacon_jittery" else 0.05
        t = rng.uniform(0, period)
        while t < DUR:
            add(t, rng.uniform(300, 3000), rng.uniform(200, 2000))
            t += period * (1 + rng.uniform(-jit, jit))
    elif name == "slow_exfil":
        t = rng.uniform(0, 300)
        while t < DUR:
            add(t, 4e5, 3000)
            t += 300
    out = []
    for i in range(int(DUR // POLL)):
        b = bins.get(i, [0.0, 0.0, 0.0])
        out.append(((i + 1) * POLL, app, round(b[0], 1), round(b[1], 1), b[2] > 0, suspect))
    return out


def first_onset(name: str, tr) -> float:
    """time the malicious behaviour first appears in the trace"""
    for t, _, tx, rx, _, _ in tr:
        if tx + rx > 0:
            return t
    return 0.0


def run(name: str, seed: int):
    nw = NetWatch()
    tr = trace(name, seed)
    evs = []
    for t, app, tx, rx, fg, sus in tr:
        evs += nw.observe(t, app, tx, rx, fg, sus)
    return tr, evs


def main(seeds: int = 200) -> None:
    res = {"poll_s": POLL, "trace_s": DUR, "seeds": seeds, "scenarios": {}}
    for name, (mal, sus, desc) in SCENARIOS.items():
        hit, delays, kinds = 0, [], {}
        for s in range(seeds):
            tr, evs = run(name, s)
            if evs:
                hit += 1
                delays.append(evs[0].t - first_onset(name, tr))
                for e in evs:
                    kinds[e.kind] = kinds.get(e.kind, 0) + 1
        r = {"malicious": mal, "suspect_app": sus, "what": desc, ("detected" if mal else "false_alarm"): round(hit / seeds, 3), "event_kinds": kinds}
        if mal and delays:
            delays.sort()
            r["median_time_to_detect_s"] = delays[len(delays) // 2]
        res["scenarios"][name] = r
        print(f"{name:26s} {'MAL' if mal else 'ok '} {hit / seeds:6.3f} {kinds}")
    (ROOT / "bench/results/netwatch_sim.json").write_text(json.dumps(res, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
