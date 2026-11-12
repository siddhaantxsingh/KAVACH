"""Does cross-channel fusion help? Replays the SAME timestamped event traces under five configurations.

Plan (written before the first run; nothing below is tuned on these scenarios; every detector keeps the
thresholds shipped in the app, which were tuned on the call dev split only):

  A   call detector alone            Guardian on the call transcript; no other channel
  B   message/link/app detectors     kill chain fed only non-call events (SMS, links, APKs, remote access,
      alone                          payment app), with no knowledge that a call is going on
  C   independent OR                 level(t) = max(level_A(t), level_B(t)); no temporal/entity correlation
  B+  call-aware Shield              B plus the phone-state "unknown call started/ended" events, no speech
                                     (the app with call listening switched off)
  D   full fusion, no Llama          as the app is wired: the call listener gets the forwarded action events and
                                     the call start/end; Shield's kill chain gets every event, the call start/end
                                     and the listener's CAUTION/STOP; the user sees the higher of the two
  E   D + Llama escalation           NOT RUN on the host: needs the deployed .pte (see docs/EVIDENCE_LEDGER.md)

Scenarios: each template takes calls from a split (default test_far: test calls with 3x ASR errors on the
caller's lines, i.e. the caller heard through a speaker) and attaches channel events at fixed relative times.
Scam templates add evidence that belongs to the same scam; benign templates are counterexamples where
temporal proximity alone must not prove a scam. SMS texts are short team-written examples (8 scam, 4
genuine), shared by all configurations.

  t_risky (scam sessions) = earliest of: the first spoken payment/OTP/remote-access request (money_t),
  15 s after a scam link arrives (time to open it), an APK install, remote access starting, a payment app opening.
  "warned before risk" counts CAUTION-or-STOP strictly before t_risky, over ALL scam sessions.

Usage: python bench/fusion_ablation.py [--split test_far] [--n 40] [--json bench/results/fusion_ablation.json]
       (use --split dev to investigate; report test_far)
"""
from __future__ import annotations

import argparse
import json
import random
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "bench"))
from eval_calls import FILES  # noqa: E402
from kavach_core.features import LinearModel  # noqa: E402
from kavach_core.guardian import Guardian  # noqa: E402
from kavach_core.killchain import CALL_END, CALL_START, CROSS_KINDS, LEVELS, PAYMENT_OPEN, Event, KillChain  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.message_shield import MessageShield  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402
from kavach_core.url_check import UrlChecker  # noqa: E402

SCAM_SMS = [
    "Dear Customer, your SBI KYC has expired. Update now at http://sbi-kyc-verify.top/login or your account will be blocked today.",
    "Your electricity connection will be disconnected tonight at 9.30 pm. Pay now at http://bescom-bill-update.xyz",
    "Your parcel is held at customs. Pay Rs 25 redelivery fee: http://indiapost-redelivery.in/pay",
    "Refund of Rs 4,999 approved. Install the support app to receive it: http://refund-help.xyz/support.apk",
    "Aapka SIM 2 ghante mein band ho jayega. KYC update karein: http://trai-sim-verify.online",
    "Congratulations! You won Rs 25,00,000 in KBC lucky draw. Claim: http://kbc-prize-claim.club",
    "HDFC: your netbanking is suspended. Verify PAN at http://hdfc-netbank-verify.co/pan",
    "Part time job: earn Rs 5000/day by liking videos. Join http://task-earn-daily.xyz",
]
GENUINE_SMS = [
    "Rs 1,250.00 debited from A/c XX4521 on 21-11-26 to VPA swiggy@icici. Not you? Call 18001234 - SBI",
    "Your OTP for login is 482913. Do not share it with anyone. -HDFC Bank",
    "Your Amazon order #402-77 will be delivered today between 4 PM and 7 PM.",
    "Dear customer, your electricity bill of Rs 1,240 is due on 28 Nov. Pay via the official app. Never share OTP.",
]
CONFIGS = ["A", "B", "C", "B+", "D"]


