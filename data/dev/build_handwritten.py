"""Convert handwritten_calls.txt / heldout_calls.txt into timestamped JSONL (same schema as the generator)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
RATE = 2.6  # words per second
GAP = 0.8  # seconds between turns
SEG_WORDS = 8  # ASR endpointing chunk size


def parse(path: Path) -> list[dict]:
    calls, cur = [], None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or (line.startswith("#") and not line.startswith("###")):
            continue
        if line.startswith("###"):
            cid, lab, typ, lang = [x.strip() for x in line[3:].split("|")]
            cur = {"id": cid, "label": 1 if lab == "scam" else 0, "type": typ, "lang": lang, "split": "handwritten", "segments": [], "money_t": None, "_t": 1.0}
            calls.append(cur)
            continue
        tag, _, text = line.partition(":")
        speaker = "victim" if tag.startswith("V") else "caller"
        if tag == "C$" and cur["money_t"] is None:
            cur["money_t"] = round(cur["_t"], 2)
        words = text.split()
        t = cur["_t"]
        for i in range(0, len(words), SEG_WORDS):
            chunk = words[i : i + SEG_WORDS]
            dur = len(chunk) / RATE
            cur["segments"].append({"t0": round(t, 2), "t1": round(t + dur, 2), "text": " ".join(chunk), "speaker": speaker, "stage": None})
            t += dur + 0.1
        cur["_t"] = t + GAP
    for c in calls:
        c.pop("_t")
    return calls


def main() -> None:
    for name, split in (("handwritten_calls", "handwritten"), ("heldout_calls", "heldout")):
        calls = parse(HERE / f"{name}.txt")
        for c in calls:
            c["split"] = split
        out = HERE / f"{name}.jsonl"
        with open(out, "w", encoding="utf-8") as f:
            for c in calls:
                f.write(json.dumps(c, ensure_ascii=False) + "\n")
        print(f"{len(calls)} calls ({sum(c['label'] for c in calls)} scam) -> {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
