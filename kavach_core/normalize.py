"""Text normalisation shared by every KAVACH text model.

The Kotlin port (android/core/.../Normalize.kt) must produce identical output;
tests/test_parity_vectors.py writes golden vectors that the Kotlin tests read.

Rules
-----
1. Unicode NFC, then lower-case.
2. Remove the Devanagari nukta (U+093C) so that "ज़" and "ज" match, and map
   chandrabindu (U+0901) to anusvara (U+0902). ASR output is inconsistent here.
3. Devanagari digits -> ASCII digits.
4. Apostrophes are deleted ("don't" -> "dont").
5. Every character that is not an ASCII letter/digit, a Devanagari character
   (U+0900-U+097F, except the danda punctuation) or another Unicode
   letter/digit becomes a space.
6. Whitespace is collapsed.
"""
from __future__ import annotations

import unicodedata

_DEV_DIGITS = {ord("०") + i: str(i) for i in range(10)}
_APOSTROPHES = "'’‘`´"


def normalize(text: str) -> str:
    t = unicodedata.normalize("NFC", text).lower()
    t = t.replace("़", "").replace("ँ", "ं")
    t = t.translate(_DEV_DIGITS)
    out = []
    for ch in t:
        if ch in _APOSTROPHES:
            continue
        o = ord(ch)
        if o < 128:
            out.append(ch if ch.isalnum() else " ")
        elif 0x0900 <= o <= 0x097F:
            out.append(" " if o in (0x0964, 0x0965) else ch)
        else:
            out.append(ch if ch.isalnum() else " ")
    return " ".join("".join(out).split())


def tokens(text: str) -> list[str]:
    n = normalize(text)
    return n.split(" ") if n else []
