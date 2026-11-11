"""Per-sentence language choice for the "Auto" call language.

Every sentence is recognised by both whole-sentence speech models (English: NVIDIA Parakeet 110M,
Hindi/Hinglish: AI4Bharat IndicConformer). Each model turns speech of the other language into
words that are not real words of its own language (English model on Hindi: "Kyamee Ankalji";
Hindi model on English: transliterated syllables). So the transcript to keep is the one whose words
are more often real, common words of its language:

    score = (en_known - hi_known) + W * (en_common - hi_common)

  (shares are counted over len + 1 words, so a 1-2 word fragment cannot win on its own)
  *_known  : share of words found in the 20,000 most frequent words of that language
  *_common : share found in the 200 most frequent (mostly function words: "the", "is"; "है", "के")

score > MARGIN -> English, otherwise Hindi/Hinglish (the safe default: the Hindi model also writes
English loanwords, which the lexicon knows in Devanagari). A sentence close to the margin (short:
"okay", "haan") keeps the previous sentence's language. Word lists: assets/langid/ (wordfreq,
Apache-2.0 / CC-BY-SA data, see NOTICE.md). Kotlin twin: android/core/.../LangPick.kt.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
W = 1.0
MARGIN = 0.2
STICKY = 0.15
COMMON = 200
_EN = re.compile(r"[a-z']+")
_HI = re.compile(r"[ऀ-ॿ]+")


class LangPick:
    def __init__(self, en_words: list[str] | None = None, hi_words: list[str] | None = None):
        d = ROOT / "assets/langid"
        en_words = en_words or (d / "en_words.txt").read_text(encoding="utf-8").split()
        hi_words = hi_words or (d / "hi_words.txt").read_text(encoding="utf-8").split()
        self.en, self.hi = set(en_words), set(hi_words)
        self.en_c, self.hi_c = set(en_words[:COMMON]), set(hi_words[:COMMON])
        self.last = "hi"

    @staticmethod
    def _share(words: list[str], s: set[str]) -> float:
        return sum(w in s for w in words) / (len(words) + 1) if words else 0.0

    def score(self, en_text: str, hi_text: str) -> float:
        e = _EN.findall(en_text.lower())
        h = _HI.findall(hi_text)
        return (self._share(e, self.en) - self._share(h, self.hi)) + W * (self._share(e, self.en_c) - self._share(h, self.hi_c))

    def pick(self, en_text: str, hi_text: str) -> str:
        """'en' or 'hi' for one sentence (remembers the last choice for borderline sentences)."""
        s = self.score(en_text, hi_text)
        if abs(s - MARGIN) < STICKY:
            return self.last
        self.last = "en" if s > MARGIN else "hi"
        return self.last

    def reset(self) -> None:
        self.last = "hi"
