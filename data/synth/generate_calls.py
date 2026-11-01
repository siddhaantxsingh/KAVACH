"""Generate synthetic, timestamped call transcripts (scam + benign) for training.

Output: data/synth/out/calls_{train,dev,test}.jsonl, one call per line:
  {"id", "label" (1 scam / 0 benign), "type", "lang", "split",
   "segments": [{"t0", "t1", "text", "speaker", "stage"}],   # ASR-like segments
   "money_t": start time of the first OTP/PIN/payment request (scam only)}

Design choices (see docs/data_card.md):
  * template-disjoint splits: every utterance pool is partitioned by item
    index into train / dev / test parts (split_pool), so dev (model selection)
    and test (final numbers) contain phrasings the models never trained on;
  * ASR-style corruption: lower-case, no punctuation, word drops, duplicated
    words, character slips, Devanagari matra drops, Hinglish spelling variants;
  * realistic timing: 2.2-3.0 words/s, turn gaps, endpointing splits long
    utterances into 4-10 word segments;
  * code-switching: ~15% of utterances in a Hinglish/English call are drawn
    from the other Latin-script rendering.
This data is for training only. Final numbers must come from human-recorded
calls (data/README.md, demo/recording_protocol.md).
"""
from __future__ import annotations

import argparse
import json
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from call_templates import BENIGN_SCRIPTS, P, SCAM_SCRIPTS, SLOTS, SLOTS_HI, STAGE_OF_POOL  # noqa: E402

MONEY_POOLS = {"a_credential", "pay_safe", "pay_fee", "pay_collect", "pay_family"}
LANGS = [("en", 0.3), ("hing", 0.35), ("hi", 0.35)]
HING_VARIANTS = {
    "aadhaar": ["aadhar", "adhaar", "adhar"], "nahi": ["nahin", "nai"], "hai": ["he", "h"], "kijiye": ["kijie", "karo"], "bhej": ["bhej"],
    "account": ["acount", "khata"], "police": ["pulis", "police"], "transfer": ["tranfer", "transfar"], "paise": ["pese", "paisa"],
    "turant": ["turnt", "jaldi"], "batana": ["batao", "bolna"], "mat": ["mat", "matt"], "aapka": ["apka", "aapka"], "aapke": ["apke"],
    "giraftar": ["giraftaar", "arrest"], "warrant": ["warant", "varrant"], "otp": ["o t p", "otp"], "verification": ["verifikation", "verify"],
}
DEV_MATRAS = "ािीुूेैोौंः"


def split_pool(items: list[str], split: str) -> list[str]:
    """Partition a pool by item index: test = every 3rd item; dev = the last remaining
    item when at least 3 remain (so model selection also sees unseen phrasings);
    train = the rest. Pools of size 1 are shared by all splits."""
    if len(items) < 2:
        return items
    test = [x for i, x in enumerate(items) if i % 3 == 2 or (len(items) == 2 and i == 1)]
    rest = [x for x in items if x not in test]
    dev = rest[-1:] if len(rest) >= 3 else rest
    train = rest[:-1] if len(rest) >= 3 else rest
    return {"test": test, "dev": dev, "train": train}[split]


def fill(text: str, lang: str, rng: random.Random) -> str:
    slots = SLOTS_HI if lang == "hi" else SLOTS

    def rep(m):
        key = m.group(1)
        return rng.choice(slots.get(key, SLOTS[key]))

    return re.sub(r"\{(\w+)\}", rep, text)


def asr_noise(text: str, lang: str, rng: random.Random, level: float) -> str:
    t = text.lower()
    t = re.sub(r"[^\w\sऀ-ॿ]", " ", t).replace("।", " ")
    words = t.split()
    out = []
    for w in words:
        r = rng.random()
        if r < 0.05 * level and len(words) > 3:
            continue  # dropped word
        if lang == "hing" and w in HING_VARIANTS and rng.random() < 0.35:
            w = rng.choice(HING_VARIANTS[w])
        if len(w) > 3 and rng.random() < 0.05 * level:
            if lang == "hi":
                w = "".join(c for c in w if not (c in DEV_MATRAS and rng.random() < 0.5)) or w
            else:
                i = rng.randrange(len(w) - 1)
                w = w[:i] + w[i + 1] + w[i] + w[i + 2 :]
        out.append(w)
        if rng.random() < 0.02 * level:
            out.append(w)  # stutter / duplicate
    return " ".join(out) if out else t


