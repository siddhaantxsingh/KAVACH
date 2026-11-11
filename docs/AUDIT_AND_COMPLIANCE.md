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
| A Llama-family model for advisory/reasoning, on-device, with ExecuTorch | `LlamaAdvisor` (ExecuTorch `LlmModule`) explains and may escalate CAUTION→STOP; JSON contract + validator; the fast detector never waits for it | code in `android/shared`; ExecuTorch native libs packaged in both APKs (CI); host contract tests | **verified on a phone (10 Nov 2026, owner's Xiaomi): Meta's Llama 3.2 1B SpinQuant INT4 .pte loaded with ExecuTorch 1.4 and generated on a sample call: 196 tokens, first token 13.4 s (cold prompt, no warm prefix), 11.7 tokens/s decode. Answer quality was poor (it echoed the scammer's sentence, confidence 0); the validator now rejects such answers and the prompt has a worked example. Live test (11 Nov, 00:08): TTS scam call from a laptop speaker → phone microphone → English ASR → STOP instantly → Llama explanation on the phone, first token 7.2 s (warm prefix), 10.1 tokens/s, 106 tokens. Caveat: the advice text was identical to the worked example in the prompt (correct but not call-specific); LoRA fine-tuning on our SFT data is the Phase 2 fix.** |
| PS3: scam communications (calls, SMS/chat) | Guardian (speech → cues → classifier → kill chain), Message Shield | host evaluation: synthetic test STOP 90.6%, hand-written 93.8%, held-out 70%, 0 false STOPs; on the owner's phone: demo replay STOP, live English TTS scam call through a laptop speaker STOP (placement 13 dB) | verified for one live English scam call; genuine-call, Hindi and human-voice tests pending |
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


## UI redesign (11 Nov)

Reviewed on real screenshots: the CI job `screens` runs both apps on an Android 14 emulator and saves
every main screen in dark and light mode to the release `ui-screens` (`scripts/ci_screens.sh`).

- Shield: bottom tabs (Home, Check, Activity, Settings); status dial; setup steps with progress;
  real on-phone counters (messages checked, links checked, warnings); alert cards with icon, level in
  words and time; a "clear the warning" button.
- Guardian: two tabs (Listen, Setup); status dial that pulses while listening; one Start/Stop button;
  explanation as headline + body with an "on this phone" tag; transcript as bubbles; readiness rows.
- Typeface Inter (SIL OFL, bundled); Lucide line icons (ISC). Contrast table: `docs/design_tokens.md`, 0 failures.
- Wording fixes found in the review: a Stop caused by a message or link no longer says "this call" or
  names a scam type Shield cannot know; fallback explanation grammar; scam-type matching now uses
  telltale words (the KYC demo call was being explained as an electricity scam).
- Not restyled: the optional Network Watch app (it does not use the shared UI module).
- Not checked on screenshots: TalkBack, very large font sizes, the in-call screen (needs a real call).


## Phone test, 11 Nov (six synthetic calls through a laptop speaker)

| Call | Expected | Result on the phone |
|---|---|---|
| genuine bank (Hindi) | not red | not red (after the "OTP kisi ko mat batana" fix; it was red before) |
| genuine bank (English) | not red | not red |
| delivery (English) | not red | not red |
| digital arrest (Hindi) | red | **no warning** (under investigation; Hindi speech model cannot be run on the build machine) |
| digital arrest (English) | red | yellow |
| KYC bank (English) | red | yellow |

Cause of the two yellows (reproduced on the build machine with the same English speech model):
the yellow warning was read aloud for 19–33 s, and the microphone is muted while KAVACH speaks, so
the caller's decisive sentences ("tell me the OTP", "transfer to the safe account") were never heard.
Fixes: the spoken yellow warning is one short line (~6 s) and the spoken red warning is headline +
action (~11 s); the full explanation stays on screen. Spelled-out letters from the speech recogniser
("o t p", "s b i k y c") are now joined into words ("otp", "sbi kyc") in Python and Kotlin.
Evaluation sets (dev, test, hand-written, held-out): no change in detection or false alarms.

Hindi digital-arrest call (no warning on the phone): the Hindi streaming model wrote the call as
mixed Hindi and broken English ("arestaurant" for arrest warrant, "मनिलौंd rink" for money laundering,
"inspactor", "आधार card", "parcel book", "trucks" for drugs). The word list matched none of it; only
the classifier reached caution. 56 hand-written Hinglish speech-model forms were added to five
categories (police, identity misuse, threat, isolation, payment). Checks: none fires on ~30,000
lines of benign synthetic calls and SMS; dev/test/hand-written/held-out results unchanged; the
phone's transcript of this call now reaches STOP on the build machine. Caveat: the forms were
chosen after seeing this one transcript, so this call is no longer an independent test; the next
Hindi check must use new recordings.

Llama on the same call copied its example answer word for word and answered in English during a
Hindi call. Its verdict (scam type, evidence) is still used, but such wording is now replaced by the
playbook explanation for the call, and the screen says "Checked by Llama 3.2 1B on this phone ·
advice from KAVACH's playbook" instead of "Explained by Llama". Proper fix: LoRA fine-tuning (Phase 2).


## Hindi through the speech model: measured for the first time (11 Nov)

The Hindi streaming speech model is now mirrored to the build machine (release `dev-models`), so
Hindi audio can be replayed on the host. 100 unseen synthetic Hindi test calls (50 scam, 50 genuine)
were spoken by Piper TTS, passed through a channel and decoded by the same Hindi model the phone uses.

| Condition | Build | Red on scam calls | Yellow or red | Red on genuine calls |
|---|---|---|---|---|
| near speaker, quiet room | before | 24% | 58% | 0% |
| near speaker, quiet room | now | 44% | 88% | 0% |
| telephone band, SNR 10-30 dB | before | 0% | 12% | 0% |
| telephone band, SNR 10-30 dB | now | 0% | 44% | 0% |

What changed: 208 mixed-script word-list variants (`tools/expand_loanwords.py`; the model writes
"bank", "otp", "card" in Latin letters inside Hindi); the call classifier retrained with 410 Hindi
calls as the speech model hears them (two channels); "tell no one" is treated as safety advice when
the sentence is about an OTP/PIN; the `isolation+authority` rule can be vetoed by a confidently
benign classifier; a copied Llama answer can no longer raise yellow to red.
Text-only sets after the change: dev, test, hand-written and held-out detection equal or better,
0 false STOPs. The owner's two Hindi test files, replayed 12 times each in varied near conditions:
scam call red 12/12; genuine bank call calm 10/12, yellow 2/12, red 0/12.

Honest limits: (1) the Hindi speech model is the bottleneck: on a telephone-band noisy call its text
is mostly unusable, and no word list can repair that; (2) training and test audio use the same TTS
voice, so real human voices will score lower; (3) a stronger Hindi model (e.g. a Hindi fine-tuned
Whisper-small run sentence by sentence) is the real fix and is not integrated yet.


## One app, one phone (11 Nov)

- Shield and the call listener are one app (`io.kavach.guardian`, label KAVACH); the Shield module
  is a library inside it. Network Watch stays a separate optional app by the owner's choice, so the
  main app keeps no INTERNET permission.
- One-phone call listening: `CallListenAccessService` (accessibility). Android documents that
  during a voice call only an accessibility service may capture audio besides the call.
  **Not yet verified on a real call** — the owner must test one speaker-phone call on the Xiaomi.
- No spoken warning during a real call (vibration + full-screen STOP card instead).
- One shared Llama instance (the former Shield copy would have doubled memory).
- New: on-phone benchmark screen (`docs/benchmarks.md`), Network Watch redesign with Apps tab and a
  home Wi-Fi check (risky open services), demo video script (`demo/demo_video_script.md`).
- Emulator screenshots (release `ui-screens`): every screen in dark, light and Hindi; no crash in
  the error log.
- Risk: Play-store policy limits accessibility services; acceptable for a side-loaded prototype,
  documented in the report §8.


## Hindi: AI4Bharat IndicConformer (11 Nov)

Same 100 unseen synthetic Hindi calls, same audio: telephone-band red 0 % → 58 %, yellow-or-red
46 % → 86 %; near-speaker red 44 % → 66 %, yellow-or-red 92 % → 96 %; genuine calls never red, 6 %
yellow (3 bank/police genuine calls). Classifier retrained with 320 calls transcribed by the new
model; text-only sets equal or better, 0 false STOPs. Owner action: `scripts/push_models.sh --asr-hi-big`,
then re-test the Hindi files. Caveat: synthetic single-voice audio; human recordings still needed.
