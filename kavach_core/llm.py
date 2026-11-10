"""Llama 3.2 1B advisor: prompt construction, JSON verdict validation, fallback.

Contract
  Llama receives a fixed system prefix (role, rules, JSON schema, list of scam
  type ids) and a per-alert user message (the 3 retrieved playbook entries,
  active cue categories with their scores, and the transcript window). It
  must answer with ONE JSON object:

    {"scam": true, "type": "digital_arrest", "stage": "isolation",
     "confidence": 0.92, "evidence": ["do not tell your family"],
     "say": "<= 35 words for the user, in the requested language",
     "action": "hang_up"}

  validate() enforces types and enums, clamps confidence, and drops every
  evidence quote that is not literally present in the transcript (after
  normalisation) - the model may not invent quotes. If parsing fails the app
  re-prompts once with the error; if that also fails, or the model is not
  loaded / times out / the phone is throttling, fallback_verdict() produces a
  verdict from the rules and playbook, so a warning is always raised.

Prefix caching: SYSTEM_PREFIX never changes, so the Android app pre-fills it
into the KV cache when guarding starts (LlmModule.prefillPrompt) and again in
the background after every alert; an alert then only pays for the user part.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field

from .normalize import normalize

STAGES = ["none", "contact", "hook", "isolation", "action", "payment"]
ACTIONS = ["none", "continue_with_care", "verify_independently", "do_not_share", "do_not_pay", "hang_up"]
MAX_SAY_WORDS = 35

BOT = "<|begin_of_text|>"
SH, EH, EOT = "<|start_header_id|>", "<|end_header_id|>", "<|eot_id|>"


def system_prompt(type_ids: list[str]) -> str:
    return (
        "You are KAVACH, an on-device scam-call guardian for people in India. "
        "You read a live phone-call transcript (Hindi, English or Hinglish; it comes from speech recognition and has errors) "
        "and decide whether the caller is running a scam.\n"
        "Rules:\n"
        "- Police, CBI, customs, courts and banks NEVER ask for money, OTP, PIN, screen sharing or app installs on a call, "
        "and there is no 'digital arrest' in Indian law.\n"
        "- Genuine calls (bank fraud alerts that say never share OTP, delivery agents, relatives) must not be called scams.\n"
        "- Quote evidence only as exact words from the transcript.\n"
        "- 'say' is spoken to a frightened, possibly elderly person: short, calm, clear, in the requested language, "
        "and must include the next step. Helpline: 1930.\n"
        "- 'say' is YOUR advice to the user in your own words. Never repeat what the caller said.\n"
        "Answer with ONE JSON object and nothing else, keys in this order:\n"
        '{"scam": true|false, "type": one of [' + ", ".join(type_ids) + ', "other", "none"], '
        '"stage": one of [' + ", ".join(STAGES) + '], "confidence": 0.0-1.0, '
        '"evidence": [up to 3 short exact quotes], "say": "max 35 words", '
        '"action": one of [' + ", ".join(ACTIONS) + "]}\n"
        'Example: {"scam": true, "type": "digital_arrest", "stage": "payment", "confidence": 0.9, '
        '"evidence": ["arrest warrant has been issued"], "say": "This is a scam. Real police never ask for money on a call. '
        'Do not send anything. Hang up and call 1930.", "action": "hang_up"}'
    )


def user_prompt(transcript: str, active: dict[str, float], retrieved: list[dict], lang: str) -> str:
    cues = ", ".join(f"{k}={v:.1f}" for k, v in sorted(active.items(), key=lambda x: -x[1])) or "none"
    pb = "\n".join(f"- {e['id']}: {e['story_en']} TRUTH: {e['truth_en']}" for e in retrieved)
    language = "Hindi (Devanagari)" if lang == "hi" else "English"
    return (
        f"Relevant scam patterns:\n{pb}\n"
        f"Detected cue scores: {cues}\n"
        f"Reply language for 'say': {language}\n"
        f"Transcript (most recent last):\n\"\"\"{transcript.strip()}\"\"\""
    )


def message_user_prompt(message: str, active: dict[str, float], url_reasons: list[str], retrieved: list[dict], lang: str) -> str:
    """Variant used by the user's phone for a high-risk SMS / WhatsApp message (same JSON contract)."""
    cues = ", ".join(f"{k}={v:.1f}" for k, v in sorted(active.items(), key=lambda x: -x[1])) or "none"
    pb = "\n".join(f"- {e['id']}: {e['story_en']} TRUTH: {e['truth_en']}" for e in retrieved)
    language = "Hindi (Devanagari)" if lang == "hi" else "English"
    links = "; ".join(url_reasons) or "none"
    return (
        f"Relevant scam patterns:\n{pb}\n"
        f"Detected cue scores: {cues}\nLink check findings: {links}\n"
        f"Reply language for 'say': {language}\n"
        f"This is a text MESSAGE the user received, not a call. Message:\n\"\"\"{message.strip()}\"\"\""
    )


