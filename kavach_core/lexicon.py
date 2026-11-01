"""Scam-cue phrase matcher (rule layer and fail-safe fallback).

Phrase DSL (see assets/cues.json):
  * tokens are separated by spaces and matched against normalised tokens;
  * a token ending in '*' is a prefix match ("arrest*" matches "arrested");
  * the token '~' is a gap of 0..4 arbitrary tokens.

Negatable categories are suppressed when a negator token appears inside the
matched span, within 2 tokens before it, or within 2 tokens after it, so that
"never share your OTP with anyone" is not read as a request for the OTP.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from .normalize import normalize, tokens

ASSETS = Path(__file__).resolve().parent.parent / "assets"
GAP_MAX = 4
NEG_BEFORE = 2
NEG_AFTER = 2


@dataclass(frozen=True)
class CueHit:
    category: str
    phrase: str
    start: int  # token index, inclusive
    end: int  # token index, exclusive
    stage: str
    weight: float


@dataclass
class Category:
    name: str
    stage: str
    weight: float
    negatable: bool
    label_en: str
    label_hi: str
    phrases: list[list[str]] = field(default_factory=list)
    raw_phrases: list[str] = field(default_factory=list)


def _tok_match(pattern: str, tok: str) -> bool:
    if pattern.endswith("*") and len(pattern) > 1:
        return tok.startswith(pattern[:-1])
    return tok == pattern


def _match_at(pat: list[str], toks: list[str], i: int, j: int = 0) -> int:
    """Return end index (exclusive) of a match of pat[j:] starting at toks[i], or -1.

    Greedy-shortest: the first successful alignment found by trying gaps in
    increasing order, so results are deterministic and identical in Kotlin.
    """
    if j == len(pat):
        return i
    p = pat[j]
    if p == "~":
        for g in range(GAP_MAX + 1):
            if i + g > len(toks):
                break
            e = _match_at(pat, toks, i + g, j + 1)
            if e >= 0:
                return e
        return -1
    if i >= len(toks) or not _tok_match(p, toks[i]):
        return -1
    return _match_at(pat, toks, i + 1, j + 1)


class Lexicon:
    def __init__(self, path: Path | str = ASSETS / "cues.json"):
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        self.negators = {normalize(n) for n in data["negators"]}
        self.stages: list[str] = data["stages"]
        self.categories: dict[str, Category] = {}
        for name, c in data["categories"].items():
            cat = Category(name, c["stage"], float(c["weight"]), bool(c["negatable"]), c["label_en"], c["label_hi"])
            for ph in c["phrases"]:
                # Normalise each token but keep DSL markers.
                toks = []
                for t in ph.split(" "):
                    if t == "~":
                        toks.append("~")
                    elif t.endswith("*"):
                        toks.append(normalize(t[:-1]) + "*")
                    else:
                        toks.extend(normalize(t).split(" "))
                toks = [t for t in toks if t]
                if toks and toks[0] != "~":
                    cat.phrases.append(toks)
                    cat.raw_phrases.append(ph)
            self.categories[name] = cat

    def _negated(self, toks: list[str], s: int, e: int) -> bool:
        lo = max(0, s - NEG_BEFORE)
        hi = min(len(toks), e + NEG_AFTER)
        return any(toks[k] in self.negators for k in range(lo, hi))

    def match_tokens(self, toks: list[str]) -> list[CueHit]:
        """All cue hits. Semantics: per category, phrases in file order, start positions
        ascending; a token already covered by a hit of the same category cannot start
        another hit of that category. Candidate starts are pre-indexed by first token."""
        positions: dict[str, list[int]] = {}
        for i, t in enumerate(toks):
            positions.setdefault(t, []).append(i)
        prefix_cache: dict[str, list[int]] = {}

        def starts(first: str) -> list[int]:
            if first.endswith("*") and len(first) > 1:
                if first not in prefix_cache:
                    pre = first[:-1]
                    prefix_cache[first] = sorted(i for t, ps in positions.items() if t.startswith(pre) for i in ps)
                return prefix_cache[first]
            return positions.get(first, [])

        hits: list[CueHit] = []
        for cat in self.categories.values():
            covered: set[int] = set()
            for pat, raw in zip(cat.phrases, cat.raw_phrases):
                for i in starts(pat[0]):
                    if i in covered:
                        continue
                    e = _match_at(pat, toks, i)
                    if e < 0:
                        continue
                    if cat.negatable and self._negated(toks, i, e):
                        continue
                    hits.append(CueHit(cat.name, raw, i, e, cat.stage, cat.weight))
                    covered.update(range(i, e))
        hits.sort(key=lambda h: (h.start, h.category))
        return hits

    def match(self, text: str) -> list[CueHit]:
        return self.match_tokens(tokens(text))

    def categories_in(self, text: str) -> dict[str, int]:
        out: dict[str, int] = {}
        for h in self.match(text):
            out[h.category] = out.get(h.category, 0) + 1
        return out
