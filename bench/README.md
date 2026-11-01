# Benchmarks and evaluation

## Host (runs anywhere, numbers in `results/`)
| Script | What it measures | Output |
|---|---|---|
| `eval_calls.py --split {dev,test,handwritten,heldout}` | call-level STOP/CAUTION recall, caught-before-money, time-to-STOP, false STOP/CAUTION, by type and language; rules-only vs shipped | `results/calls_*.json` |
| `tune_guardian.py` | threshold grid on dev + hand-written only | `results/guardian_tuning.json` |
| `e2e_audio.py` | TTS call → speaker-phone channel → streaming ASR → engine: WER, RTF, levels | `results/e2e_audio_en.json` |
| `eval_recorded.py` | same as above on human-recorded WAVs (`demo/recording_protocol.md`) | `results/recorded_*.json` |
| Kotlin `EngineBench` (android/core tests) | per-tick cost of the shipped fast path, message and URL check | `results/kotlin_engine_host.json` |
| `../models/llama/tiny_pipeline_check.py` | Llama conversion + export correctness | `results/llama_pipeline_check.json` |

## On device (Phase 2 week 1, Arm phones)
1. **Llama speed matrix** (KleidiAI on/off × big/LITTLE/all cores × 2/4 threads):
   `ANDROID_NDK=... bench/build_llama_runner.sh <executorch checkout>` then
   `bench/llama_bench.sh <model.pte> <tokenizer.model>`. The prompt is a real KAVACH alert
   (`prompts/alert_hi.txt`). Adjust the `BIG`/`LITTLE` core masks for the SoC. Run it for each
   ladder rung and on both phone tiers.
2. **Guarding session** (battery, temperature, clocks, ASR RTF, Llama TTFT in the real app):
   guard a call or a demo replay, then
   `adb pull /sdcard/Android/data/io.kavach.guardian/files/bench/ bench/device/` and
   `python bench/parse_session.py bench/device/bench/guardian_<ts>.csv`.
   Battery drain per hour: unplug the phone, guard for ≥ 30 min, screen on at minimum brightness.
3. **Thermal soak**: `bench/thermal_soak.sh 30` while the Guardian loops a demo replay with Llama alerts.
4. **Memory**: `adb shell dumpsys meminfo io.kavach.guardian` during an alert (target ≤ 2.5 GB PSS).

Report medians over ≥ 5 runs after one warm-up, the phone at room temperature, with flight mode on.