NETWORK_KIND_TEXT = {
    "suspicious_upload": "an app secretly sent a lot of data from the phone",
    "beaconing_app": "an app keeps contacting a server in the background at a steady rhythm (remote-control pattern)",
    "malicious_domain": "an app contacted a fake or malware website",
}


def network_user_prompt(events: list[tuple[str, str]], in_unknown_call: bool, retrieved: list[dict], lang: str) -> str:
    """Variant used by the user's phone for Network Watch findings (same JSON contract).
    events: (kind, detail) pairs; details are quoted so 'evidence' can cite them."""
    pb = "\n".join(f"- {e['id']}: {e['story_en']} TRUTH: {e['truth_en']}" for e in retrieved)
    language = "Hindi (Devanagari)" if lang == "hi" else "English"
    lines = "\n".join(f"- {NETWORK_KIND_TEXT.get(k, k)}: \"{d}\"" for k, d in events) or "- none"
    call = "yes, a call with an unknown number is going on right now" if in_unknown_call else "no"
    return (
        f"Relevant scam patterns:\n{pb}\n"
        f"Network Watch findings on the user's phone:\n{lines}\n"
        f"During a call from an unknown number: {call}\n"
        f"Reply language for 'say': {language}\n"
        "This is NOT a call transcript. Explain simply what the app may be doing (stealing data, remote control) "
        "and what to do now (uninstall the app, turn off mobile data / Wi-Fi, call 1930)."
    )


def format_llama3(system: str, user: str) -> tuple[str, str]:
    """Return (prefix, suffix). prefix is constant -> prefill once and cache."""
    prefix = f"{BOT}{SH}system{EH}\n\n{system}{EOT}"
    suffix = f"{SH}user{EH}\n\n{user}{EOT}{SH}assistant{EH}\n\n"
    return prefix, suffix


@dataclass
class Verdict:
    scam: bool
    type: str
    stage: str
    confidence: float
    evidence: list[str] = field(default_factory=list)
    say: str = ""
    action: str = "none"
    source: str = "llm"  # llm | fallback

    def to_json(self) -> str:
        d = {k: getattr(self, k) for k in ("scam", "type", "stage", "confidence", "evidence", "say", "action")}
        return json.dumps(d, ensure_ascii=False)


