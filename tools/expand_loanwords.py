"""Mixed-script variants for the cue word list.

The Hindi streaming speech model writes English loanwords in Latin letters inside Hindi sentences
("bank कभी otp नहीं मांगता", "आधार card", "call मत काटिए"), while the hand-written Hindi phrases
spell them in Devanagari ("बैंक", "ओटीपी", "कार्ड", "कॉल"). For every phrase that contains such
loanwords this script adds the variants with the loanwords in Latin letters (all subsets, phrases
with up to 3 loanwords). Idempotent: run it again after editing assets/cues.json.

Usage: python tools/expand_loanwords.py [--check]
"""
from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from kavach_core.normalize import normalize  # noqa: E402

LOAN = {
    "ओटीपी": "otp", "पिन": "pin", "बैंक": "bank", "लिंक": "link", "अकाउंट": "account", "एकाउंट": "account", "कॉल": "call",
    "शेयर": "share", "कोड": "code", "पुलिस": "police", "कार्ड": "card", "पार्सल": "parcel", "अरेस्ट": "arrest", "नंबर": "number",
    "ट्रांसफर": "transfer", "सिम": "sim", "केस": "case", "स्क्रीन": "screen", "साइबर": "cyber", "क्राइम": "crime", "ब्रांच": "branch",
    "कोर्ट": "court", "वारंट": "warrant", "मनी": "money", "ड्रग्स": "drugs", "पेमेंट": "payment", "कस्टम": "custom", "कस्टम्स": "customs",
    "टैक्स": "tax", "एफआईआर": "fir", "आरबीआई": "rbi", "लॉन्ड्रिंग": "laundering", "ब्लॉक": "block", "पासवर्ड": "password", "ऐप": "app",
    "एप": "app", "डाउनलोड": "download", "इंस्टॉल": "install", "परमिशन": "permission", "डिपॉजिट": "deposit", "सेफ": "safe",
    "अप्रूव": "approve", "सीबीआई": "cbi", "एनसीबी": "ncb", "इनकम": "income", "सुप्रीम": "supreme", "हाई": "high", "मजिस्ट्रेट": "magistrate",
    "जज": "judge", "इंस्पेक्टर": "inspector", "ट्राई": "trai", "इंटरपोल": "interpol", "ऑफिसर": "officer", "स्टेशन": "station",
    "केवाईसी": "kyc", "मैनेजर": "manager", "हेड": "head", "ऑफिस": "office", "कस्टमर": "customer", "केयर": "care", "पैन": "pan",
    "फंडिंग": "funding", "बुक": "book", "फेक": "fake", "पासपोर्ट": "passport", "डिजिटल": "digital", "फ्रीज": "freeze",
    "कनेक्शन": "connection", "वीडियो": "video", "कैमरा": "camera", "डिस्कनेक्ट": "disconnect", "फोन": "phone", "यूपीआई": "upi",
    "सीवीवी": "cvv", "एनीडेस्क": "anydesk", "टीमव्यूअर": "teamviewer", "क्विक": "quick", "सपोर्ट": "support", "एपीके": "apk",
    "क्लिक": "click", "फीस": "fees", "सिक्योरिटी": "security", "रिफंडेबल": "refundable", "वेरिफिकेशन": "verification", "क्यूआर": "qr",
    "स्कैन": "scan", "रिक्वेस्ट": "request", "विड्रॉल": "withdrawal", "गूगल": "google", "पे": "pay", "फोनपे": "phonepe", "पेटीएम": "paytm",
    "सेविंग्स": "savings", "लॉटरी": "lottery", "लकी": "lucky", "ड्रा": "draw", "केबीसी": "kbc", "कैशबैक": "cashback", "रिफंड": "refund",
    "पार्ट": "part", "टाइम": "time", "टास्क": "task", "कमीशन": "commission", "बोनस": "bonus", "गिफ्ट": "gift", "प्रॉफिट": "profit",
    "वीआईपी": "vip", "परसेंट": "percent", "गारंटी": "guarantee", "एक्सीडेंट": "accident", "मिनट": "minute",
}
LOAN = {normalize(k): v for k, v in LOAN.items()}


def variants(phrase: str) -> list[str]:
    toks = phrase.split(" ")
    idx = []
    for i, t in enumerate(toks):
        base = t[:-1] if t.endswith("*") else t
        if t != "~" and normalize(base) in LOAN:
            idx.append(i)
    if not idx or len(idx) > 3:
        return []
    out = []
    for r in range(1, len(idx) + 1):
        for combo in itertools.combinations(idx, r):
            v = list(toks)
            for i in combo:
                star = v[i].endswith("*")
                v[i] = LOAN[normalize(v[i].rstrip("*"))] + ("*" if star else "")
            out.append(" ".join(v))
    return out


def expand(d: dict) -> int:
    added = 0
    for cat in d["categories"].values():
        have = set(cat["phrases"])
        for p in list(cat["phrases"]):
            for v in variants(p):
                if v not in have:
                    cat["phrases"].append(v)
                    have.add(v)
                    added += 1
    return added


if __name__ == "__main__":
    path = ROOT / "assets/cues.json"
    d = json.loads(path.read_text(encoding="utf-8"))
    n = expand(d)
    print("added", n, "mixed-script variants")
    if "--check" not in sys.argv:
        path.write_text(json.dumps(d, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
