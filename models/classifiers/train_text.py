"""Train the two hashed linear text models shipped in the apps.

  cue_model  - scam-cue screen for rolling call-transcript windows
  sms_model  - scam SMS / WhatsApp-text classifier

Both use kavach_core.features (FNV-1a hashed word, bigram and char 3-5-gram
features) and L2-regularised logistic regression. C is picked on the dev
split; test numbers are reported once, at the end, on the held-out split.
Weights are written to assets/models/<name>.{bin,json} and copied into the
Android assets by the Gradle build.

Usage: python models/classifiers/train_text.py [--dim 262144]
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, f1_score, precision_score, recall_score, roc_auc_score

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from kavach_core.features import LinearModel, build_matrix  # noqa: E402
from kavach_core.guardian import WINDOW_TOKENS  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.normalize import tokens  # noqa: E402

SYNTH = ROOT / "data" / "synth" / "out"
PUBLIC = ROOT / "data" / "public" / "raw"
OUT = ROOT / "assets" / "models"
REPORTS = ROOT / "bench" / "results"


def call_windows(path: Path) -> tuple[list[str], list[int], list[str]]:
    """One window per ASR segment: the last WINDOW_TOKENS tokens of the call so far.

    Label 1 iff the call is a scam AND the window already contains at least one
    caller utterance with a scam stage (opening greetings of a scam are 0).
    """
    texts, labels, groups = [], [], []
    for line in open(path, encoding="utf-8"):
        c = json.loads(line)
        toks: list[str] = []
        seen_scam = False
        for s in c["segments"]:
            toks.extend(tokens(s["text"]))
            if c["label"] == 1 and s.get("stage"):
                seen_scam = True
            texts.append(" ".join(toks[-WINDOW_TOKENS:]))
            labels.append(1 if seen_scam else 0)
            groups.append(c["type"])
    return texts, labels, groups


def sms_rows() -> dict[str, tuple[list[str], list[int]]]:
    rows = {"train": ([], []), "dev": ([], []), "test": ([], [])}
    for r in csv.DictReader(open(SYNTH / "sms_synth.csv", encoding="utf-8")):
        rows[r["split"]][0].append(r["text"])
        rows[r["split"]][1].append(int(r["label"]))
    # UCI SMS Spam Collection: deterministic 70/10/20 split by line index
    uci = PUBLIC / "sms_spam_uci.tsv"
    if uci.exists():
        for i, line in enumerate(open(uci, encoding="utf-8", errors="replace")):
            lab, _, text = line.rstrip("\n").partition("\t")
            if lab not in ("ham", "spam"):
                continue
            k = i % 10
            split = "train" if k < 7 else "dev" if k == 7 else "test"
            rows[split][0].append(text)
            rows[split][1].append(1 if lab == "spam" else 0)
    return rows


def metrics(y: np.ndarray, p: np.ndarray, thr: float) -> dict:
    yhat = (p >= thr).astype(int)
    return {
        "n": int(len(y)), "pos": int(y.sum()), "threshold": thr,
        "precision": round(float(precision_score(y, yhat, zero_division=0)), 4),
        "recall": round(float(recall_score(y, yhat, zero_division=0)), 4),
        "f1": round(float(f1_score(y, yhat, zero_division=0)), 4),
        "roc_auc": round(float(roc_auc_score(y, p)), 4),
        "pr_auc": round(float(average_precision_score(y, p)), 4),
    }


def fit(name: str, tr, dv, te, dim: int, extra_meta: dict, lexicon=None) -> dict:
    t0 = time.time()
    Xtr, Xdv, Xte = (build_matrix(x[0], dim, lexicon) for x in (tr, dv, te))
    ytr, ydv, yte = np.array(tr[1]), np.array(dv[1]), np.array(te[1])
    best = None
    grid = []
    for C in (0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0):
        clf = LogisticRegression(C=C, max_iter=2000, solver="liblinear")
        clf.fit(Xtr, ytr)
        pdv = clf.predict_proba(Xdv)[:, 1]
        ap = average_precision_score(ydv, pdv)  # template-disjoint dev -> rewards generalisation
        grid.append({"C": C, "dev_pr_auc": round(float(ap), 4)})
        if best is None or ap > best[0]:
            best = (ap, C, clf)
    _, C, clf = best
    # decision threshold maximising dev F1
    pdv = clf.predict_proba(Xdv)[:, 1]
    thr = max(np.arange(0.2, 0.91, 0.05), key=lambda t: f1_score(ydv, (pdv >= t).astype(int)))
    thr = round(float(thr), 2)
    pte = clf.predict_proba(Xte)[:, 1]
    w = clf.coef_[0].astype(np.float32)
    model = LinearModel(name, dim, w, float(clf.intercept_[0]), thr, {"C": C, **extra_meta}, lexicon)
    model.save(OUT)
    # parity check: our own inference == sklearn
    k = min(200, len(te[0]))
    ours = np.array([model.predict_proba(t) for t in te[0][:k]])
    assert np.allclose(ours, pte[:k], atol=1e-4), "hashed inference does not match sklearn"
    res = {"model": name, "C": C, "grid": grid, "dim": dim, "train_n": len(ytr), "seconds": round(time.time() - t0, 1),
           "nonzero_weights": int((np.abs(w) > 1e-6).sum()), "dev": metrics(ydv, pdv, thr), "test": metrics(yte, pte, thr)}
    print(json.dumps(res, indent=2))
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dim", type=int, default=1 << 18)
    ap.add_argument("--no-cue-features", action="store_true")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    REPORTS.mkdir(parents=True, exist_ok=True)

    tr = call_windows(SYNTH / "calls_train.jsonl")
    dv = call_windows(SYNTH / "calls_dev.jsonl")
    te = call_windows(SYNTH / "calls_test.jsonl")
    lex = None if a.no_cue_features else Lexicon()
    r1 = fit("cue_model", tr[:2], dv[:2], te[:2], a.dim, {"window_tokens": WINDOW_TOKENS, "task": "call transcript window -> scam"}, lex)

    rows = sms_rows()
    r2 = fit("sms_model", rows["train"], rows["dev"], rows["test"], a.dim, {"task": "sms/notification text -> scam"}, lex)
    (REPORTS / "text_models.json").write_text(json.dumps([r1, r2], indent=2))


if __name__ == "__main__":
    main()
