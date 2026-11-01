"""Train the Link Check URL model (hashed char n-gram logistic regression).

Positives : Phishing.Database active phishing domains (sampled) + generated
            look-alikes of Indian bank / UPI / government brands
Negatives : top-10k popular domains + genuine Indian domains with typical paths
Split     : by registrable domain (a domain never appears in two splits).
The deterministic look-alike rules in kavach_core/url_check.py run first; this
model scores what the rules do not cover. We report both the model alone and
the full UrlChecker (rules + model) on the test split.
"""
from __future__ import annotations

import json
import random
import sys
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, f1_score, precision_score, recall_score, roc_auc_score

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from kavach_core.features import LinearModel, build_matrix  # noqa: E402
from kavach_core.url_check import UrlChecker, registrable_domain, split_url  # noqa: E402

RAW = ROOT / "data/public/raw"
OUT = ROOT / "assets/models"
DIM = 1 << 18


def lookalikes(rng: random.Random, brands: list[str], n: int) -> list[str]:
    tlds = ["xyz", "top", "online", "site", "in", "com", "co", "info", "live", "shop", "icu", "click", "net", "org", "co.in", "support"]
    words = ["kyc", "update", "verify", "secure", "login", "netbanking", "online", "care", "rewards", "help", "refund", "pay", "app", "portal"]
    out = []
    for _ in range(n):
        b = rng.choice(brands)
        r = rng.random()
        if r < 0.35:
            host = rng.choice([f"{b}-{rng.choice(words)}", f"{rng.choice(words)}-{b}", f"{b}{rng.choice(words)}", f"{b}-{rng.choice(words)}-{rng.choice(words)}"])
        elif r < 0.6 and len(b) > 3:
            i = rng.randrange(len(b) - 1)
            host = rng.choice([b[:i] + b[i + 1] + b[i] + b[i + 2:], b[:i] + b[i + 1:], b[:i] + b[i] + b[i:], b.replace("o", "0").replace("i", "1")])
        elif r < 0.8:
            host = f"{b}.{rng.choice(words)}-{rng.randint(10, 999)}"
        else:
            host = f"{rng.choice(words)}{rng.randint(1, 99)}-{b}"
        path = rng.choice(["", "/", "/login", "/kyc/update", "/index.php", "/verify", "/app.apk", "/" + "".join(rng.choice("abcdef0123456789") for _ in range(8))])
        out.append(f"{host}.{rng.choice(tlds)}{path}")
    return out


def main() -> None:
    rng = random.Random(5)
    checker = UrlChecker()
    phish = [l.strip().lower() for l in open(RAW / "phishing-domains-ACTIVE.txt", encoding="utf-8", errors="ignore") if l.strip() and not l.startswith("#")]
    rng.shuffle(phish)
    phish = phish[:40000]
    benign = [l.strip().lower() for l in open(RAW / "top-10000-domains.txt", encoding="utf-8") if l.strip()]
    gen = json.loads((ROOT / "assets/genuine_domains.json").read_text())
    paths = ["", "/", "/login", "/personal-banking", "/en/home.html", "/contact-us", "/offers", "/track", "/myaccount", "/help/faq"]
    genuine = [d + rng.choice(paths) for d in gen["domains"] if "." in d for _ in range(15)]
    genuine += [f"{b}.bank.in{rng.choice(paths)}" for b in ("sbi", "hdfc", "icici", "axis", "kotak", "pnb", "canara", "unionbank") for _ in range(10)]
    looks = lookalikes(rng, [b for b in gen["brands"] if len(b) >= 3], 8000)

    items = [(u, 1, "phishdb") for u in phish] + [(u, 1, "lookalike") for u in looks] + [(u, 0, "top10k") for u in benign] + [(u, 0, "genuine_in") for u in genuine]

    def bucket(u: str) -> str:
        h = sum(ord(c) * (i + 1) for i, c in enumerate(registrable_domain(split_url(u)[0]))) % 10
        return "train" if h < 7 else "dev" if h == 7 else "test"

    splits = {"train": [], "dev": [], "test": []}
    for u, y, src in items:
        splits[bucket(u)].append((u, y, src))
    # class balance: weight benign up (fewer, but false alarms are costly)
    def xy(rows):
        texts = [checker.model_text(*split_url(u)) for u, _, _ in rows]
        return texts, np.array([y for _, y, _ in rows])

    Xtr_t, ytr = xy(splits["train"])
    Xdv_t, ydv = xy(splits["dev"])
    Xte_t, yte = xy(splits["test"])
    Xtr, Xdv, Xte = build_matrix(Xtr_t, DIM), build_matrix(Xdv_t, DIM), build_matrix(Xte_t, DIM)
    best = None
    for C in (0.5, 1.0, 2.0, 4.0, 8.0):
        clf = LogisticRegression(C=C, max_iter=3000, solver="liblinear", class_weight="balanced").fit(Xtr, ytr)
        ap = average_precision_score(ydv, clf.predict_proba(Xdv)[:, 1])
        if best is None or ap > best[0]:
            best = (ap, C, clf)
    _, C, clf = best
    # threshold: lowest value keeping dev benign false-alarm rate <= 3%
    pdv = clf.predict_proba(Xdv)[:, 1]
    thr = next(t for t in np.arange(0.5, 0.995, 0.01) if ((pdv >= t) & (ydv == 0)).sum() / max(1, (ydv == 0).sum()) <= 0.03)
    thr = round(float(thr), 2)
    model = LinearModel("url_model", DIM, clf.coef_[0].astype(np.float32), float(clf.intercept_[0]), thr, {"C": C, "task": "url host+path tokens -> phishing"})
    model.save(OUT)
    p = clf.predict_proba(Xte)[:, 1]
    yhat = (p >= thr).astype(int)
    res = {"threshold": thr, "model_only": {"n": len(yte), "pos": int(yte.sum()), "precision": round(precision_score(yte, yhat), 4), "recall": round(recall_score(yte, yhat), 4),
                          "f1": round(f1_score(yte, yhat), 4), "roc_auc": round(roc_auc_score(yte, p), 4)}, "C": C}

    # full checker (rules + model) on the test split, per source
    full = UrlChecker(model)
    per_src: dict[str, list[int]] = {}
    tp = fp = fn = tn = 0
    for u, y, src in splits["test"]:
        v = full.check(u)
        flag = int(v.risk >= 0.5)
        per_src.setdefault(src, []).append(flag)
        tp += flag and y
        fp += flag and not y
        fn += (not flag) and y
        tn += (not flag) and (not y)
    prec, rec = tp / max(1, tp + fp), tp / max(1, tp + fn)
    res["checker_rules_plus_model"] = {"precision": round(prec, 4), "recall": round(rec, 4), "f1": round(2 * prec * rec / max(1e-9, prec + rec), 4),
                                       "flag_rate_by_source": {k: round(sum(v) / len(v), 4) for k, v in per_src.items()},
                                       "genuine_indian_false_alarm": round(sum(per_src.get("genuine_in", [0])) / max(1, len(per_src.get("genuine_in", [1]))), 4)}
    print(json.dumps(res, indent=2))
    (ROOT / "bench/results/url_model.json").write_text(json.dumps(res, indent=2))


if __name__ == "__main__":
    main()
