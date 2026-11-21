# Frozen-build live-call results: evidence note

Source: team-supplied test-results addendum, observations dated 21 November 2026. On 22 November the team confirmed that the functional and reliability checks used **live calls from a second phone**. This resolves the original document’s ambiguous reading/playback wording. This note supports the [single report](KAVACH_Phase1_Report.pdf); it is not another submission report.

## Build

Version `0.2-phase1` (versionCode 2), source `ed541cede0d0567d8627fbd8841b13f742d77d38`, installed APK SHA-256 `fd2e7069bb7907bd8edc0a81a823b0dcf888d7f3f318e5d73592f0bb15af25a6`. The team reports no reinstall/change after freeze and a byte-identical APK pulled back from the phone. Device/model hashes: [freeze manifest](FREEZE_MANIFEST.md).

## Live-call functional and reliability observations

| Check | Reported outcome |
|---|---|
| English/Hindi OTP plus instruction to call the conversation safe | INFO: “Listening — worth watching”; no STOP |
| Full English fake-KYC pattern | Red STOP: Fake KYC / bank account block; Activity: Urgent, Guardian: stop |
| Genuine bank lines and genuine scripts | No warning |
| Llama after warning | Explanation appeared; rule-based STOP correctly named fake KYC |
| Accessibility / microphone permission off | Guidance to restore permission/service |
| Screen lock / Home pressed | Listening continued |
| Held call with second incoming call | Worked |
| Consecutive calls | Both heard |
| ASR / noise hints | Some English words appeared in Hindi script or were misrecognised; Too noisy sometimes appeared in a quiet room |

No exact trial count, timing trace or per-call validated Llama JSON was supplied. INFO alone does not call the advisor, so the injection observations do not establish model injection resistance. Missing-model fallback and dual-SIM were explicitly not tested.

## Separate battery workload

This run used an English podcast and about 15 minutes of Hindi dub, with continued Auto transcription reported. Phone unplugged, wireless adb, Wi-Fi on, screen about 50%, room about 30 °C; four taps kept the screen awake. Logged listening battery: 99% to 92% over about 30 minutes; 35.0 to 37.1 °C, maximum 37.3 °C. The later 91% reading is outside the logged endpoint. Idle: 100% to 99% over about 10 minutes; 36.9 to 36.1 °C. Current was blank. Thermal status 0 does not directly establish absence of CPU throttling.

These are whole-phone gauge observations, not app-only consumption, normal-use estimates or accuracy measurements. The CSVs are the authoritative logged values; setup details are team-reported.

## Remaining Phase 1 scope

Four live human calls plus an optional fifth and the demo video. Those planned calls have no outcomes yet. The 20-call protocol and further experiments are deferred to Phase 2. Historical synthetic/TTS, acoustic-playback and model self-test results retain their actual methods.