def segments_for(text: str, t0: float, rate: float, rng: random.Random) -> tuple[list[tuple[float, float, str]], float]:
    words = text.split()
    segs, i, t = [], 0, t0
    while i < len(words):
        n = rng.randint(4, 10)
        chunk = words[i : i + n]
        dur = len(chunk) / rate
        segs.append((round(t, 2), round(t + dur, 2), " ".join(chunk)))
        t += dur + rng.uniform(0.05, 0.25)
        i += n
    return segs, t


def make_call(cid: str, label: int, typ: str, script, split: str, rng: random.Random, noise: float) -> dict:
    lang = rng.choices([l for l, _ in LANGS], [w for _, w in LANGS])[0]
    t = rng.uniform(0.5, 2.0)
    rate = rng.uniform(2.2, 3.0)
    segs, money_t = [], None
    for pool, prob in script:
        if rng.random() > prob:
            continue
        use_lang = lang
        if lang in ("en", "hing") and rng.random() < 0.15:
            use_lang = "hing" if lang == "en" else "en"
        items = split_pool(P[pool][use_lang], split)
        raw = fill(rng.choice(items), use_lang, rng)
        text = asr_noise(raw, use_lang, rng, noise)
        speaker = "victim" if pool.startswith("victim") else "caller"
        stage = STAGE_OF_POOL.get(pool) if label == 1 else None
        if label == 1 and pool in MONEY_POOLS and money_t is None:
            money_t = round(t, 2)
        ss, t = segments_for(text, t, rate, rng)
        for a, b, x in ss:
            segs.append({"t0": a, "t1": b, "text": x, "speaker": speaker, "stage": stage})
        t += rng.uniform(0.3, 1.2)
    return {"id": cid, "label": label, "type": typ, "lang": lang, "split": split, "segments": segs, "money_t": money_t}


def generate(n: int, split: str, seed: int, noise: float, scam_frac: float = 0.5) -> list[dict]:
    rng = random.Random(seed)
    calls = []
    scam_types, benign_types = list(SCAM_SCRIPTS), list(BENIGN_SCRIPTS)
    for k in range(n):
        if rng.random() < scam_frac:
            typ = scam_types[k % len(scam_types)] if rng.random() < 0.5 else rng.choice(scam_types)
            calls.append(make_call(f"{split}-{k:05d}", 1, typ, SCAM_SCRIPTS[typ], split, rng, noise))
        else:
            typ = rng.choice(benign_types)
            calls.append(make_call(f"{split}-{k:05d}", 0, typ, BENIGN_SCRIPTS[typ], split, rng, noise))
    return calls


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "out"))
    ap.add_argument("--train", type=int, default=8000)
    ap.add_argument("--dev", type=int, default=600)
    ap.add_argument("--test", type=int, default=1000)
    ap.add_argument("--noise", type=float, default=1.0)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for split, n, seed in (("train", a.train, a.seed), ("dev", a.dev, a.seed + 1), ("test", a.test, a.seed + 2)):
        pool_split = split  # each split draws from its own partition of every pool
        calls = generate(n, pool_split, seed, a.noise)
        for c in calls:
            c["split"] = split
            c["id"] = c["id"].replace(pool_split, split, 1)
        with open(out / f"calls_{split}.jsonl", "w", encoding="utf-8") as f:
            for c in calls:
                f.write(json.dumps(c, ensure_ascii=False) + "\n")
        n_scam = sum(c["label"] for c in calls)
        print(f"{split}: {len(calls)} calls ({n_scam} scam, {len(calls) - n_scam} benign) -> {out / f'calls_{split}.jsonl'}")


if __name__ == "__main__":
    main()