def extract_json_object(text: str) -> str | None:
    start = text.find("{")
    while start >= 0:
        depth, in_str, esc = 0, False, False
        for i in range(start, len(text)):
            ch = text[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[start : i + 1]
        start = text.find("{", start + 1)
    return None


def validate(raw: str, transcript: str, type_ids: list[str]) -> tuple[Verdict | None, list[str]]:
    errors: list[str] = []
    js = extract_json_object(raw)
    if js is None:
        return None, ["no JSON object found"]
    try:
        d = json.loads(js)
    except json.JSONDecodeError as e:
        return None, [f"invalid JSON: {e.msg}"]
    if not isinstance(d, dict):
        return None, ["top level is not an object"]

    scam = d.get("scam")
    if isinstance(scam, str):
        low = scam.strip().lower()
        scam = True if low in ("true", "yes") else False if low in ("false", "no") else None
    if not isinstance(scam, bool):
        return None, ["'scam' must be true or false"]

    typ = str(d.get("type", "other")).strip()
    if typ not in type_ids and typ not in ("other", "none"):
        errors.append(f"unknown type '{typ}' -> other")
        typ = "other"
    stage = str(d.get("stage", "none")).strip()
    if stage not in STAGES:
        errors.append(f"unknown stage '{stage}' -> none")
        stage = "none"
    try:
        conf = float(d.get("confidence", 0.5))
    except (TypeError, ValueError):
        conf = 0.5
        errors.append("confidence not a number")
    conf = min(1.0, max(0.0, conf))

    norm_tr = " " + normalize(transcript) + " "
    evidence = []
    for q in d.get("evidence", []) or []:
        if not isinstance(q, str):
            continue
        nq = normalize(q)
        if nq and (" " + nq + " ") in norm_tr:
            evidence.append(q.strip())
        else:
            errors.append(f"dropped non-verbatim quote '{q[:40]}'")
    evidence = evidence[:3]
    if scam and not evidence and d.get("evidence"):
        conf = min(conf, 0.6)  # claimed evidence that is not in the transcript

    say = str(d.get("say", "")).strip()
    if say_copies_transcript(say, transcript):
        return None, ["'say' repeats the caller's words; write short advice TO THE USER instead"]
    words = say.split()
    if len(words) > MAX_SAY_WORDS:
        say = " ".join(words[:MAX_SAY_WORDS])
    action = str(d.get("action", "none")).strip()
    if action not in ACTIONS:
        errors.append(f"unknown action '{action}'")
        action = "hang_up" if scam else "none"
    if not scam:
        typ = "none" if typ == "other" else typ
    return Verdict(scam, typ, stage, round(conf, 3), evidence, say, action, "llm"), errors


def say_copies_transcript(say: str, transcript: str) -> bool:
    """True when the advice mostly repeats the call itself (seen on a phone: the model echoed the
    scammer's own instruction as 'advice'). >= 60% of its words, at least 4, come from the call."""
    words = normalize(say).split()
    if len(words) < 4:
        return False
    heard = set(normalize(transcript).split())
    return sum(1 for w in words if w in heard) / len(words) >= 0.6


def repair_prompt(errors: list[str]) -> str:
    return "Your previous answer was not valid: " + "; ".join(errors[:3]) + ". Reply again with ONE valid JSON object only."


def repair_suffix(suffix: str, previous: str, errors: list[str]) -> str:
    """Continue the chat: previous assistant turn, a user turn with the error, a fresh assistant header."""
    return f"{suffix}{previous.strip()}{EOT}{SH}user{EH}\n\n{repair_prompt(errors)}{EOT}{SH}assistant{EH}\n\n"


def fallback_verdict(level: str, active: dict[str, float], retrieved: list[dict], labels: dict[str, tuple[str, str]],
                     stages: list[str], lang: str, helpline: str = "1930") -> Verdict:
    """Deterministic verdict + explanation used when the LLM is unavailable (and spoken instantly at STOP)."""
    if level not in ("caution", "stop") or not retrieved:
        return Verdict(False, "none", "none", 0.2, [], "", "none", "fallback")
    top = retrieved[0]
    cats = [c for c, _ in sorted(active.items(), key=lambda x: -x[1]) if c in labels and c != "safety_advice"][:2]
    k = 0 if lang == "en" else 1
    signs = "; ".join(labels[c][k] for c in cats)
    if lang == "hi":
        head = "रुकिए! यह कॉल " + top["name_hi"] + " स्कैम जैसी लग रही है।" if level == "stop" else "सावधान! यह कॉल संदिग्ध है।"
        say = f"{head} {signs}. {top['truth_hi']} {top['action_hi']}"
    else:
        head = f"Stop! This call looks like a {top['name_en']} scam." if level == "stop" else "Caution: this call looks suspicious."
        say = f"{head} {signs}. {top['truth_en']} {top['action_en']}"
    action = "hang_up" if level == "stop" else "verify_independently"
    stage = stages[-1] if stages else "contact"
    return Verdict(True, top["id"], stage, 0.9 if level == "stop" else 0.6, [], say, action, "fallback")
