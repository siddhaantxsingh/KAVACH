# KAVACH audit, compliance matrix and owner actions

Written 10 November 2026, from the source repository, CI builds and the owner's first on-device test
(one Xiaomi phone, both apps installed; Guardian demo replay reached STOP and the spoken warning played).

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

The official page could not be opened from the build machine on 10 Nov (blocked request). The items
below come from the owner's check of the page on 10 Nov 2026 and **must be re-verified** before submission.

| Requirement (as reported) | KAVACH implementation | Evidence | Status |
|---|---|---|---|
| Phase 1 submission due 23 Nov 2026; PoC due 5 Feb 2027 | technical report, repo, APKs, demo video | `docs/KAVACH_Phase1_Technical_Report.pdf`, GitHub release `apks-latest` | report and APKs ready; video not recorded |
| A Llama-family model for advisory/reasoning, on-device, with ExecuTorch | `LlamaAdvisor` (ExecuTorch `LlmModule`) explains and may escalate CAUTION→STOP; JSON contract + validator; the fast detector never waits for it | code in `android/shared`; ExecuTorch native libs packaged in both APKs (CI); host contract tests | **verified on a phone (10 Nov 2026, owner's Xiaomi): Meta's Llama 3.2 1B SpinQuant INT4 .pte loaded with ExecuTorch 1.4 and generated on a sample call: 196 tokens, first token 13.4 s (cold prompt, no warm prefix), 11.7 tokens/s decode. Answer quality was poor (it echoed the scammer's sentence, confidence 0); the validator now rejects such answers and the prompt has a worked example. Re-test pending.** |
| PS3: scam communications (calls, SMS/chat) | Guardian (speech → cues → classifier → kill chain), Message Shield | host evaluation: synthetic test STOP 90.6%, hand-written 93.8%, held-out 70%, 0 false STOPs; demo replay on the owner's phone | host-verified; demo replay verified; live speech on the phone not yet tested |
| PS3: phishing | Link Check: allow-list, look-alike rules, URL model | host evaluation (`bench/results/url_model.json`) | host-verified |
| PS3: local network-threat analysis | Network Watch: per-app traffic rules (no INTERNET), optional DNS-watch VPN app, flow model (research only) | simulated traces; Scapy pcap replay; UNSW-NB15/NSL-KDD numbers | simulated + host-verified; **no phone traffic tested**; flow model does not transfer to phone HTTPS (documented) |
| PS3: plain-language explanation and remediation | rule reasons in Hindi/English, spoken warnings, STOP card (1930), Llama explanation | spoken warning heard on the phone | partial: rule reasons and voice verified; Llama explanation untested |

## 2. Capability matrix (Android reality)

| Capability | What the code does | Limits, stated plainly | Status |
|---|---|---|---|
| Hearing the call | Guardian listens to the call on **speaker** through its own microphone | normal apps cannot capture call audio (Android 10+); the dialer role does **not** grant call audio; only an OEM privileged app can (`docs/oem_mode.md`) | speaker path present, untested live; OEM mode blocked |
| Reading messages | notification listener | sees only what the notification shows; no SMS history, no hidden or collapsed content | present, untested |
| Unknown-call awareness | phone-state broadcast + contacts lookup | knows *that* a call is on, never the audio; number needs READ_CALL_LOG | present, untested |
| Screen-share / remote-access | usage-stats: remote-access app in the foreground + payment app | an installed remote-access app is not proof of active sharing; we only see which app is in front | present, untested |
| App risk | permissions, installer, lure names | permissions indicate risk, not malware | present, untested |
| Network (Part A) | per-app byte counters | byte counts only; counter freshness depends on the phone's Android build | simulated |
| Network (Part B) | DNS-only local VPN, forwards lookups unchanged | IPv4 DNS only; bypassed by DNS-over-HTTPS and "Private DNS: strict"; conflicts with other VPNs; needs INTERNET (separate opt-in app) | present, untested |
| Payment blocking | none | KAVACH warns; it **cannot** block a bank payment and never claims to | n/a |

## 3. Defects and risks, by impact

1. **Llama runs on the phone but its first answer was poor** (it repeated the caller's words). Fixed in the validator and prompt; needs a re-test. Its first token took 13.4 s on a cold prompt, so its explanation always follows the instant rule-based warning and never delays it.
2. **Fake "protected" states in earlier builds.** Fixed in this build: Guardian says "Not listening" when idle and labels demo replays "a scripted call, not a live call"; Shield shows "Setup needed" or "Protection is off" unless the service is really running; link results never say "safe" (only "no obvious warning signs; not a guarantee", or "matches the official domain").
3. **Public demo signing key.** `android/kavach-shared-debug.keystore` is in the public repo, so any app signed with it can join the same-phone link (signature permission). Fine for a demo; a real release needs a private key kept out of the repo, and an authenticated pairing step (open).
4. **Bluetooth link has no app-level pairing verification** beyond Android bonding, and no replay protection (open).
5. **No consented human recordings.** Every accuracy number is synthetic, hand-written or TTS (open; needs the team).
6. **No phone benchmarks** for ASR speed, Llama tokens/s, memory, battery or heat (open; needs the phone).
7. **Light mode, TalkBack and large text untested on a phone.** Contrast is verified (`docs/design_tokens.md`, 0 failures); rows switch to a stacked layout at large text sizes.
8. **Missing product pieces** from the master prompt: an onboarding flow; an Activity timeline with dismiss, delete and redacted export; a Settings screen; model packs with checksums and atomic activation. These are **absent** and planned for Gate C/D (see §5).

## 4. What changed in this build (design and truthfulness)

- "Midnight, Ivory and Gold" design system: dark and light themes follow the phone's setting; cobalt for actions; gold only for identity; amber and red only for status.
  - No teal or cyan anywhere: checked by scanning every colour in the code and icons.
  - No gradients behind text; no emoji in status text; status shown in words, never colour alone.
  - Sentence-case labels; buttons at least 56 dp tall; text in sp so it scales with the phone's text size; rows stack at large text sizes.
  - Status changes are announced by TalkBack (live region); section titles are marked as headings.
- Adaptive launcher icons: a gold shield on midnight. The Guardian adds sound arcs; Network Watch uses an outline shield.
- Guardian Readiness card, all in words:
  - microphone permission;
  - Hindi and English speech models on the phone or missing;
  - Llama: missing / loading / failed / loaded but not run / **ran on this phone** with its measured speed;
  - link to Shield.
- Technical numbers are moved behind "Technical details".

## 5. Plan to 23 November (three people, two weeks)

| Gate | What | Who |
|---|---|---|
| B (by 14 Nov) | Llama .pte on the phone; Readiness says "ran on this phone"; screenshot + log for the report. Live English and Hindi speech tests with the TTS files | owner |
| B | 10–20 consented role-play calls (scam and genuine, Hindi/Hinglish/English) recorded under `demo/recording_protocol.md`; evaluated with `bench/eval_recorded.py` | team |
| C (by 18 Nov) | phone benchmarks from the Technical details screen (ASR speed, Llama tokens/s, memory, temperature) into the report | owner + Claude |
| D (by 21 Nov) | report v2 with the measured phone numbers, honest limits, and this matrix | Claude |
| E (22–23 Nov) | demo video and submission, with a day of buffer | team |

Out of scope before Phase 1: Pi hub, NPU offload, Llama fine-tuning, payment interception, and the full Home/Check/Activity/Settings rebuild (Gate C/D for the PoC).

## 6. Owner actions (need your phone)

1. Copy Llama to the phone: `scripts/push_models.sh --official-spinquant` (about 1.1 GB). Open the Guardian, tap a language, and let a test call play. In Readiness, the Llama line should change to "ran on this phone: … tokens/s". If it says "failed to load", send a screenshot plus `adb logcat -d | grep -i -E "executorch|llama" > llama.txt`; the downloaded file may be from an older ExecuTorch version.
2. Live English test: tap **English** and play `test_call_digital_arrest_en.wav`. Expected: STOP. Then `test_call_genuine_bank_en.wav`. Expected: no STOP.
3. Live Hindi test: tap **हिंदी / Hinglish** and play `test_call_digital_arrest_hi.wav`. This is the first real test of the Hindi model.
4. Light mode: turn on the phone's light theme and check both apps.
5. TalkBack: turn it on, then walk through Shield setup and a Guardian demo call.

## 7. Candid self-review (no scores)

- **Detection evidence:** good on synthetic and hand-written data with 0 false STOPs; weak where it matters most, because there are no real human recordings yet.
- **Llama requirement:** the code path is complete, but it is **not yet observed on a phone**. This is the main compliance risk.
- **Arm optimisation:** the export recipe and threading are in place; no phone measurements yet.
- **Network analysis:** honest and well documented, but simulated. Phone-traffic validation is missing, and the flow model is research only.
- **Privacy:** strong by design (no INTERNET in the main apps; audio never stored). Weakened by the public demo key and the unauthenticated Bluetooth pairing.
- **Usability:** much improved and truthful now, but not tested with real older users; onboarding and the activity timeline are missing.
- **Demo readiness:** the demo replay works on a real phone; the live path is still to be confirmed.
