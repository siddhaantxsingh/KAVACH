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
from statistics import median
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
            elif kind == "llm_outcome":
                cur.setdefault("llm_outcomes", []).append({"outcome": rest[0] if rest else "", "ms": rest[1] if len(rest) > 1 else "", "valid": (rest[2] if len(rest) > 2 else "") == "true"})
            elif kind == "own_voice_ignored":
                cur["own_voice_ignored"] = cur.get("own_voice_ignored", 0) + 1
            elif kind == "llm_say_replaced":
                cur["replaced"] += 1
            elif kind == "mic_blocked":
                cur["mic_blocked"] += 1
            elif kind == "session_end":
                cur["end_s"] = call_s
                cur = None
    return out


def call_start_ms(row: dict) -> float | None:
    value = row.get("start_time_ist", "").strip()
    if not value:
        return None
    # Seconds required: minute-rounded times are unsuitable for lead-time claims.
    dt = datetime.strptime(f"{row['date']} {value}", "%Y-%m-%d %H:%M:%S").replace(tzinfo=IST)
    return dt.timestamp() * 1000


def match(sess_row: dict, logs: list[dict], tol_s: int = 120) -> dict | None:
    ms = call_start_ms(sess_row)
    if ms is None:
        return None
    candidates = [s for s in logs if abs(s["start_wall_ms"] - ms) <= tol_s * 1000]
    if len(candidates) > 1:
        raise ValueError(f"{sess_row['call_id']}: ambiguous log match; isolate the correct log session")
    return candidates[0] if candidates else None


def _median(v: list):
    return median(v) if v else None


def fnum(x: str):
    if x is None or str(x).strip() == "":
        return None
    value = float(x)
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"Invalid nonnegative timestamp: {x}")
    return value


