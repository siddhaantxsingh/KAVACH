"""Write golden vectors that the Kotlin core must reproduce exactly.

Output: assets/parity/vectors.json (read by android/core tests). Regenerate
after any change to the lexicon, models or algorithms:
    python tests/make_parity_vectors.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from kavach_core.features import LinearModel, fnv1a32, vectorize_tokens  # noqa: E402
from kavach_core.guardian import Guardian  # noqa: E402
from kavach_core.killchain import Event, KillChain  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.llm import format_llama3, system_prompt, user_prompt, validate  # noqa: E402
from kavach_core.message_shield import MessageShield  # noqa: E402
from kavach_core.normalize import normalize, tokens  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402
from kavach_core.url_check import UrlChecker, extract_urls, split_url  # noqa: E402

TEXTS = [
    "Don't DISCONNECT the call!! It's being recorded…",
    "मैं मुंबई पुलिस से बोल रहा हूँ, आपके आधार कार्ड का इस्तेमाल मनी लॉन्ड्रिंग में हुआ है। किसी को मत बताइए।",
    "aapke aadhaar se money laundering hua hai, call mat kaatna aur kisi ko kuch mat batana",
    "Sir this is HDFC fraud monitoring. Please never share your OTP with anyone.",
    "dont worry just tell me the otp you received",
    "ज़मानत के लिए ₹५०,००० भेजिए 🙏 तुरंत",
    "Your SBI account will be BLOCKED today. Update KYC: http://sbi-kyc-update.xyz/login",
    "482913 is your OTP for transaction of INR 1,299 at AMAZON. Do not share it with anyone. - HDFC Bank",
    "ओटीपी किसी को शेयर मत करना",
    "pay the withdrawal tax first then you can take out your profit",
    "",
]
URLS = ["https://onlinesbi.sbi.bank.in/", "sbi-kyc-update.xyz/login", "hdfcbnak.com", "http://paytrn.com/refund", "https://xn--pytm-5cd.com",
        "bit.ly/3xYz", "http://45.33.12.9/sbi", "https://sites.google.com/view/sbi-yono-kyc", "rto-challan.top/echallan.apk", "hdfc.bank.in/login",
        "kotaku.com", "icicl.com", "yonosbi-rewards.in", "user@evil.com/x", "https://www.amazon.in/gp/your-orders", "pm-kisan-bonus.site/claim"]


def main() -> None:
    lex, pb = Lexicon(), Playbook()
    cue = LinearModel.load(ROOT / "assets/models", "cue_model")
    sms = LinearModel.load(ROOT / "assets/models", "sms_model")
    url = LinearModel.load(ROOT / "assets/models", "url_model")
    uc = UrlChecker(url)
    ms = MessageShield(sms, lex, uc)
    out: dict = {"normalize": [[t, normalize(t)] for t in TEXTS]}
    out["fnv"] = [[s, fnv1a32(s)] for s in ["", "a", "foobar", "w|पुलिस", "c| ओट", "kk|authority_police+isolation"]]
    out["lexicon"] = [[t, [[h.category, h.phrase, h.start, h.end] for h in lex.match(t)]] for t in TEXTS]
    small = tokens("police arrest otp")
    v, _ = vectorize_tokens(small, 1 << 18, lex.match_tokens(small))
    out["vector_small"] = {"tokens": small, "entries": sorted([[k, val] for k, val in v.items()])}
    out["logits"] = {
        "cue_model": [[t, cue.logit_tokens(tokens(t))] for t in TEXTS],
        "sms_model": [[t, sms.logit_tokens(tokens(t))] for t in TEXTS],
        "url_model": [[uc.model_text(*split_url(u)), url.logit_tokens(tokens(uc.model_text(*split_url(u))))] for u in URLS],
    }
    out["urls_extract"] = [[t, extract_urls(t)] for t in TEXTS]
    out["url_verdicts"] = [[u, (vv := uc.check(u)).level, vv.risk, vv.reasons] for u in URLS]
    out["messages"] = [[t, (m := ms.check(t)).level, m.p_scam] for t in TEXTS if t]

    stage_of = {n: c.stage for n, c in lex.categories.items()}
    scripts = [
        [[10, "call", "authority_police", 2.0], [30, "call", "isolation", 2.5]],
        [[1, "call", "authority_bank", 1.0], [2, "call", "safety_advice", -2.0], [3, "call", "threat", 1.5], [4, "call", "credential_request", 2.5]],
        [[0, "call", "authority_bank", 1.0], [5, "call", "threat", 1.5], [6, "call", "urgency", 0.75], [40, "link", "dangerous_link", 2.5]],
        [[0, "call", "payment_request", 2.5], [3, "call", "link_click", 1.25]],
        [[0, "call", "credential_request", 2.5], [600, "call", "payment_request", 2.5]],
        # call awareness / one-phone mode / fused rule
        [[0, "phone", "unknown_call_start", 0.0], [60, "link", "dangerous_link", 2.5]],
        [[0, "phone", "unknown_call_start", 0.0], [30, "app", "payment_app_opened", 0.0], [200, "app", "payment_app_opened", 0.0]],
        [[0, "phone", "unknown_call_start", 0.0], [10, "call", "scam_call", 2.0], [30, "app", "payment_app_opened", 0.0]],
        [[0, "phone", "unknown_call_start", 0.0], [100, "phone", "call_end", 0.0], [460, "app", "risky_apk", 2.5]],
        [[0, "phone", "unknown_call_start", 0.0], [100, "phone", "call_end", 0.0], [1000, "app", "risky_apk", 2.5]],
        [[0, "call", "authority_police", 2.0], [5, "call", "urgency", 0.75], [8, "call", "threat", 1.5], [20, "app", "payment_app_opened", 0.0]],
        [[0, "network", "beaconing_app", 1.5], [40, "network", "suspicious_upload", 2.0]],
    ]
    kc_out = []
    for sc in scripts:
        kc = KillChain(stage_of)
        steps = []
        for t, ch, kind, w in sc:
            kc.add(Event(t, ch, kind, w))
            a = kc.assess(t + 1)
            steps.append([t + 1, a.level, a.rule, round(a.score, 3)])
        kc_out.append({"events": sc, "steps": steps})
    out["killchain"] = kc_out

    out["retrieval"] = [[act, txt, [r.entry["id"] for r in pb.rank(act, txt)]] for act, txt in [
        ({"authority_police": 2.0, "isolation": 2.5}, "police do not tell family"),
        ({"reward_greed": 1.2, "payment_request": 2.5}, "lottery prize processing fee"),
        ({"remote_access": 2.5, "credential_request": 2.5}, "install anydesk tell me the code"),
    ]]

    ids = [e["id"] for e in pb.entries]
    out["prompt"] = {"system": system_prompt(ids), "user": user_prompt("kisi ko mat batana", {"isolation": 2.5}, pb.entries[:1], "hi"),
                     "formatted": list(format_llama3(system_prompt(ids), "u"))}
    raws = ['Sure! {"scam": true, "type": "digital_arrest", "stage": "isolation", "confidence": 0.93, "evidence": ["kisi ko mat batana", "made up"], "say": "Phone kaatiye.", "action": "hang_up"}',
            '{"scam": "maybe"}', "no json", '{"scam": false, "type": "bogus", "stage": "x", "confidence": 7, "action": "dance"}']
    out["validate"] = []
    for r in raws:
        vv, _ = validate(r, "main cbi se hoon kisi ko mat batana", ids)
        out["validate"].append([r, json.loads(vv.to_json()) if vv else None])

    calls = [json.loads(l) for l in open(ROOT / "data/dev/handwritten_calls.jsonl", encoding="utf-8")][:12]
    g = Guardian(lex, cue, pb)
    gout = []
    for c in calls:
        segs = [(s["t1"], s["text"]) for s in c["segments"]]
        hist = g.run_transcript(segs)
        gout.append({"id": c["id"], "segments": segs, "levels": [[round(h.t, 3), h.level, round(h.p_scam, 4)] for h in hist], "final_rule": g.rule})
    out["guardian"] = gout

    dst = ROOT / "assets/parity/vectors.json"
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(out, ensure_ascii=False, indent=1))
    print("wrote", dst, dst.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
