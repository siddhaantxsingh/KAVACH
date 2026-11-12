"""Score the 20-call human pilot (demo/pilot20/README.md).

  python bench/pilot20_report.py --sessions demo/pilot20/sessions.csv \
      --annot demo/pilot20/annotation_final.csv --logs ./pilot_logs --out bench/results/pilot20.json

Inputs
  sessions.csv   one row per call, filled in during recording (status, end_state, start_time_ist, ...)
  annotation     agreed first_cue_s / first_request_s per call (seconds from call connect), made without
                 looking at KAVACH's output
  --logs         folder with the app's guardian_*.csv session logs (adb pull .../files/bench). Optional:
                 without logs only the end state written in sessions.csv is scored (no timing).
All rates are integer counts with Wilson 95 % intervals. Unavailable sessions are listed, not dropped.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path

IST = timezone(timedelta(hours=5, minutes=30))
ORDER = {"none": 0, "info": 1, "caution": 2, "stop": 3}


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    if n == 0:
        return (float("nan"), float("nan"))
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return (max(0.0, (c - h) / d), min(1.0, (c + h) / d))


def rate(k: int, n: int) -> dict:
    lo, hi = wilson(k, n)
    return {"k": k, "n": n, "rate": (k / n if n else None), "wilson95": [round(lo, 3), round(hi, 3)] if n else None,
            "text": f"{k}/{n}" + (f" ({100 * k / n:.0f} %, 95 % CI {100 * lo:.0f}–{100 * hi:.0f} %)" if n else "")}


def read_log_sessions(logdir: Path) -> list[dict]:
    """Every session block in every guardian_*.csv: start wall time, first time of each level, llm lines."""
    out = []
    for f in sorted(logdir.glob("guardian_*.csv")):
        cur = None
        for r in csv.reader(open(f, encoding="utf-8")):
            if not r or r[0] == "wall_ms" or len(r) < 3:
                continue
            wall, call_s, kind = int(r[0]), float(r[1]), r[2]
            rest = r[3:]
            if kind == "session":
                cur = {"file": f.name, "start_wall_ms": wall, "reason": rest[0] if rest else "", "first": {}, "alerts": [], "llm": [], "replaced": 0, "mic_blocked": 0}
                out.append(cur)
            elif cur is None:
                continue
            elif kind == "alert":
                lvl = rest[0] if rest else ""
                cur["alerts"].append({"t": call_s, "level": lvl, "rule": rest[1] if len(rest) > 1 else "", "type": rest[2] if len(rest) > 2 else ""})
                cur["first"].setdefault(lvl, call_s)
            elif kind == "llm":
                cur["llm"].append({"t": call_s, "ttft_ms": rest[2] if len(rest) > 2 else ""})
            elif kind == "llm_say_replaced":
                cur["replaced"] += 1
            elif kind == "mic_blocked":
                cur["mic_blocked"] += 1
            elif kind == "session_end":
                cur["end_s"] = call_s
                cur = None
    return out


def match(sess_row: dict, logs: list[dict], tol_s: int = 120) -> dict | None:
    t = sess_row.get("start_time_ist", "").strip()
    if not t or not logs:
        return None
    try:
        dt = datetime.strptime(f"{sess_row['date']} {t}", "%Y-%m-%d %H:%M:%S").replace(tzinfo=IST)
    except ValueError:
        dt = datetime.strptime(f"{sess_row['date']} {t}", "%Y-%m-%d %H:%M").replace(tzinfo=IST)
    ms = dt.timestamp() * 1000
    best = min(logs, key=lambda s: abs(s["start_wall_ms"] - ms))
    return best if abs(best["start_wall_ms"] - ms) <= tol_s * 1000 else None


def fnum(x: str):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions", required=True)
    ap.add_argument("--annot")
    ap.add_argument("--logs")
    ap.add_argument("--out", default="bench/results/pilot20.json")
    a = ap.parse_args()

    rows = list(csv.DictReader(open(a.sessions, encoding="utf-8")))
    ann = {r["call_id"]: r for r in csv.DictReader(open(a.annot, encoding="utf-8"))} if a.annot else {}
    logs = read_log_sessions(Path(a.logs)) if a.logs else []

    calls = []
    for r in rows:
        lg = match(r, logs)
        first = lg["first"] if lg else {}
        top = max([ORDER.get(k, 0) for k in first] + [ORDER.get(r.get("end_state", "").strip().lower(), 0)])
        level = [k for k, v in ORDER.items() if v == top][0]
        warn_t = min([first[k] for k in ("caution", "stop") if k in first], default=None)
        an = ann.get(r["call_id"], {})
        req = fnum(an.get("first_request_s"))
        cue = fnum(an.get("first_cue_s"))
        calls.append({
            "call_id": r["call_id"], "card": r["card"], "label": r["label"], "language": r["language"], "caller": r["caller_id"],
            "noise": r["noise"], "status": r.get("status", "ok").strip() or "ok", "level": level,
            "first_info_s": first.get("info"), "first_caution_s": first.get("caution"), "first_stop_s": first.get("stop"),
            "first_cue_s": cue, "first_request_s": req, "first_warning_s": warn_t,
            "warned_before_request": (warn_t is not None and req is not None and warn_t < req) or (warn_t is not None and req is None and r["label"] == "scam"),
            "lead_s": (req - warn_t) if (warn_t is not None and req is not None) else None,
            "cue_to_stop_s": (first["stop"] - cue) if ("stop" in first and cue is not None) else None,
            "explanation_source": r.get("explanation_source", ""), "llm_replaced": lg["replaced"] if lg else None,
            "mic_blocked_events": lg["mic_blocked"] if lg else None, "log_matched": lg is not None,
        })

    ok = [c for c in calls if c["status"] == "ok"]
    scam = [c for c in ok if c["label"] == "scam"]
    ben = [c for c in ok if c["label"] == "benign"]
    timed = all(c["log_matched"] for c in ok) and bool(ann)
    res = {
        "n_planned": len(calls), "n_unavailable": len(calls) - len(ok),
        "unavailable": [c["call_id"] for c in calls if c["status"] != "ok"],
        "scam_stop": rate(sum(c["level"] == "stop" for c in scam), len(scam)),
        "scam_caution_or_stop": rate(sum(ORDER[c["level"]] >= 2 for c in scam), len(scam)),
        "scam_missed": [c["call_id"] for c in scam if ORDER[c["level"]] < 2],
        "benign_false_stop": rate(sum(c["level"] == "stop" for c in ben), len(ben)),
        "benign_false_caution_only": rate(sum(c["level"] == "caution" for c in ben), len(ben)),
        "benign_info": rate(sum(c["level"] == "info" for c in ben), len(ben)),
        "timing_available": timed,
    }
    if timed:
        res["scam_warned_before_first_request_over_all_scams"] = rate(sum(bool(c["warned_before_request"]) for c in scam), len(scam))
        leads = sorted(c["lead_s"] for c in scam if c["lead_s"] is not None and c["lead_s"] > 0)
        res["lead_s_among_warned_before_request"] = {"n": len(leads), "median": leads[len(leads) // 2] if leads else None, "min": leads[0] if leads else None, "max": leads[-1] if leads else None}
        cs = sorted(c["cue_to_stop_s"] for c in scam if c["cue_to_stop_s"] is not None)
        res["cue_to_stop_s"] = {"n": len(cs), "median": cs[len(cs) // 2] if cs else None}
    for key in ("language", "caller", "noise"):
        g = {}
        for c in ok:
            d = g.setdefault(c[key], {"scam": 0, "scam_stop": 0, "benign": 0, "benign_stop": 0, "benign_caution": 0})
            if c["label"] == "scam":
                d["scam"] += 1; d["scam_stop"] += c["level"] == "stop"
            else:
                d["benign"] += 1; d["benign_stop"] += c["level"] == "stop"; d["benign_caution"] += c["level"] == "caution"
        res[f"by_{key}"] = g
    res["explanation_source"] = {s: sum(c["explanation_source"] == s for c in ok) for s in sorted({c["explanation_source"] for c in ok})}
    res["calls"] = calls
    res["note"] = ("Locked pilot: 20 role-played calls, few speakers; calls by the same caller are not independent. "
                   "Wilson intervals assume independence and are therefore optimistic. Not a population estimate.")
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    json.dump(res, open(a.out, "w"), indent=1, ensure_ascii=False)
    print(f"scam STOP {res['scam_stop']['text']}; scam CAUTION-or-STOP {res['scam_caution_or_stop']['text']}")
    print(f"genuine false STOP {res['benign_false_stop']['text']}; genuine CAUTION (not STOP) {res['benign_false_caution_only']['text']}")
    if timed:
        print(f"scam warned before first risky request (all scam calls) {res['scam_warned_before_first_request_over_all_scams']['text']}")
    print(f"unavailable: {res['unavailable'] or 'none'}; missed scams: {res['scam_missed'] or 'none'}; wrote {a.out}")


if __name__ == "__main__":
    main()
