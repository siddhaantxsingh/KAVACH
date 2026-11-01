"""Cross-channel scam kill-chain engine.

Real Indian scams are sequences: an authority call, a threat or lure, an
isolation script ("don't disconnect, don't tell family"), then an action
(OTP, link, APK, screen share) and finally a payment. Single-channel filters
see one fragment; this engine keeps decaying evidence for every cue category
from every channel and escalates NONE -> INFO -> CAUTION -> STOP.

Evidence model
  evidence[c](t) = sum_i  w_i * 0.5 ** ((t - t_i) / half_life(channel_i))
  A category is *active* when its evidence >= ACTIVE (0.5).
  A stage is active when any of its categories is active.

Escalation rules (checked in order, first match wins; all thresholds are in
KillChainConfig and tuned on the dev split only):
  STOP  isolation active together with authority_police / threat / identity_misuse
  STOP  authority_police + identity_misuse + threat all active (the digital-
        arrest hook: no genuine officer does all three on a call)
  STOP  two different action/payment cues (e.g. OTP + collect request) and no
        genuine safety advice heard
  STOP  action-or-payment cue active together with a contact-or-hook cue other
        than bare urgency, and no genuine safety advice heard, i.e. a scripted
        path toward money
  STOP  cross-channel: a risky call (score >= caution) and a dangerous link /
        APK / remote-access / payment-app-while-screen-shared event from another
        channel inside the correlation window
  STOP  total weighted score >= stop_score
  CAUTION total score >= caution_score (halved when no contact/hook cue is
        active), or any hook cue + authority claim
  INFO  any positive cue
Levels are sticky (they never drop during a session) so a scammer cannot
"calm" the guard by switching to small talk; reset() ends the session.
"""
from __future__ import annotations

from dataclasses import dataclass, field

LEVELS = ["none", "info", "caution", "stop"]
CONTACT_HOOK = {"authority_police", "authority_bank", "identity_misuse", "threat", "urgency", "reward_greed", "family_emergency"}
ACTION_PAY = {"credential_request", "remote_access", "link_click", "payment_request"}
HOOK_FOR_ACTION = CONTACT_HOOK - {"urgency"}
STRONG_HOOK = {"authority_police", "threat", "identity_misuse"}
CROSS_KINDS = {"dangerous_link", "risky_apk", "remote_access_active", "payment_app_while_sharing", "scam_sms", "scam_call"}


@dataclass
class KillChainConfig:
    active: float = 0.5
    caution_score: float = 3.0
    stop_score: float = 7.0
    half_life_s: dict = field(default_factory=lambda: {"call": 600.0, "sms": 1800.0, "link": 1800.0, "app": 86400.0, "screen": 900.0, "network": 3600.0})
    cross_window_s: float = 1800.0
    cap_per_category: float = 3.0


@dataclass
class Event:
    t: float
    channel: str  # call | sms | link | app | screen | network
    kind: str  # cue category name, or a CROSS_KINDS event, or 'classifier'
    weight: float
    detail: str = ""


@dataclass
class Assessment:
    level: str
    score: float
    active: dict[str, float]
    stages: list[str]
    rule: str
    channels: list[str]


class KillChain:
    def __init__(self, stage_of: dict[str, str], config: KillChainConfig | None = None):
        self.stage_of = dict(stage_of)
        self.cfg = config or KillChainConfig()
        self.events: list[Event] = []
        self.peak = "none"
        self.peak_rule = ""

    def reset(self) -> None:
        self.events.clear()
        self.peak = "none"
        self.peak_rule = ""

    def add(self, ev: Event) -> None:
        self.events.append(ev)

    def evidence(self, now: float) -> dict[str, float]:
        ev: dict[str, float] = {}
        for e in self.events:
            if e.t > now:
                continue
            hl = self.cfg.half_life_s.get(e.channel, 600.0)
            v = e.weight * 0.5 ** ((now - e.t) / hl)
            ev[e.kind] = ev.get(e.kind, 0.0) + v
        return ev

    def assess(self, now: float) -> Assessment:
        cfg = self.cfg
        ev = self.evidence(now)
        active = {k: v for k, v in ev.items() if v >= cfg.active}
        # safety advice reduces the score but never removes other evidence
        score = sum(min(v, cfg.cap_per_category) for k, v in ev.items() if k != "safety_advice" and v > 0)
        score += ev.get("safety_advice", 0.0)  # negative weight
        if not (set(active) & CONTACT_HOOK):
            score *= 0.5  # requests without any pretext (office talk, family) count half
        cats = set(active) - {"safety_advice"}
        stages = sorted({self.stage_of[c] for c in cats if c in self.stage_of}, key=_stage_rank)
        channels = sorted({e.channel for e in self.events if e.t <= now})

        level, rule = "none", ""
        call_cats = {e.kind for e in self.events if e.channel == "call" and e.t <= now} & cats
        call_score = sum(min(v, cfg.cap_per_category) for k, v in ev.items() if k in call_cats)
        cross = [e for e in self.events if e.kind in CROSS_KINDS and 0 <= now - e.t <= cfg.cross_window_s]
        has_safety = ev.get("safety_advice", 0.0) <= -cfg.active

        if "isolation" in cats and cats & STRONG_HOOK:
            level, rule = "stop", "isolation+authority"
        elif STRONG_HOOK <= cats:
            level, rule = "stop", "authority+identity+threat"
        elif len(cats & ACTION_PAY) >= 2 and not has_safety:
            level, rule = "stop", "multi-action"
        elif cats & ACTION_PAY and cats & HOOK_FOR_ACTION and not has_safety:
            level, rule = "stop", "hook+action"
        elif cross and call_score >= cfg.caution_score:
            level, rule = "stop", "cross-channel:" + cross[-1].kind
        elif cross and len(cross) >= 2 and len({e.channel for e in cross}) >= 2:
            level, rule = "stop", "cross-channel-multi"
        elif score >= cfg.stop_score:
            level, rule = "stop", "score"
        elif score >= cfg.caution_score:
            level, rule = "caution", "score"
        elif cats & {"authority_police", "authority_bank"} and cats & (CONTACT_HOOK - {"authority_police", "authority_bank"}):
            level, rule = "caution", "authority+hook"
        elif cross:
            level, rule = "caution", "single-channel:" + cross[-1].kind
        elif score > 0 and cats:
            level, rule = "info", "cue"

        if LEVELS.index(level) > LEVELS.index(self.peak):
            self.peak, self.peak_rule = level, rule
        return Assessment(self.peak, round(score, 3), {k: round(v, 3) for k, v in active.items()}, stages, self.peak_rule, channels)


def _stage_rank(s: str) -> int:
    order = ["contact", "hook", "isolation", "action", "payment"]
    return order.index(s) if s in order else 99
