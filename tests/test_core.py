"""Unit tests for the KAVACH reference core. Run: python -m pytest -q tests"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from kavach_core.features import LinearModel, fnv1a32, vectorize_tokens  # noqa: E402
from kavach_core.guardian import Guardian  # noqa: E402
from kavach_core.killchain import Event, KillChain  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.llm import extract_json_object, fallback_verdict, format_llama3, system_prompt, validate  # noqa: E402
from kavach_core.message_shield import MessageShield  # noqa: E402
from kavach_core.normalize import normalize  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402
from kavach_core.url_check import UrlChecker, extract_urls, osa_distance  # noqa: E402

MODELS = ROOT / "assets/models"


@pytest.fixture(scope="module")
def lex():
    return Lexicon()


@pytest.fixture(scope="module")
def pb():
    return Playbook()


# ---------------------------------------------------------------- normalise / hash
def test_normalize_basic():
    assert normalize("Don't  DISCONNECT!!  the call…") == "dont disconnect the call"
    assert normalize("ज़मानत") == normalize("जमानत")  # nukta removed
    assert normalize("०१२३ OTP।") == "0123 otp"
    assert normalize("  ") == ""


def test_fnv1a_known_values():
    # standard FNV-1a 32-bit test vectors
    assert fnv1a32("") == 0x811C9DC5
    assert fnv1a32("a") == 0xE40C292C
    assert fnv1a32("foobar") == 0xBF9CF968


def test_vector_is_unit_norm():
    v, _ = vectorize_tokens(["police", "arrest", "warrant"], 1 << 18)
    assert abs(sum(x * x for x in v.values()) - 1.0) < 1e-9


# ---------------------------------------------------------------- lexicon
def test_lexicon_hindi_and_hinglish(lex):
    cats = lex.categories_in("मैं मुंबई पुलिस से बोल रहा हूं, किसी को मत बताइए, फोन मत काटिए")
    assert {"authority_police", "isolation"} <= set(cats)
    cats = lex.categories_in("aapke aadhaar se money laundering hua hai, call mat kaatna")
    assert {"identity_misuse", "isolation"} <= set(cats)


def test_negation_suppresses_request(lex):
    assert "credential_request" not in lex.categories_in("please never share your OTP with anyone")
    assert "safety_advice" in lex.categories_in("please never share your OTP with anyone")
    assert "credential_request" in lex.categories_in("dont worry just tell me the otp")
    assert "credential_request" not in lex.categories_in("ओटीपी किसी को शेयर मत करना")


def test_prefix_and_gap(lex):
    hits = lex.match("you will be arrested today")
    assert any(h.category == "threat" and h.phrase == "arrest*" for h in hits)
    assert "credential_request" in lex.categories_in("please tell me the six digit otp")


# ---------------------------------------------------------------- URL check
def test_url_extract():
    us = extract_urls("Update KYC at sbi-kyc.xyz/u now or https://bit.ly/abc. Call 9876543210.")
    assert us == ["sbi-kyc.xyz/u", "https://bit.ly/abc"]


def test_url_rules():
    c = UrlChecker()
    assert c.check("https://onlinesbi.sbi.bank.in/").level == "safe"
    assert c.check("hdfc.bank.in/login").level == "safe"
    assert "brand_in_unofficial_domain" in c.check("sbi-kyc-update.xyz/login").reasons
    assert "typosquat" in c.check("hdfcbnak.com").reasons
    assert "homoglyph" in c.check("paytrn.com").reasons
    assert "apk_download" in c.check("rto-challan.top/echallan.apk").reasons
    assert "user_content_brand" in c.check("https://sites.google.com/view/sbi-yono-kyc").reasons
    assert c.check("https://kotaku.com").reasons == []  # real word, not a typo of kotak


def test_osa_distance():
    assert osa_distance("hdfcbnak", "hdfcbank") == 1
    assert osa_distance("abc", "abc") == 0
    assert osa_distance("kotaku", "kotak") == 1


# ---------------------------------------------------------------- kill-chain
def _kc(lex):
    return KillChain({n: c.stage for n, c in lex.categories.items()})


def test_killchain_isolation_authority_stops(lex):
    kc = _kc(lex)
    kc.add(Event(10, "call", "authority_police", 2.0))
    assert kc.assess(11).level in ("info", "none", "caution")
    kc.add(Event(30, "call", "isolation", 2.5))
    a = kc.assess(31)
    assert a.level == "stop" and a.rule == "isolation+authority"


def test_killchain_safety_advice_blocks_hook_action(lex):
    kc = _kc(lex)
    kc.add(Event(1, "call", "authority_bank", 1.0))
    kc.add(Event(2, "call", "safety_advice", -2.0))
    kc.add(Event(3, "call", "threat", 1.5))
    kc.add(Event(4, "call", "credential_request", 2.5))
    assert kc.assess(5).level != "stop"


def test_killchain_cross_channel(lex):
    kc = _kc(lex)
    kc.add(Event(0, "call", "authority_bank", 1.0))
    kc.add(Event(5, "call", "threat", 1.5))
    kc.add(Event(6, "call", "urgency", 0.75))
    kc.add(Event(40, "link", "dangerous_link", 2.5))
    a = kc.assess(41)
    assert a.level == "stop" and a.rule == "call+action:dangerous_link"


def _run(lex, evs, now):
    kc = _kc(lex)
    for e in evs:
        kc.add(Event(*e))
    a = kc.assess(now)
    return a.level, a.rule


def test_one_phone_mode_unknown_call_plus_action(lex):
    # no conversation heard at all: unknown call + dangerous link during it -> STOP
    assert _run(lex, [(0, "phone", "unknown_call_start", 0.0), (60, "link", "dangerous_link", 2.5)], 61) == ("stop", "unknown-call+dangerous_link")
    # risky APK installed 6 min after the unknown call ended still belongs to it
    assert _run(lex, [(0, "phone", "unknown_call_start", 0.0), (100, "phone", "call_end", 0.0), (460, "app", "risky_apk", 2.5)], 461)[0] == "stop"
    # ... but not 15 min later (falls back to the single-channel CAUTION)
    assert _run(lex, [(0, "phone", "unknown_call_start", 0.0), (100, "phone", "call_end", 0.0), (1000, "app", "risky_apk", 2.5)], 1001)[0] == "caution"


def test_payment_app_during_unknown_call(lex):
    # paying during a short unknown call (e.g. a delivery agent) is not an alarm
    assert _run(lex, [(0, "phone", "unknown_call_start", 0.0), (30, "app", "payment_app_opened", 0.0)], 31)[0] == "none"
    # during a long unknown call it is a CAUTION
    assert _run(lex, [(0, "phone", "unknown_call_start", 0.0), (200, "app", "payment_app_opened", 0.0)], 201) == ("caution", "long-unknown-call+payment")


def test_fused_conversation_plus_action(lex):
    # Guardian said the conversation is suspicious + victim opens a payment app in the same call -> STOP at once
    assert _run(lex, [(0, "phone", "unknown_call_start", 0.0), (10, "call", "scam_call", 2.0), (30, "app", "payment_app_opened", 0.0)], 31) == \
        ("stop", "call+action:payment_app_opened")
    # same on the Guardian, which heard the cues itself (no call-awareness events)
    assert _run(lex, [(0, "call", "authority_police", 2.0), (5, "call", "urgency", 0.75), (8, "call", "threat", 1.5),
                      (20, "app", "payment_app_opened", 0.0)], 21)[1] == "call+action:payment_app_opened"


def test_killchain_decay_and_sticky(lex):
    kc = _kc(lex)
    kc.add(Event(0, "call", "authority_police", 2.0))
    kc.add(Event(1, "call", "identity_misuse", 2.0))
    kc.add(Event(2, "call", "threat", 1.5))
    assert kc.assess(3).level == "stop"
    assert kc.assess(10_000).level == "stop"  # sticky within a session
    assert kc.evidence(10_000)["threat"] < 0.01  # but evidence decays


# ---------------------------------------------------------------- LLM contract
def test_validate_good_json(pb):
    ids = [e["id"] for e in pb.entries]
    tr = "main cbi se bol raha hoon kisi ko mat batana paise transfer karo"
    raw = 'Sure! {"scam": true, "type": "digital_arrest", "stage": "isolation", "confidence": 0.93, ' \
          '"evidence": ["kisi ko mat batana", "made up quote"], "say": "Yeh scam hai, phone kaatiye aur 1930 par call kijiye.", "action": "hang_up"} done'
    v, errs = validate(raw, tr, ids)
    assert v is not None and v.scam and v.type == "digital_arrest"
    assert v.evidence == ["kisi ko mat batana"]  # non-verbatim quote dropped
    assert any("non-verbatim" in e for e in errs)


def test_validate_rejects_garbage(pb):
    ids = [e["id"] for e in pb.entries]
    assert validate("no json here", "x", ids)[0] is None
    assert validate('{"scam": "maybe"}', "x", ids)[0] is None
    v, _ = validate('{"scam": false, "type": "bogus", "stage": "x", "confidence": 7, "action": "dance"}', "x", ids)
    assert v.type == "none" and v.stage == "none" and v.confidence == 1.0 and v.action == "none"


def test_extract_json_handles_braces_in_strings():
    s = 'x {"say": "use {curly} braces", "scam": false} y'
    assert json.loads(extract_json_object(s))["say"] == "use {curly} braces"


def test_prompt_prefix_is_constant(pb):
    ids = [e["id"] for e in pb.entries]
    p1, s1 = format_llama3(system_prompt(ids), "a")
    p2, s2 = format_llama3(system_prompt(ids), "b")
    assert p1 == p2 and s1 != s2 and p1.startswith("<|begin_of_text|>")


def test_fallback_verdict_hindi(lex, pb):
    labels = {n: (c.label_en, c.label_hi) for n, c in lex.categories.items()}
    ret = [r.entry for r in pb.rank({"authority_police": 2, "isolation": 2.5}, "police do not tell family")]
    v = fallback_verdict("stop", {"authority_police": 2, "isolation": 2.5}, ret, labels, ["contact", "isolation"], "hi")
    assert v.scam and v.type == "digital_arrest" and "1930" in v.say and v.action == "hang_up"


# ---------------------------------------------------------------- end to end
@pytest.mark.skipif(not (MODELS / "cue_model.bin").exists(), reason="train models first")
def test_guardian_digital_arrest_call(lex, pb):
    g = Guardian(lex, LinearModel.load(MODELS, "cue_model"), pb)
    segs = [(5, "hello namaste"), (12, "main cyber crime branch se inspector bol raha hoon"),
            (20, "aapke aadhaar se money laundering hua hai arrest warrant nikla hai"),
            (30, "phone mat kaatna aur kisi ko mat batana"), (40, "apne paise rbi ke safe account me transfer kijiye")]
    g.run_transcript(segs)
    assert g.level == "stop"
    stop_t = next(s.t for s in g.history if s.level == "stop")
    assert stop_t < 40  # before the payment request


@pytest.mark.skipif(not (MODELS / "cue_model.bin").exists(), reason="train models first")
def test_guardian_genuine_bank_call_no_stop(lex, pb):
    g = Guardian(lex, LinearModel.load(MODELS, "cue_model"), pb)
    segs = [(6, "hello this is the fraud monitoring team of hdfc we noticed a card transaction of 8450"),
            (12, "was this done by you"), (15, "no"),
            (24, "we will block the card please never share your otp or pin with anyone the bank will never ask for it")]
    g.run_transcript(segs)
    assert g.level != "stop"


@pytest.mark.skipif(not (MODELS / "sms_model.bin").exists(), reason="train models first")
def test_message_shield():
    ms = MessageShield(LinearModel.load(MODELS, "sms_model"), Lexicon(), UrlChecker(LinearModel.load(MODELS, "url_model")))
    bad = ms.check("Dear Customer your SBI account will be blocked today. Update KYC: http://sbi-kyc-update.xyz/login")
    assert bad.level == "dangerous" and any(e.kind == "dangerous_link" for e in bad.events(0))
    ok = ms.check("482913 is your OTP for transaction of INR 1,299 at AMAZON. Do not share it with anyone. - HDFC Bank")
    assert ok.level == "safe"


@pytest.mark.skipif(not (MODELS / "cue_model.bin").exists(), reason="train models first")
def test_model_roundtrip_matches_explain():
    m = LinearModel.load(MODELS, "cue_model")
    ex = m.explain("aapke aadhaar par money laundering ka case hai kisi ko mat batana")
    assert ex and all(c > 0 for _, c in ex)