def scam_templates():
    """name -> fn(call, rng) -> (externals, t_risky, keep_segments_fraction)"""
    def dur(c):
        return c["segments"][-1]["t1"]

    def mid_sms_link(c, r):
        t = 0.5 * dur(c)
        return [("sms", t, r.choice(SCAM_SMS[:4]))], t + 15.0
    def apk_install(c, r):
        t = 0.6 * dur(c)
        return [("event", t, Event(t, "app", "risky_apk", 2.5, "sideloaded support app"))], t
    def remote_then_pay(c, r):
        t = 0.5 * dur(c)
        return [("event", t, Event(t, "screen", "remote_access_active", 2.0, "remote access")),
                ("event", t + 30, Event(t + 30, "app", PAYMENT_OPEN, 0.0, "upi app"))], t
    def sms_before_call(c, r):
        t = -300.0
        return [("sms", t, r.choice(SCAM_SMS))], None  # risk = the call's own request
    def early_talk_then_apk(c, r):
        t = 0.4 * dur(c) + 5
        return [("event", t, Event(t, "app", "risky_apk", 2.5, "apk"))], t
    return {"scam_call_alone": (lambda c, r: ([], None), 1.0),
            "scam+link_sms_mid_call": (mid_sms_link, 1.0),
            "scam+apk_install": (apk_install, 1.0),
            "scam+remote_access+payment_app": (remote_then_pay, 1.0),
            "scam_sms_5min_before_call": (sms_before_call, 1.0),
            "first40%_of_call+apk": (early_talk_then_apk, 0.4)}


def benign_templates():
    def dur(c):
        return c["segments"][-1]["t1"]
    return {
        "benign_call_alone": (lambda c, r: [], 1.0),
        "benign+unrelated_scam_sms_mid_call": (lambda c, r: [("sms", 0.5 * dur(c), r.choice(SCAM_SMS))], 1.0),
        "benign+scam_sms_20min_after_call": (lambda c, r: [("sms", dur(c) + 1200, r.choice(SCAM_SMS))], 1.0),
        "benign+payment_app_during_call": (lambda c, r: [("event", 0.6 * dur(c), Event(0.6 * dur(c), "app", PAYMENT_OPEN, 0.0, "upi"))], 1.0),
        "benign+stale_risky_apk_2_days_ago": (lambda c, r: [("event", -172800.0, Event(-172800.0, "app", "risky_apk", 2.5, "old apk"))], 1.0),
        "benign+genuine_sms_duplicated_reordered": (lambda c, r: [("sms", 0.5 * dur(c), GENUINE_SMS[0]), ("sms", 0.5 * dur(c) - 20, GENUINE_SMS[0]),
                                                                    ("sms", 0.3 * dur(c), r.choice(GENUINE_SMS[1:]))], 1.0),
        "benign+work_screen_share_no_payment": (lambda c, r: [("event", 0.4 * dur(c), Event(0.4 * dur(c), "screen", "remote_access_active", 2.0, "meeting share"))], 1.0),
    }


