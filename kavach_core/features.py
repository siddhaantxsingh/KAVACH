"""Hashed n-gram features and a portable linear model.

Why a hashed linear model as the always-on screen:
  * identical, dependency-free inference in Python and Kotlin (a dot product);
  * < 1 ms per 3-second transcript window on a little core, so it can run in
    the hot loop while Llama only runs on escalation;
  * every score decomposes into per-token contributions, which become the
    "evidence" shown to the user.
The char-CNN in models/classifiers is the ExecuTorch-exported alternative
benchmarked against this baseline.

Feature set for a normalised token list t:
  w|<tok>                word unigrams
  b|<tok_i> <tok_i+1>    word bigrams
  c|<ngram>              character 3..5-grams of " " + tok + " "
Each feature string is UTF-8 encoded and hashed with 32-bit FNV-1a, modulo DIM.
Values are 1 + ln(count), then the vector is L2-normalised.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .normalize import tokens as _tokens

FNV_OFFSET = 0x811C9DC5
FNV_PRIME = 0x01000193
CHAR_N = (3, 4, 5)


def fnv1a32(s: str) -> int:
    h = FNV_OFFSET
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * FNV_PRIME) & 0xFFFFFFFF
    return h


def feature_strings(toks: list[str]) -> list[tuple[str, int]]:
    """Return (feature, token_index) pairs; token_index attributes the feature to a token."""
    out: list[tuple[str, int]] = []
    for i, w in enumerate(toks):
        out.append(("w|" + w, i))
        p = " " + w + " "
        for n in CHAR_N:
            for k in range(0, len(p) - n + 1):
                out.append(("c|" + p[k : k + n], i))
    for i in range(len(toks) - 1):
        out.append(("b|" + toks[i] + " " + toks[i + 1], i))
    return out


def vectorize_tokens(toks: list[str], dim: int) -> tuple[dict[int, float], list[tuple[int, int]]]:
    counts: dict[int, int] = {}
    attrib: list[tuple[int, int]] = []
    for f, ti in feature_strings(toks):
        idx = fnv1a32(f) % dim
        counts[idx] = counts.get(idx, 0) + 1
        attrib.append((idx, ti))
    vec = {k: 1.0 + math.log(v) for k, v in counts.items()}
    norm = math.sqrt(sum(v * v for v in vec.values())) or 1.0
    return {k: v / norm for k, v in vec.items()}, attrib


@dataclass
class LinearModel:
    name: str
    dim: int
    weights: np.ndarray  # float32[dim]
    bias: float
    threshold: float = 0.5
    meta: dict | None = None

    def logit_tokens(self, toks: list[str]) -> float:
        vec, _ = vectorize_tokens(toks, self.dim)
        return self.bias + sum(float(self.weights[k]) * v for k, v in vec.items())

    def predict_proba(self, text: str) -> float:
        return _sigmoid(self.logit_tokens(_tokens(text)))

    def explain(self, text: str, top_k: int = 5) -> list[tuple[str, float]]:
        """Top tokens by positive contribution to the logit."""
        toks = _tokens(text)
        vec, attrib = vectorize_tokens(toks, self.dim)
        per_tok = [0.0] * len(toks)
        # distribute each hashed feature's contribution evenly over its occurrences
        occ: dict[int, int] = {}
        for idx, _ in attrib:
            occ[idx] = occ.get(idx, 0) + 1
        for idx, ti in attrib:
            per_tok[ti] += float(self.weights[idx]) * vec[idx] / occ[idx]
        ranked = sorted(((toks[i], c) for i, c in enumerate(per_tok)), key=lambda x: -x[1])
        seen, out = set(), []
        for t, c in ranked:
            if c <= 0 or t in seen:
                continue
            seen.add(t)
            out.append((t, c))
            if len(out) == top_k:
                break
        return out

    # ---- persistence: <name>.json (meta) + <name>.bin (float32 LE weights)
    def save(self, directory: Path | str) -> None:
        d = Path(directory)
        d.mkdir(parents=True, exist_ok=True)
        self.weights.astype("<f4").tofile(d / f"{self.name}.bin")
        meta = {
            "name": self.name,
            "format": "kavach-hashed-linear-v1",
            "dim": self.dim,
            "bias": self.bias,
            "threshold": self.threshold,
            "char_n": list(CHAR_N),
            "hash": "fnv1a32-utf8",
            "values": "1+ln(count), l2-normalised",
        }
        if self.meta:
            meta.update(self.meta)
        (d / f"{self.name}.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")

    @classmethod
    def load(cls, directory: Path | str, name: str) -> "LinearModel":
        d = Path(directory)
        meta = json.loads((d / f"{name}.json").read_text(encoding="utf-8"))
        w = np.fromfile(d / f"{name}.bin", dtype="<f4")
        assert w.shape[0] == meta["dim"], "weight file does not match dim"
        return cls(name, meta["dim"], w, float(meta["bias"]), float(meta.get("threshold", 0.5)), meta)


def _sigmoid(z: float) -> float:
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    e = math.exp(z)
    return e / (1.0 + e)


def build_matrix(texts: list[str], dim: int):
    """Sparse CSR matrix for training with scikit-learn."""
    from scipy.sparse import csr_matrix

    rows, cols, vals = [], [], []
    for r, t in enumerate(texts):
        vec, _ = vectorize_tokens(_tokens(t), dim)
        for k, v in vec.items():
            rows.append(r)
            cols.append(k)
            vals.append(v)
    return csr_matrix((np.asarray(vals, dtype=np.float32), (rows, cols)), shape=(len(texts), dim))
