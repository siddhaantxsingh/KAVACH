from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "bench"))

from kavach_core.killchain import CALL_START, Event, KillChain  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.netwatch import DomainModel, FlowModel, NetWatch, domain_core, domain_tokens, flow_vector  # noqa: E402
from netwatch_sim import trace  # noqa: E402


def _events(name, seed=0, suspect=None):
    nw = NetWatch()
    out = []
    for t, app, tx, rx, fg, sus in trace(name, seed):
        out += nw.observe(t, app, tx, rx, fg, sus if suspect is None else suspect)
    return out


def test_spyware_upload_and_beacon_detected():
    assert any(e.kind == "suspicious_upload" for e in _events("spyware_photo_exfil"))
    assert any(e.kind == "suspicious_upload" for e in _events("slow_exfil"))
    assert any(e.kind == "beaconing_app" for e in _events("rat_beacon_300s"))


def test_normal_use_is_quiet():
    for name in ["chat_foreground", "photo_share_foreground", "video_streaming", "cloud_backup_store_app"]:
        assert _events(name) == [], name


def test_trusted_store_apps_never_flagged():
    # a non-suspect app never raises an event, whatever it does
    assert _events("spyware_photo_exfil", suspect=False) == []


def test_cooldown_one_alert_per_hour():
    ev = [e for e in _events("rat_beacon_60s") if e.kind == "beaconing_app"]
    assert all(b.t - a.t >= 3600 for a, b in zip(ev, ev[1:]))


def test_domain_core_and_tokens():
    assert domain_core("www.a.example.co.in") == (["a", "example"], "co.in")
    assert domain_tokens("") == []
    assert "#tld:com" in domain_tokens("google.com")


def test_domain_model_separates_random_from_real():
    dm = DomainModel.load(ROOT / "assets/models")
    for real in ["google.com", "onlinesbi.sbi", "hdfcbank.com", "irctc.co.in", "wikipedia.org"]:
        assert dm.score(real) < dm.threshold, real
    for dga in ["xjwqkzpvbnfhtrq.biz", "kdjfhgkdjfhgqwpz.ru", "cvyh1po636avyrsxebwbkn7.net"]:
        assert dm.score(dga) >= dm.threshold, dga


def test_flow_vector_shape_and_model():
    fm = FlowModel.load(ROOT / "assets/models/flow_model.json")
    v = flow_vector({"proto": "tcp", "state": "FIN", "service": "http"})
    assert len(v) == len(fm.mean)
    assert 0.0 <= fm.score({"proto": "tcp", "state": "FIN", "service": "http", "dur": 1.2, "spkts": 10, "dpkts": 12}) <= 1.0


def test_network_event_during_unknown_call_is_stop():
    lex = Lexicon()
    kc = KillChain({n: c.stage for n, c in lex.categories.items()})
    kc.add(Event(0.0, "phone", CALL_START, 0.0))
    kc.add(Event(120.0, "network", "suspicious_upload", 2.0, "spy"))
    a = kc.assess(130.0)
    assert a.level == "stop" and a.rule == "unknown-call+suspicious_upload"
    kc2 = KillChain({n: c.stage for n, c in lex.categories.items()})
    kc2.add(Event(0.0, "phone", CALL_START, 0.0))
    kc2.add(Event(60.0, "network", "malicious_domain", 1.5, "x.biz"))
    assert kc2.assess(61.0).level == "stop"
