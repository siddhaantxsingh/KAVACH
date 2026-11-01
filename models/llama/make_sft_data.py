"""Build LoRA fine-tuning pairs for Llama 3.2 1B: (system + user prompt) -> JSON verdict.

The prompt is produced by exactly the code the phone uses (kavach_core.llm), with the
retrieved playbook entries and cue scores computed by the same engine, so training and
inference see identical inputs. Targets come from the call annotations:

  scam      call label and whether a scam-stage utterance is already in the window
  type      the call's scam type (a playbook id) or "none"
  stage     latest annotated stage in the window
  evidence  up to 3 cue phrases found in the window, copied verbatim (normalised text)
  say       short advice in the requested language, built from the vetted playbook text
  action    hang_up (isolation/action/payment), verify_independently (hook),
            continue_with_care (contact), none (benign)

Output: data/synth/out/sft_{train,dev}.jsonl in chat format {"messages": [...]}, plus a
small committed sample at models/llama/sft_sample.jsonl.
"""
from __future__ import annotations

import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from kavach_core.guardian import WINDOW_TOKENS  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.llm import system_prompt, user_prompt  # noqa: E402
from kavach_core.normalize import tokens  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402

STAGE_ORDER = ["contact", "hook", "isolation", "action", "payment"]
BENIGN_SAY = {
    "en": "This call looks normal. Still, never share an OTP, PIN or screen with anyone on a call.",
    "hi": "यह कॉल सामान्य लग रही है। फिर भी कॉल पर किसी को ओटीपी, पिन या स्क्रीन कभी मत दीजिए।",
}


def window_cues(lex: Lexicon, toks: list[str]) -> tuple[dict[str, float], list[str]]:
    hits = lex.match_tokens(toks)
    active: dict[str, float] = {}
    for h in hits:
        active[h.category] = min(3.0, active.get(h.category, 0.0) + h.weight)
    active = {k: round(v, 2) for k, v in active.items() if v > 0}
    ev = sorted((h for h in hits if h.weight > 0), key=lambda h: -h.weight)
    quotes, seen = [], set()
    for h in ev:
        q = " ".join(toks[h.start:h.end])
        if q not in seen:
            seen.add(q)
            quotes.append(q)
        if len(quotes) == 3:
            break
    return active, quotes


def target(scam: bool, typ: str, stage: str, quotes: list[str], pb: Playbook, lang: str, n_stages: int) -> dict:
    if not scam:
        return {"scam": False, "type": "none", "stage": "none", "confidence": 0.85, "evidence": [], "say": BENIGN_SAY[lang], "action": "none"}
    e = pb.by_id[typ]
    say = (f"यह {e['name_hi']} स्कैम है। {e['action_hi']}" if lang == "hi" else f"This is a {e['name_en']} scam. {e['action_en']}")
    say = " ".join(say.split()[:35])
    action = "hang_up" if stage in ("isolation", "action", "payment") else "verify_independently" if stage == "hook" else "continue_with_care"
    conf = round(min(0.97, 0.55 + 0.12 * n_stages), 2)
    return {"scam": True, "type": typ, "stage": stage, "confidence": conf, "evidence": quotes, "say": say, "action": action}


def build(split: str, n: int, seed: int) -> list[dict]:
    rng = random.Random(seed)
    lex, pb = Lexicon(), Playbook()
    sys_p = system_prompt([e["id"] for e in pb.entries])
    calls = [json.loads(l) for l in open(ROOT / f"data/synth/out/calls_{split}.jsonl", encoding="utf-8")]
    out = []
    while len(out) < n:
        c = rng.choice(calls)
        if not c["segments"]:
            continue
        k = rng.randint(1, len(c["segments"]))
        segs = c["segments"][:k]
        toks: list[str] = []
        for s in segs:
            toks.extend(tokens(s["text"]))
        toks = toks[-WINDOW_TOKENS:]
        stages = [s["stage"] for s in segs if s.get("stage")]
        scam = c["label"] == 1 and bool(stages)
        stage = max(stages, key=STAGE_ORDER.index) if stages else "none"
        active, quotes = window_cues(lex, toks)
        retrieved = [r.entry for r in pb.rank(active, " ".join(toks))]
        lang = "hi" if c["lang"] in ("hi", "hing") and rng.random() < 0.8 else "en"
        tgt = target(scam, c["type"], stage, quotes if scam else [], pb, lang, len(set(stages)))
        out.append({"messages": [
            {"role": "system", "content": sys_p},
            {"role": "user", "content": user_prompt(" ".join(toks), active, retrieved, lang)},
            {"role": "assistant", "content": json.dumps(tgt, ensure_ascii=False)},
        ], "meta": {"call": c["id"], "label": c["label"], "type": c["type"], "lang": lang}})
    return out


def main() -> None:
    d = ROOT / "data/synth/out"
    for split, n, seed in (("train", 4000, 1), ("dev", 400, 2)):
        rows = build(split, n, seed)
        with open(d / f"sft_{split}.jsonl", "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(split, len(rows), "scam share", round(sum(json.loads(r["messages"][2]["content"])["scam"] for r in rows) / len(rows), 3))
    with open(Path(__file__).parent / "sft_sample.jsonl", "w", encoding="utf-8") as f:
        for r in build("dev", 6, 9):
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
