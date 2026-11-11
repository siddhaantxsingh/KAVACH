# On-phone benchmark (Calls → Setup & tools → Measure performance)

`android/guardian/src/main/kotlin/kavach/guardian/BenchmarkActivity.kt` measures, on the phone's
Arm CPU, and writes `/sdcard/Android/data/io.kavach.guardian/files/bench/kavach_bench_<time>.json`.
Unplug USB/charger first, otherwise battery numbers are invalid (the screen says so).

| Key | What | How |
|---|---|---|
| `phone` | maker/model, SoC, ABIs, cores and max MHz per core, RAM, Android version, charging | `Build`, sysfs `cpuinfo_max_freq` |
| `model_files_mb` | size of every model file on the phone; detector assets in KB | file sizes |
| `detector_ms_per_segment` | rules + classifier + kill-chain per transcript sentence | 40 runs × 8 sentences, after 5 warm-up runs |
| `asr.{hi,en}_t{1,2,4}` | real-time factor (processing time / audio time), load time, memory delta, at 1, 2 and 4 threads | 15 s speech clip (`assets/bench/sample_15s_16k.pcm`, synthetic Kokoro voice) |
| `llama` | load time; memory before/after load; cold and warm-prefix first-token time, prefill and decode tokens/s; battery current while generating; thermal status before/after | `LlamaAdvisor` stats from ExecuTorch, Llama 3.2 1B SpinQuant INT4 `.pte` |
| `listening_60s` | one minute of real-time streaming recognition: CPU % of one core, average battery current, estimated % battery per hour, battery temperature start/end, thermal status | `Process.getElapsedCpuTime`, `BatteryManager.CURRENT_NOW`, `PowerProfile` capacity |

Notes: call protection is paused during the test (two copies of Llama would not fit in memory)
and switched back on afterwards. Phones report battery current in µA or mA; values above 20,000
are treated as µA. `PowerProfile` capacity is read by reflection and may be missing on some phones
(then no %/hour is shown).