class Runner:
    def __init__(self):
        M = ROOT / "assets/models"
        self.lex = Lexicon()
        self.cue = LinearModel.load(M, "cue_model")
        self.pb = Playbook()
        self.ms = MessageShield(LinearModel.load(M, "sms_model"), self.lex, UrlChecker(LinearModel.load(M, "url_model")))
        self.stage_of = {n: c.stage for n, c in self.lex.categories.items()}

    def external_events(self, ext):
        evs = []
        for kind, t, x in ext:
            evs.extend(self.ms.check(x).events(t) if kind == "sms" else [x])
        return evs

    def run(self, segs, ext, end):
        """Return {config: (first_caution_t, first_stop_t, final_level, rule)}"""
        evs = sorted(self.external_events(ext), key=lambda e: e.t)
        call_evs = [Event(0.0, "phone", CALL_START, 0.0, ""), Event(end, "phone", CALL_END, 0.0, "")]
        last = max([end] + [e.t for e in evs]) + 6.0
        times = sorted({round(k * 3.0, 3) for k in range(1, int(last / 3.0) + 1)} | {round(s[0], 3) for s in segs}
                       | {round(e.t, 3) for e in evs if e.t >= 0})
        out = {}

        fwd = CROSS_KINDS | {CALL_START, CALL_END, PAYMENT_OPEN}

        def trace_guardian(with_ext: bool):
            """the call listener; in the app it receives only the forwarded kinds and the call start/end"""
            g = Guardian(self.lex, self.cue, self.pb)
            g.reset()
            pending = sorted(([e for e in evs if e.kind in fwd] + call_evs) if with_ext else [], key=lambda e: e.t)
            i = j = 0
            hist = []
            for t in times:
                if with_ext and t > end + 30.0:  # the app ends the listening session 30 s after the call ends
                    hist.append((t, hist[-1][1], hist[-1][2], "G")); continue
                while i < len(segs) and segs[i][0] <= t + 1e-9:
                    g.on_segment(segs[i][1], segs[i][0]); i += 1
                while j < len(pending) and pending[j].t <= t + 1e-9:
                    g.add_external(pending[j]); j += 1
                st = g.tick(t)
                hist.append((t, st.level, st.rule, "G"))
            return hist

        def trace_kc(with_call: bool, guardian_hist=None):
            """Shield's kill chain (ShieldHub); with guardian_hist it also gets the Guardian's CAUTION/STOP as scam_call"""
            kc = KillChain(self.stage_of)
            pending = sorted(evs + (call_evs if with_call else []), key=lambda e: e.t)
            j = 0
            hist = []
            fed = set()
            for k, t in enumerate(times):
                while j < len(pending) and pending[j].t <= t + 1e-9:
                    kc.add(pending[j]); j += 1
                if guardian_hist is not None and k > 0:
                    lv = guardian_hist[k - 1][1]  # the Guardian's level reaches Shield one tick later
                    if lv in ("caution", "stop") and lv not in fed:
                        fed.add(lv); kc.add(Event(t, "call", "scam_call", 2.0, "guardian:" + lv))
                a = kc.assess(t)
                hist.append((t, a.level, a.rule, "S"))
            return hist

        def mx(h1, h2):
            return [(x if LEVELS.index(x[1]) >= LEVELS.index(y[1]) else y) for x, y in zip(h1, h2)]

        A = trace_guardian(False)
        G = trace_guardian(True)
        H = {"A": A, "B": trace_kc(False), "B+": trace_kc(True)}
        H["C"] = mx(A, H["B"])
        H["D"] = mx(G, trace_kc(True, G))
        for k, h in H.items():
            fc = next((x[0] for x in h if LEVELS.index(x[1]) >= 2), None)
            fs = next((x[0] for x in h if x[1] == "stop"), None)
            out[k] = (fc, fs, h[-1][1], h[-1][2], h[-1][3])
        return out


CALL_RULES = ("call+action", "unknown-call", "cross-channel:", "long-unknown-call")


CONTENT_RULES = ("isolation+authority", "authority+identity+threat", "multi-action", "hook+action", "victim-complying", "classifier", "score")


def call_linked(rule: str, src: str) -> bool:
    """True when the STOP points at the call: a rule that ties an action to the call, or the listener's own
    content-based STOP. A STOP from two message channels (cross-channel-multi) blames the message, not the call."""
    return rule.startswith(CALL_RULES) or (src == "G" and rule.split("(")[0] in CONTENT_RULES)


