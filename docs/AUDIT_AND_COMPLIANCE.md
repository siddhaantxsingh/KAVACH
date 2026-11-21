# Phase 1 submission and evidence audit

Updated 22 November 2026. The only submission report is [KAVACH_Phase1_Report.pdf](KAVACH_Phase1_Report.pdf), with an editable [Word version](KAVACH_Phase1_Report.docx). Benchmark methods and limitations are in the [evidence ledger](EVIDENCE_LEDGER.md).

## Submission mapping

| Item | Evidence / current status |
|---|---|
| Source code | Public KAVACH repository |
| Application | Frozen `0.2-phase1` APK and model hashes in [manifest](FREEZE_MANIFEST.md); check mutable release against those hashes |
| On-device Llama / ExecuTorch | LoRA export, device screenshots and separate self-test; stock SpinQuant performance is not attributed to the LoRA file |
| Report | Single long-format PDF / editable Word pair |
| Live-call evidence | Three documented 12 November calls; team-confirmed 21 November functional/reliability live-call checks with unspecified trial count |
| Final recorded set | Four calls and optional fifth pending in `demo/live_calls_phase1.csv` |
| Demo video | Pending; [current instructions](FINISH_ROUND1.md) |

Official challenge: https://arm-education.github.io/Arm-Developer-Labs/Bharat_AI_SoC_2026_27.html . Check the actual submission form for current field, size and duration constraints. A specific call count is the team’s evaluation choice, not a verified official requirement.

## Evidence boundaries

- Live calls, earlier laptop-speaker playback, synthetic text, TTS/channel experiments and device benchmarks are distinct methods. The team’s live-call clarification applies to its addendum’s functional/reliability checks.
- The 93.4% detector score is synthetic text without ASR/Llama. The 38% to 89% LoRA comparison is full-precision synthetic development evaluation. Neither is live-call accuracy.
- INFO (“worth watching”) does not invoke Llama; the injection observations do not show model robustness.
- The 30-minute podcast workload is logged; current is unavailable and no matched app-only energy result or <10%/hour claim is established.
- Three earlier observed calls and uncounted addendum checks are not combined with pending calls into an invented success rate.
- Fusion has observed false attribution in synthetic stress tests; speakerphone/accessibility dependence and one-device coverage remain limitations.
- Missing-model fallback and dual-SIM remain untested. Larger studies and optimisation ablations are Phase 2 work.

## Completion gate

Follow [FINISH_ROUND1.md](FINISH_ROUND1.md): finish the four/five calls, record the video, incorporate actual results into the same report, verify access to the deliverables, submit and retain the receipt. Do not reinstall or alter the frozen build for documentation changes.
