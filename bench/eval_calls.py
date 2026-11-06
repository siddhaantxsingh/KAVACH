"""Call-level evaluation of the Call Guardian (the metrics in the proposal, section 8).

For every call the transcript segments are replayed in call time (each ASR
segment becomes available at its end time, the guardian ticks every 3 s) and
we record when CAUTION and STOP were first raised.

Metrics
  scam calls   STOP recall, CAUTION-or-higher recall,
               caught-before-money: STOP raised no later than the start of the
               first OTP / PIN / payment / remote-access request,
               time-to-STOP from the first scam cue (median, p90)
  benign calls false STOP rate, false CAUTION-or-higher rate
Broken down by scam type and language. Configurations compared:
  rules       phrase cues + kill-chain only (the fail-safe path)
  classifier  linear cue model only (kill-chain disabled except for its levels)
  combined    shipped configuration

Usage: python bench/eval_calls.py [--split test|dev|handwritten] [--json out.json]
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from kavach_core.features import LinearModel  # noqa: E402
from kavach_core.guardian import Guardian, GuardianConfig  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402

FILES = {
    "train": ROOT / "data/synth/out/calls_train.jsonl",
    "dev": ROOT / "data/synth/out/calls_dev.jsonl",
    "test": ROOT / "data/synth/out/calls_test.jsonl",
    "test_far": ROOT / "data/synth/out/calls_test_far.jsonl",  # caller heard through the speaker: 3x ASR errors
    "handwritten": ROOT / "data/dev/handwritten_calls.jsonl",
    "heldout": ROOT / "data/dev/heldout_calls.jsonl",  # locked: report only, never tune
}


class NullLexicon(Lexicon):
    def match_tokens(self, toks):  # classifier-only ablation
        return []


def load(split: str) -> list[dict]:
    return [json.loads(l) for l in open(FILES[split], encoding="utf-8")]


def first_time(history, level: str) -> float | None:
    order = ["none", "info", "caution", "stop"]
    for st in history:
        if order.index(st.level) >= order.index(level):
            return st.t
    return None


def evaluate(calls: list[dict], g: Guardian) -> dict:
    per = []
    for c in calls:
        segs = [(s["t1"], s["text"]) for s in c["segments"]]
        hist = g.run_transcript(segs)
        t_stop, t_caution = first_time(hist, "stop"), first_time(hist, "caution")
        cue_t = next((s["t0"] for s in c["segments"] if s.get("stage")), c["segments"][0]["t0"] if c["segments"] else 0.0)
        per.append({"id": c["id"], "label": c["label"], "type": c["type"], "lang": c["lang"], "t_stop": t_stop, "t_caution": t_caution,
                    "money_t": c.get("money_t"), "cue_t": cue_t, "rule": g.rule})
    return summarize(per)


def summarize(per: list[dict]) -> dict:
    scam = [p for p in per if p["label"] == 1]
    ben = [p for p in per if p["label"] == 0]

    def rate(xs, f):
        return round(sum(1 for x in xs if f(x)) / len(xs), 4) if xs else None

    def before_money(p):
        return p["t_stop"] is not None and (p["money_t"] is None or p["t_stop"] <= p["money_t"])

    tts = [p["t_stop"] - p["cue_t"] for p in scam if p["t_stop"] is not None]
    out = {
        "n_scam": len(scam), "n_benign": len(ben),
        "stop_recall": rate(scam, lambda p: p["t_stop"] is not None),
        "caution_recall": rate(scam, lambda p: p["t_caution"] is not None),
        "caught_before_money": rate(scam, before_money),
        "time_to_stop_median_s": round(statistics.median(tts), 1) if tts else None,
        "time_to_stop_p90_s": round(sorted(tts)[int(0.9 * (len(tts) - 1))], 1) if tts else None,
        "false_stop_rate": rate(ben, lambda p: p["t_stop"] is not None),
        "false_caution_rate": rate(ben, lambda p: p["t_caution"] is not None),
    }
    by_type = defaultdict(list)
    for p in per:
        by_type[p["type"]].append(p)
    out["by_type"] = {
        t: ({"n": len(v), "stop_recall": rate(v, lambda p: p["t_stop"] is not None), "before_money": rate(v, before_money)} if v[0]["label"] == 1
            else {"n": len(v), "false_stop": rate(v, lambda p: p["t_stop"] is not None), "false_caution": rate(v, lambda p: p["t_caution"] is not None)})
        for t, v in sorted(by_type.items())
    }
    by_lang = defaultdict(list)
    for p in per:
        by_lang[p["lang"]].append(p)
    out["by_lang"] = {
        l: {"stop_recall": rate([p for p in v if p["label"] == 1], lambda p: p["t_stop"] is not None),
            "false_stop": rate([p for p in v if p["label"] == 0], lambda p: p["t_stop"] is not None)}
        for l, v in sorted(by_lang.items())
    }
    out["misses"] = [p["id"] for p in scam if p["t_stop"] is None][:20]
    out["false_stops"] = [(p["id"], p["rule"]) for p in ben if p["t_stop"] is not None][:20]
    return out


def build(config: str, cfg: GuardianConfig | None = None) -> Guardian:
    pb = Playbook()
    model = LinearModel.load(ROOT / "assets/models", "cue_model")
    if config == "rules":
        return Guardian(Lexicon(), None, pb, cfg)
    if config == "classifier":
        return Guardian(NullLexicon(), model, pb, cfg)
    return Guardian(Lexicon(), model, pb, cfg)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="test")
    ap.add_argument("--configs", default="rules,classifier,combined")
    ap.add_argument("--json")
    a = ap.parse_args()
    calls = load(a.split)
    results = {}
    for conf in a.configs.split(","):
        r = evaluate(calls, build(conf))
        results[conf] = r
        print(f"\n== {a.split} / {conf}")
        for k in ("n_scam", "n_benign", "stop_recall", "caution_recall", "caught_before_money", "time_to_stop_median_s",
                  "time_to_stop_p90_s", "false_stop_rate", "false_caution_rate"):
            print(f"  {k:24s} {r[k]}")
        print("  by_lang", r["by_lang"])
    if a.json:
        Path(a.json).parent.mkdir(parents=True, exist_ok=True)
        Path(a.json).write_text(json.dumps(results, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
