"""Call Guardian streaming engine (reference implementation of the Android logic).

Pipeline per call
  ASR final segments ──► token buffer ──► after every final segment and at
  least every TICK_S seconds:
     1. phrase cues on new tokens  ──► kill-chain events (channel="call")
     2. hashed linear cue classifier on the last WINDOW_TOKENS tokens
     3. level = max(kill-chain level, classifier level)  (sticky)
     4. on escalation: instant fallback verdict (spoken), then optional Llama
        verdict; Llama may raise CAUTION to STOP early but can never lower a
        rule-based STOP (fail-safe).

Classifier levels use two consecutive ticks to avoid one-window spikes:
  CAUTION  if p >= p_caution on 2 consecutive ticks
  STOP     if p >= p_stop    on 2 consecutive ticks and kill-chain >= CAUTION
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

from .features import LinearModel
from .killchain import LEVELS, Event, KillChain, KillChainConfig
from .lexicon import Lexicon
from .llm import Verdict, fallback_verdict, format_llama3, system_prompt, user_prompt, validate, repair_suffix
from .normalize import tokens
from .retrieval import Playbook

TICK_S = 3.0
WINDOW_TOKENS = 120
RESCAN_OVERLAP = 12


@dataclass
class GuardianConfig:
    p_caution: float = 0.80
    p_stop: float = 0.93
    llm_escalate_conf: float = 0.80
    lang: str = "hi"
    killchain: KillChainConfig = field(default_factory=KillChainConfig)


@dataclass
class Alert:
    t: float
    level: str
    rule: str
    verdict: Verdict


@dataclass
class GuardState:
    t: float
    level: str
    p_scam: float
    score: float
    active: dict[str, float]
    stages: list[str]
    rule: str


LlmFn = Callable[[str, str], str]  # (prefix, suffix) -> raw model text


class Guardian:
    def __init__(self, lexicon: Lexicon, cue_model: LinearModel | None, playbook: Playbook,
                 config: GuardianConfig | None = None, llm_fn: LlmFn | None = None):
        self.lex = lexicon
        self.model = cue_model
        self.pb = playbook
        self.cfg = config or GuardianConfig()
        self.llm_fn = llm_fn
        self.type_ids = [e["id"] for e in playbook.entries]
        self.system = system_prompt(self.type_ids)
        self.labels = {n: (c.label_en, c.label_hi) for n, c in lexicon.categories.items()}
        self.kc = KillChain({n: c.stage for n, c in lexicon.categories.items()}, self.cfg.killchain)
        self.reset()

    def reset(self) -> None:
        self.toks: list[str] = []
        self.tok_time: list[float] = []
        self.texts: list[tuple[float, str]] = []
        self.scanned = 0
        self.seen_hits: set[tuple[str, int]] = set()
        self.seen_segments: set[tuple[str, float]] = set()
        self.p_hist: list[float] = []
        self.level = "none"
        self.rule = ""
        self.alerts: list[Alert] = []
        self.history: list[GuardState] = []
        self.kc.reset()

    # ---- input
    def on_segment(self, text: str, t: float) -> None:
        tk = tokens(text)
        if not tk:
            return
        self.texts.append((t, text))
        self.toks.extend(tk)
        self.tok_time.extend([t] * len(tk))

    def add_external(self, ev: Event) -> None:
        """Events from the main phone (SMS, links, APKs, screen share) over the device link."""
        self.kc.add(ev)

    def transcript_window(self) -> str:
        return " ".join(self.toks[-WINDOW_TOKENS:])

    # ---- periodic evaluation
    def tick(self, now: float) -> GuardState:
        start = max(0, self.scanned - RESCAN_OVERLAP)
        sub = self.toks[start:]
        for h in self.lex.match_tokens(sub):
            key = (h.category, start + h.start)
            if key in self.seen_hits:
                continue
            self.seen_hits.add(key)
            t_hit = self.tok_time[start + h.end - 1]
            # one piece of evidence per category per ASR segment: repeating a word
            # inside one sentence must not stack up evidence
            seg_key = (h.category, t_hit)
            if seg_key in self.seen_segments:
                continue
            self.seen_segments.add(seg_key)
            self.kc.add(Event(t_hit, "call", h.category, h.weight, h.phrase))
        self.scanned = len(self.toks)

        p = 0.0
        if self.model is not None and self.toks:
            p = _sigmoid_safe(self.model.logit_tokens(self.toks[-WINDOW_TOKENS:]))
        self.p_hist.append(p)
        a = self.kc.assess(now)

        level, rule = a.level, a.rule
        last2 = self.p_hist[-2:]
        if len(last2) == 2:
            if min(last2) >= self.cfg.p_stop and LEVELS.index(a.level) >= LEVELS.index("caution"):
                if LEVELS.index("stop") > LEVELS.index(level):
                    level, rule = "stop", "classifier"
            elif min(last2) >= self.cfg.p_caution and LEVELS.index(level) < LEVELS.index("caution"):
                level, rule = "caution", "classifier"

        if LEVELS.index(level) > LEVELS.index(self.level):
            self._escalate(now, level, rule, a)
        st = GuardState(now, self.level, round(p, 4), a.score, a.active, a.stages, self.rule)
        self.history.append(st)
        return st

    def _escalate(self, now: float, level: str, rule: str, a) -> None:
        self.level, self.rule = level, rule
        if level not in ("caution", "stop"):
            return
        window = self.transcript_window()
        retrieved = [r.entry for r in self.pb.rank(a.active, window)]
        verdict = fallback_verdict(level, a.active, retrieved, self.labels, a.stages, self.cfg.lang, self.pb.helpline["number"])
        self.alerts.append(Alert(now, level, rule, verdict))
        if self.llm_fn is None:
            return
        llm_v = self.ask_llm(window, a.active, retrieved)
        if llm_v is None:
            return
        self.alerts.append(Alert(now, self.level, "llm", llm_v))
        if (self.level == "caution" and llm_v.scam and llm_v.confidence >= self.cfg.llm_escalate_conf
                and llm_v.stage in ("isolation", "action", "payment")):
            self.level, self.rule = "stop", "llm-early-warning"
            self.kc.peak, self.kc.peak_rule = "stop", "llm-early-warning"

    def ask_llm(self, window: str, active: dict[str, float], retrieved: list[dict]) -> Verdict | None:
        prefix, suffix = format_llama3(self.system, user_prompt(window, active, retrieved, self.cfg.lang))
        raw = self.llm_fn(prefix, suffix)
        v, errs = validate(raw, window, self.type_ids)
        if v is None:
            raw = self.llm_fn(prefix, repair_suffix(suffix, raw, errs))
            v, _ = validate(raw, window, self.type_ids)
        return v

    # ---- convenience for evaluation
    def run_transcript(self, segments: list[tuple[float, str]], tick_s: float = TICK_S, tail_s: float = 6.0,
                       event_driven: bool = True) -> list[GuardState]:
        """Replay (t_end, text) segments in call time.

        Ticks happen on a fixed tick_s grid and, when event_driven is set (the
        shipped behaviour), additionally right after every final ASR segment,
        so a decisive sentence is judged as soon as it is recognised instead of
        up to tick_s later. A tick costs < 1 ms, so this is free.
        """
        self.reset()
        if not segments:
            return []
        end = segments[-1][0] + tail_s
        times = {round(k * tick_s, 3) for k in range(1, int(end / tick_s) + 1)}
        if event_driven:
            times |= {round(t, 3) for t, _ in segments}
        i = 0
        for t in sorted(times):
            while i < len(segments) and segments[i][0] <= t + 1e-9:
                self.on_segment(segments[i][1], segments[i][0])
                i += 1
            self.tick(t)
        return self.history


def _sigmoid_safe(z: float) -> float:
    import math

    return 1.0 / (1.0 + math.exp(-z)) if z >= 0 else math.exp(z) / (1.0 + math.exp(z))