def summarize(rows, scam: bool):
    res = {}
    for cfg in CONFIGS:
        r = [x[cfg] for x in rows]
        n = len(r)
        d = {"n": n, "stop": sum(x["level"] == "stop" for x in r), "caution_or_stop": sum(x["level"] in ("caution", "stop") for x in r)}
        if scam:
            d["warned_before_risk"] = sum(x["warned_before_risk"] for x in r)
            d["stop_before_risk"] = sum(x["stop_before_risk"] for x in r)
            leads = [x["lead_s"] for x in r if x["lead_s"] is not None and x["lead_s"] > 0]
            d["median_lead_s"] = round(statistics.median(leads), 1) if leads else None
        else:
            d["call_blamed_stop"] = sum(x["level"] == "stop" and x["call_blamed"] for x in r)
        rules = {}
        for x in r:
            if x["level"] in ("caution", "stop"):
                rules[x["rule"].split(":")[0]] = rules.get(x["rule"].split(":")[0], 0) + 1
        d["rules"] = rules
        res[cfg] = d
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="test_far")
    ap.add_argument("--n", type=int, default=40)
    ap.add_argument("--seed", type=int, default=2611)
    ap.add_argument("--json", default="bench/results/fusion_ablation.json")
    a = ap.parse_args()
    calls = [json.loads(l) for l in open(FILES[a.split], encoding="utf-8")]
    scams = [c for c in calls if c["label"] == 1]
    bens = [c for c in calls if c["label"] == 0]
    R = Runner()
    report = {"split": a.split, "n_per_template": a.n, "seed": a.seed, "configs": CONFIGS, "scam": {}, "benign": {}, "sessions": []}
    for group, templates, pool in (("scam", scam_templates(), scams), ("benign", benign_templates(), bens)):
        for name, (fn, frac) in templates.items():
            rng = random.Random(f"{a.seed}-{name}")
            picked = rng.sample(pool, min(a.n, len(pool)))
            rows = []
            for c in picked:
                segs_all = [(s["t1"], s["text"]) for s in c["segments"]]
                segs = segs_all[: max(1, int(round(len(segs_all) * frac)))]
                ext_r = fn(c, rng)
                ext, t_act = ext_r if isinstance(ext_r, tuple) else (ext_r, None)
                end = segs[-1][0]
                money = c.get("money_t") if frac == 1.0 else (c.get("money_t") if c.get("money_t") and c["money_t"] <= end else None)
                risk = min([x for x in (money, t_act) if x is not None], default=None)
                out = R.run(segs, ext, end)
                row = {"call": c["id"], "template": name}
                for cfg, (fc, fs, lvl, rule, src) in out.items():
                    row[cfg] = {"level": lvl, "rule": rule, "source": src, "first_warning_s": fc, "first_stop_s": fs,
                                "warned_before_risk": fc is not None and risk is not None and fc < risk,
                                "stop_before_risk": fs is not None and risk is not None and fs < risk,
                                "lead_s": (risk - fc) if (fc is not None and risk is not None) else None,
                                "call_blamed": call_linked(rule, src)}
                row["t_risky"] = risk
                rows.append(row)
                report["sessions"].append(row)
            report[group][name] = summarize(rows, group == "scam")
    Path(a.json).parent.mkdir(parents=True, exist_ok=True)
    json.dump(report, open(a.json, "w"), indent=1)
    for group in ("scam", "benign"):
        print(f"\n== {group} sessions ({a.split}, n={a.n} per template) ==")
        for name, res in report[group].items():
            cells = []
            for cfg in CONFIGS:
                d = res[cfg]
                if group == "scam":
                    cells.append(f"{cfg}: STOP {d['stop']}/{d['n']} warn<risk {d['warned_before_risk']}")
                else:
                    cells.append(f"{cfg}: STOP {d['stop']} (call {d['call_blamed_stop']}) CAUT+ {d['caution_or_stop']}")
            print(f"{name:42s} | " + " | ".join(cells))
    print(f"\nwrote {a.json}")


if __name__ == "__main__":
    main()
