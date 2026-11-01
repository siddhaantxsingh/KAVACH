"""On-device retrieval over the scam playbook.

Ranks playbook entries for the current conversation by
  0.7 * cue-overlap(active cue categories, entry.key_cues)
+ 0.3 * cosine(hashed n-gram vector of the transcript, vector of entry story)
The three best entries are inserted into the Llama prompt and the best one
drives the offline fallback explanation. Pure Python / Kotlin, no embedding
model, so retrieval costs well under a millisecond.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

from .features import vectorize_tokens
from .normalize import tokens

ASSETS = Path(__file__).resolve().parent.parent / "assets"
RET_DIM = 1 << 16


@dataclass
class Retrieved:
    entry: dict
    score: float


class Playbook:
    def __init__(self, path: Path | str = ASSETS / "playbook.json"):
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        self.helpline = data["helpline"]
        self.entries: list[dict] = data["scam_types"]
        self.by_id = {e["id"]: e for e in self.entries}
        self._vecs = [vectorize_tokens(tokens(e["name_en"] + " " + e["story_en"]), RET_DIM)[0] for e in self.entries]

    def rank(self, active: dict[str, float], text: str = "", k: int = 3) -> list[Retrieved]:
        tv = vectorize_tokens(tokens(text), RET_DIM)[0] if text else {}
        out = []
        for e, ev in zip(self.entries, self._vecs):
            keys = e["key_cues"]
            overlap = sum(min(active.get(c, 0.0), 3.0) / 3.0 for c in keys) / math.sqrt(len(keys))
            cos = sum(v * ev.get(i, 0.0) for i, v in tv.items()) if tv else 0.0
            out.append(Retrieved(e, 0.7 * overlap + 0.3 * cos))
        out.sort(key=lambda r: -r.score)
        return out[:k]
