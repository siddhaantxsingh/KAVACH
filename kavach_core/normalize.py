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
7. Letters spelled out one by one are joined: speech recognisers write "OTP" as
   "o t p" and "SBI" as "s b i". A run of two or more single-letter Latin tokens
   becomes one token ("o t p" -> "otp"). A run of three or more that starts with
   the article "a" keeps the "a" apart ("a c v v" -> "a cvv"). If the joined run
   is exactly a chain of known short forms it is split into them
   ("s b i k y c" -> "sbi kyc").
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
    return " ".join(_join_spelled("".join(out).split()))


# short forms that callers spell out; used only to split a joined run ("sbikyc" -> "sbi kyc")
SPELLED = ("otp", "pin", "kyc", "sbi", "rbi", "cbi", "cvv", "upi", "atm", "pan", "ed", "ncb", "trai", "hdfc", "icici",
           "pnb", "bob", "npci", "it", "id", "sim", "emi", "apk", "sms", "ats", "fir", "dgp", "ips", "ias", "uidai")
_SPELLED = frozenset(SPELLED)


def _split_known(run: str) -> list[str] | None:
    """Split into known short forms (longest first); None if it is not a chain of them."""
    if not run:
        return []
    for n in range(min(5, len(run)), 1, -1):
        if run[:n] in _SPELLED:
            rest = _split_known(run[n:])
            if rest is not None:
                return [run[:n]] + rest
    return None


def _single(w: str) -> bool:
    return len(w) == 1 and "a" <= w <= "z"


def _join_spelled(words: list[str]) -> list[str]:
    out: list[str] = []
    i = 0
    while i < len(words):
        j = i
        while j < len(words) and _single(words[j]):
            j += 1
        if j - i >= 2:
            run = words[i:j]
            if run[0] == "a" and len(run) >= 3:
                out.append("a")
                run = run[1:]
            joined = "".join(run)
            parts = _split_known(joined)
            out.extend(parts if parts and len(parts) > 1 else [joined])
            i = j
        else:
            out.append(words[i])
            i += 1
    return out


def tokens(text: str) -> list[str]:
    n = normalize(text)
    return n.split(" ") if n else []
