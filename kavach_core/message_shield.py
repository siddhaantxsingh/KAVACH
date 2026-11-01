"""Message Shield: scam SMS / WhatsApp-notification screening with Link Check.

For every incoming message text:
  * hashed linear SMS classifier -> p_scam
  * phrase cues (same lexicon as calls)
  * every URL in the text -> UrlChecker verdict
Message level:
  dangerous  p >= 0.90, or any dangerous link, or (p >= 0.6 and an action/payment cue)
  suspicious p >= 0.60, or any suspicious link
  safe       otherwise
Dangerous messages / links are forwarded to the kill-chain (scam_sms /
dangerous_link events) so a call that follows is judged with that context.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from .features import LinearModel
from .killchain import Event
from .lexicon import Lexicon
from .url_check import UrlChecker, UrlVerdict, extract_urls

ACTION_CATS = {"credential_request", "remote_access", "link_click", "payment_request"}


@dataclass
class MessageVerdict:
    text: str
    p_scam: float
    level: str
    cues: dict[str, int]
    urls: list[UrlVerdict] = field(default_factory=list)
    top_tokens: list[tuple[str, float]] = field(default_factory=list)

    def events(self, t: float) -> list[Event]:
        evs = []
        if self.level == "dangerous":
            evs.append(Event(t, "sms", "scam_sms", 2.0, self.text[:80]))
        for u in self.urls:
            if u.level == "dangerous":
                evs.append(Event(t, "link", "dangerous_link", 2.5, u.host))
        for c, n in self.cues.items():
            if c != "safety_advice":
                evs.append(Event(t, "sms", c, 0.5 * n, "sms"))
        return evs


class MessageShield:
    def __init__(self, sms_model: LinearModel | None, lexicon: Lexicon, urls: UrlChecker):
        self.model = sms_model
        self.lex = lexicon
        self.urls = urls

    def check(self, text: str) -> MessageVerdict:
        p = self.model.predict_proba(text) if self.model else 0.0
        cues = self.lex.categories_in(text)
        uv = [self.urls.check(u) for u in extract_urls(text)]
        worst = max((u.risk for u in uv), default=0.0)
        has_action = bool(set(cues) & ACTION_CATS) or bool(uv)
        if p >= 0.9 or worst >= 0.8 or (p >= 0.6 and has_action):
            level = "dangerous"
        elif p >= 0.6 or worst >= 0.5:
            level = "suspicious"
        else:
            level = "safe"
        top = self.model.explain(text, 4) if self.model and level != "safe" else []
        return MessageVerdict(text, round(p, 4), level, cues, uv, top)