def score(rows: list[dict], ann: dict, logs: list[dict]) -> dict:
    ids = [r["call_id"] for r in rows]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate call_id in sessions")
    if set(ann) - set(ids):
        raise ValueError("Annotation contains unknown call_id")
    calls, used = [], set()
    for r in rows:
        status = r.get("status", "").strip().lower() or "pending"
        if status not in {"pending", "ok", "unavailable"}:
            raise ValueError(f"{r['call_id']}: invalid status {status}")
        if r["label"] not in {"scam", "benign"}:
            raise ValueError(f"{r['call_id']}: invalid label")
        state = r.get("end_state", "").strip().lower()
        if status == "ok" and state not in ORDER:
            raise ValueError(f"{r['call_id']}: completed call requires explicit end_state")
        lg = match(r, logs) if status == "ok" else None
        if lg:
            key = (lg["file"], lg["start_wall_ms"])
            if key in used:
                raise ValueError(f"{r['call_id']}: log session already assigned to another call")
            used.add(key)
        # App log seconds are relative to app session start, annotations to call connection.
        offset = (lg["start_wall_ms"] - call_start_ms(r)) / 1000 if lg else 0
        first = {k: t + offset for k, t in lg["first"].items()} if lg else {}
        if any(t < 0 for t in first.values()):
            raise ValueError(f"{r['call_id']}: alert predates call connection; check clock alignment")
        top = max([ORDER.get(k, 0) for k in first] + [ORDER.get(state, 0)])
        level = next(k for k, v in ORDER.items() if v == top) if status == "ok" else None
        warn_t = min([first[k] for k in ("caution", "stop") if k in first], default=None)
        an = ann.get(r["call_id"], {})
        req, cue = fnum(an.get("first_request_s")), fnum(an.get("first_cue_s"))
        # An ended session plus matching final warning states guards against incomplete logs.
        warning_states_agree = (not state or ORDER[state] < 2 or state in first)
        timing_ok = bool(lg and "end_s" in lg and req is not None and warning_states_agree)
        calls.append({
            "call_id": r["call_id"], "card": r.get("card", ""), "label": r["label"],
            "language": r.get("language", ""), "caller": r.get("caller_id", ""),
            "noise": r.get("noise", ""), "status": status, "level": level,
            "first_info_s": first.get("info"), "first_caution_s": first.get("caution"),
            "first_stop_s": first.get("stop"), "first_cue_s": cue, "first_request_s": req,
            "first_warning_s": warn_t, "timing_evaluable": timing_ok,
            "warned_before_request": (warn_t is not None and warn_t < req) if timing_ok else None,
            "lead_s": req - warn_t if timing_ok and warn_t is not None else None,
            "cue_to_stop_s": first["stop"] - cue if lg and "end_s" in lg and "stop" in first and cue is not None else None,
            "explanation_source": r.get("explanation_source", ""),
            "llm_replaced": lg["replaced"] if lg else None,
            "mic_blocked_events": lg["mic_blocked"] if lg else None,
            "log_matched": lg is not None, "log_to_call_offset_s": offset if lg else None,
            "llm_outcomes": lg.get("llm_outcomes", []) if lg else [],
        })
    ok = [c for c in calls if c["status"] == "ok"]
    scam = [c for c in ok if c["label"] == "scam"]
    ben = [c for c in ok if c["label"] == "benign"]
    attempted = [c for c in calls if c["status"] != "pending"]
    attempted_scams = [c for c in attempted if c["label"] == "scam"]
    timing = [c for c in scam if c["timing_evaluable"]]
    early = sum(c["warned_before_request"] is True for c in timing)
    timed = bool(scam) and len(timing) == len(scam)
    res = {
        "n_planned": len(calls), "n_pending": sum(c["status"] == "pending" for c in calls),
        "n_attempted": len(attempted), "n_evaluable": len(ok),
        "n_unavailable": sum(c["status"] == "unavailable" for c in calls),
        "pending": [c["call_id"] for c in calls if c["status"] == "pending"],
        "unavailable": [c["call_id"] for c in calls if c["status"] == "unavailable"],
        "scam_stop": rate(sum(c["level"] == "stop" for c in scam), len(scam)),
        "scam_stop_over_attempted": rate(sum(c["level"] == "stop" for c in scam), len(attempted_scams)),
        "scam_caution_or_stop": rate(sum(ORDER[c["level"]] >= 2 for c in scam), len(scam)),
        "scam_caution_or_stop_over_attempted": rate(sum(ORDER[c["level"]] >= 2 for c in scam), len(attempted_scams)),
        "scam_missed": [c["call_id"] for c in scam if ORDER[c["level"]] < 2],
        "benign_false_stop": rate(sum(c["level"] == "stop" for c in ben), len(ben)),
        "benign_false_caution_only": rate(sum(c["level"] == "caution" for c in ben), len(ben)),
        "benign_info": rate(sum(c["level"] == "info" for c in ben), len(ben)),
        "benign_attempted": sum(c["label"] == "benign" for c in attempted),
        "timing_available": timed, "n_timing_evaluable_scams": len(timing),
        "timing_missing_scams": [c["call_id"] for c in scam if not c["timing_evaluable"]],
        "scam_warned_before_request_timing_evaluable": rate(early, len(timing)),
        "scam_early_warning_confirmed_over_attempted": rate(early, len(attempted_scams)),
    }
    if timed:
        res["scam_warned_before_first_request_over_all_scams"] = rate(early, len(scam))
    leads = [c["lead_s"] for c in timing if c["lead_s"] is not None and c["lead_s"] > 0]
    res["lead_s_among_warned_before_request"] = {"n": len(leads), "median": _median(leads), "min": min(leads) if leads else None, "max": max(leads) if leads else None}
    cs = [c["cue_to_stop_s"] for c in scam if c["cue_to_stop_s"] is not None]
    res["cue_to_stop_s"] = {"n": len(cs), "median": _median(cs)}
    for key in ("language", "caller", "noise"):
        g = {}
        for c in ok:
            d = g.setdefault(c[key], {"scam": 0, "scam_stop": 0, "benign": 0, "benign_stop": 0, "benign_caution": 0})
            if c["label"] == "scam":
                d["scam"] += 1; d["scam_stop"] += c["level"] == "stop"
            else:
                d["benign"] += 1; d["benign_stop"] += c["level"] == "stop"; d["benign_caution"] += c["level"] == "caution"
        res[f"by_{key}"] = g
    outs = [o for c in ok for o in c["llm_outcomes"]]
    res["llama_attempts"] = {"n": len(outs), "ok_valid": sum(o["outcome"] == "ok" and o["valid"] for o in outs),
        "timeout": sum(o["outcome"] == "timeout" for o in outs),
        "error": sum(o["outcome"].startswith("error") for o in outs),
        "median_ms": _median([int(o["ms"]) for o in outs if str(o["ms"]).isdigit()])}
    res["explanation_source"] = {s: sum(c["explanation_source"] == s for c in ok) for s in sorted({c["explanation_source"] for c in ok})}
    res["calls"] = calls
    res["note"] = ("Feasibility role-play pilot; calls sharing speakers are correlated. Wilson intervals assume independence. "
        "Detection rates are conditional on evaluable calls; over-attempted detection treats unavailable calls as no confirmed protection. "
        "Confirmed early-warning/attempted is a conservative evidence rate, not a fully measured timing rate. "
        "Missing request timestamps are unknown, never successes. Pending calls are not attempts. "
        "Phone and observer clocks must be aligned before timing; nearest log matching alone does not prove alignment.")
    return res


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

    if a.annot:
        annotations = list(csv.DictReader(open(a.annot, encoding="utf-8")))
        if len({r["call_id"] for r in annotations}) != len(annotations):
            raise ValueError("Duplicate call_id in annotations")
    res = score(rows, ann, logs)
    timed = res["timing_available"]
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    json.dump(res, open(a.out, "w"), indent=1, ensure_ascii=False)
    print(f"scam STOP {res['scam_stop']['text']}; scam CAUTION-or-STOP {res['scam_caution_or_stop']['text']}")
    print(f"genuine false STOP {res['benign_false_stop']['text']}; genuine CAUTION (not STOP) {res['benign_false_caution_only']['text']}")
    if timed:
        print(f"scam warned before first risky request (all scam calls) {res['scam_warned_before_first_request_over_all_scams']['text']}")
    print(f"pending: {res['n_pending']}; attempted: {res['n_attempted']}; unavailable: {res['unavailable'] or 'none'}; missed scams: {res['scam_missed'] or 'none'}; wrote {a.out}")


if __name__ == "__main__":
    main()
