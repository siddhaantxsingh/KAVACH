import json
from pathlib import Path

from kavach_core.langpick import LangPick

ROOT = Path(__file__).resolve().parents[1]


def test_vectors_match_python():
    p = LangPick()
    for c in json.loads((ROOT / "assets/parity/langpick.json").read_text(encoding="utf-8")):
        assert abs(p.score(c["en"], c["hi"]) - c["score"]) < 1e-9
        assert p.pick(c["en"], c["hi"]) == c["pick"]


def test_clear_cases():
    p = LangPick()
    # real outputs of both models on one sentence of test_call_kyc_bank_en.wav
    assert p.pick("Your account will be blocked within two hours because your KYC is not updated",
                  "योर अकाउंट विल बी ब्लॉक्ड विद इन टू आवर्स बिकॉज योर केवाईसी इज नॉट अपडेटेड") == "en"
    # borderline sentence right after: keeps English
    assert p.pick("I am calling from the SBI headad Office, KYC deepartment",
                  "आई एम कॉलिंग फ्रॉम दी एसपीआई हेद ऑफिस के वईसीी डिपार्टमेंट") == "en"
    assert p.pick("Hami Akikard per At Hazar Charo Pajas Rubeka", "हमने आपके कार्ड पर आठ हज़ार चार सौ पचास रुपए का") == "hi"
    # a 1-word fragment keeps the previous language
    assert p.pick("okay", "ओके") == "hi"
