"""Grid-search Guardian thresholds on the development sets only (dev + handwritten).

Objective = STOP recall + caught-before-money - 5 x false-STOP rate - 0.5 x false-CAUTION rate
(false STOPs on genuine calls are what destroy user trust, so they are weighted heavily).
The locked 'heldout' split and the synthetic 'test' split are never used here.
"""
from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bench.eval_calls import build, evaluate, load  # noqa: E402
from kavach_core.guardian import GuardianConfig  # noqa: E402


def objective(r: dict) -> float:
    return r["stop_recall"] + r["caught_before_money"] - 5 * r["false_stop_rate"] - 0.5 * r["false_caution_rate"]


def main() -> None:
    sets = {s: load(s) for s in ("dev", "handwritten")}
    rows = []
    for pc, ps, pv in itertools.product((0.6, 0.7, 0.8), (0.8, 0.85, 0.9, 0.93), (0.3, 0.5)):
        if ps < pc:
            continue
        cfg = GuardianConfig(p_caution=pc, p_stop=ps, p_veto=pv)
        res = {s: evaluate(c, build("combined", cfg)) for s, c in sets.items()}
        score = sum(objective(r) for r in res.values())
        rows.append({"p_caution": pc, "p_stop": ps, "p_veto": pv, "objective": round(score, 4),
                     **{f"{s}_{k}": res[s][k] for s in res for k in ("stop_recall", "caught_before_money", "false_stop_rate", "false_caution_rate")}})
        print(json.dumps(rows[-1]))
    best = max(rows, key=lambda r: r["objective"])
    print("BEST", json.dumps(best))
    out = ROOT / "bench/results/guardian_tuning.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"best": best, "grid": rows}, indent=2))


if __name__ == "__main__":
    main()
