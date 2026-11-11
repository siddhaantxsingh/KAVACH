# KAVACH audit, compliance matrix and owner actions

Written 10 November 2026; **updated 12 November 2026** after the night of phone tests (owner's Xiaomi
2312DRA50I, Snapdragon 7s Gen 2, Android 15).

**Statuses:**
- `verified`: observed working, with the evidence named.
- `host-verified`: measured on a laptop or in CI, not yet on a phone.
- `present, untested`: the code is in the APK but has not been run on a phone.
- `partial`: some of it is missing.
- `simulated`: tested only with synthetic data.
- `absent`: not built.
- `blocked`: needs something we don't have.

Nothing here is a claim about real victims: all call data is synthetic, hand-written, or TTS audio.

## 1. Challenge requirements

The items below come from the owner's check of the challenge page on 10 Nov 2026 and **must be
re-verified** on the official page before submission.

| Requirement (as reported) | KAVACH implementation | Evidence | Status |
|---|---|---|---|
| Phase 1 submission due 23 Nov 2026 | technical report, repo, APKs, demo video | `docs/KAVACH_Phase1_Technical_Report.pdf`, release `apks-latest` | report and APKs ready; video and 10–20 consented recordings planned for 21 Nov |
| A Llama-family model, on-device, with ExecuTorch | Llama 3.2 1B + KAVACH LoRA, 4-bit `.pte` (820 MB), ExecuTorch 1.4 on the Arm CPU; explains alerts, may raise CAUTION to STOP, never delays the rule warning | report §7.5 and §7.9; `bench/results/llama_lora_colab.json`, `bench/results/device/kavach_bench_20261112_013528.json` | **verified on the phone**: own advice in Hindi and English, 11.5–11.9 tokens/s, first token 5.9 s warm; LoRA: valid JSON 71→100 %, scam 38→89 %, copied advice 32→0 % |
| PS3: scam calls | one-phone call listening (accessibility audio exception), IndicConformer Hindi + Parakeet English, Auto language, cues + classifier + kill chain | report §7.5 | **verified on real calls** (unknown number, cellular, speaker): Hindi scam STOP, English scam STOP, genuine bank call no warning (3/3) |
| PS3: SMS / chat scams, phishing | Message Shield, Link Check | host evaluation | host-verified; on-phone paste check works |
| PS3: local network threats | Network Watch: per-app traffic, DNS watch (separate app), Wi-Fi device check | simulated traces, pcap replay | simulated + host-verified; no phone traffic test |
| PS3: plain-language explanation and remediation | rule reasons, Stop card with 1930, Llama advice in the call's language | real-call screenshots | verified |
| Arm optimisation and measurement | int8 speech models, 4-bit Llama (XNNPACK/KleidiAI), thread counts measured | on-phone benchmark | **measured**: Auto listening 54 % of one core, ≈ 7.6 % battery per hour, no heating |

## 2. Capability matrix (Android reality)

| Capability | What the code does | Limits, stated plainly | Status |
|---|---|---|---|
| Hearing the call | listens on the same phone to a call on **speaker**, using Android's accessibility audio exception | the user taps Speaker (normal phone app); if Android stops the service (app update, revoked permission) every call is silenced until it is switched off and on: the app now detects this and says so | **verified on real calls** |
| Language | Auto: both sentence models on every sentence, LangPick keeps the matching one | 92.7 % per-sentence on held-out TTS sentences; short sentences can go to the wrong model | verified on the phone |
| Reading messages | notification listener | sees only what the notification shows | present |
| Unknown-call awareness | phone-state broadcast + contacts lookup | knows *that* a call is on; needs READ_PHONE_STATE / READ_CONTACTS | verified (starts listening by itself) |
| Screen-share / remote-access, app risk | usage stats, permissions, installer | indicators, not proof | present, untested on a phone |
| Network | per-app counters; optional DNS-watch VPN app; Wi-Fi port check | DNS-over-HTTPS bypasses the DNS watch | partly tested |
| Payment blocking | none | KAVACH warns; it cannot block a bank payment and never claims to | n/a |

## 3. Open risks, by impact

1. **No human recordings yet.** Every accuracy number except the three real calls uses synthetic text or TTS voices. Planned: 10–20 consented role-play calls on 21 Nov (`demo/recording_protocol.md`).
2. **Android can silently stop call listening** (seen once tonight after the default phone app changed). Detected and explained in the app since build `1e2be84`; users still have to fix it by hand.
3. **Llama's Hindi is understandable but not always polished**, and one Hindi run was still replaced by the playbook text. Replacements are now logged with Llama's own words.
4. **Public demo signing key** in the repo and **no app-level Bluetooth pairing check**: fine for a demo, not for release.
5. **Light mode, TalkBack, large text** not tested on the phone.
6. **Network Watch** is tested on simulated traffic only.

## 4. Fixed during the phone tests (11–12 Nov)

- Hindi recognition: AI4Bharat IndicConformer (telephone band red 0 % → 58 %, any warning 46 % → 86 %); genuine calls not red.
- English recognition: NVIDIA Parakeet 110M; Auto language for mixed calls.
- Yellow warnings no longer spoken, so listening never pauses before the decision.
- Call-listening service stopped by Android: detected on Home, Calls and in a notification.
- Llama: LoRA fine-tuned; copied example advice and English advice in Hindi calls fixed; advice never ends mid-sentence.
- Stop screen and notification show the same scam; the warning reason no longer reads "Suspicious call + a suspicious call".

## 5. Plan to 23 November

| When | What | Who |
|---|---|---|
| 21 Nov | 10–20 consented role-play recordings (scam and genuine, Hindi/Hinglish/English), evaluated with `bench/eval_recorded.py`; numbers into the report | team + Claude |
| 21 Nov | demo video (`demo/demo_video_script.md`) | team |
| 21–22 Nov | final check of repo, report and links; submission | team + Claude |
| 23 Nov | deadline (buffer day) | — |

Checklist for that day: `demo/final_day_checklist.md`.

## 6. Candid self-review (no scores)

- **Strongest:** the whole product works on one ordinary phone with no internet: real calls in Hindi and English reach STOP during the call, a genuine call stays calm, and every number in the report is measured.
- **Weakest:** no human-voice test set yet; three real calls is a demonstration, not a statistic.
- **Llama requirement:** met and measured on the phone, with a clear before/after from fine-tuning.
- **Arm optimisation:** measured (speed, battery, heat); a CPU-vs-NPU comparison is Phase 2.
- **Privacy:** strong by design (no INTERNET permission in the main app; audio never stored); weakened only by the demo key and Bluetooth pairing.
