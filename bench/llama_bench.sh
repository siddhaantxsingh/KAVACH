#!/usr/bin/env bash
# On-device Llama benchmark matrix -> bench/results/device_<model>_llama.csv
#   bench/llama_bench.sh <model.pte> <tokenizer.model> [runs=5]
# Matrix: KleidiAI {ON,OFF} x placement {big (top cores), little (core 0-3), all} x threads {2,4}.
# Prompt = a real KAVACH alert prompt (bench/prompts/alert_hi.txt), so TTFT reflects production.
# Core masks: run `adb shell cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` and adjust
# BIG/LITTLE below for your SoC (examples: Snapdragon 8 Gen 3 cpu7=prime, cpu3-6=big, cpu0-2=little).
set -euo pipefail
PTE=${1:?model.pte}; TOK=${2:?tokenizer.model}; RUNS=${3:-5}
HERE=$(cd "$(dirname "$0")" && pwd)
D=/data/local/tmp/kavach
MODEL=$(adb shell getprop ro.product.model | tr -d '\r ' )
LITTLE=${LITTLE:-0f}; BIG=${BIG:-f0}; ALL=${ALL:-ff}
adb shell mkdir -p $D
adb push "$PTE" $D/model.pte >/dev/null
adb push "$TOK" $D/tokenizer.model >/dev/null
for K in ON OFF; do adb push "$HERE/device/bin/llama_main_kleidi$K" $D/ >/dev/null; adb shell chmod +x $D/llama_main_kleidi$K; done
adb push "$HERE/prompts/alert_hi.txt" $D/prompt.txt >/dev/null
OUT="$HERE/results/device_${MODEL}_llama.csv"
echo "model,kleidi,placement,threads,run,prompt_tokens,generated_tokens,ttft_ms,prefill_tps,decode_tps,rss_kb" > "$OUT"
for K in ON OFF; do for P in big:$BIG little:$LITTLE all:$ALL; do for T in 2 4; do for r in $(seq 1 "$RUNS"); do
  name=${P%%:*}; mask=${P##*:}
  LOG=$(adb shell "cd $D && taskset $mask ./llama_main_kleidi$K --model_path model.pte --tokenizer_path tokenizer.model \
        --prompt \"\$(cat prompt.txt)\" --seq_len 1200 --temperature 0 --cpu_threads $T --warmup 1 2>&1; \
        grep VmHWM /proc/self/status" || true)
  J=$(echo "$LOG" | grep -o 'PyTorchObserver {.*}' | tail -1 | sed 's/PyTorchObserver //')
  python3 - "$J" "$MODEL" "$K" "$name" "$T" "$r" >> "$OUT" <<'PY'
import json, sys
j = json.loads(sys.argv[1] or "{}"); m, k, p, t, r = sys.argv[2:7]
pt, gt = j.get("prompt_tokens", 0), j.get("generated_tokens", 0)
s, pe, ft, e = j.get("inference_start_ms", 0), j.get("prompt_eval_end_ms", 0), j.get("first_token_ms", 0), j.get("inference_end_ms", 0)
pre = pt * 1000 / (pe - s) if pe > s else 0; dec = (gt - 1) * 1000 / (e - pe) if e > pe and gt > 1 else 0
print(f"{m},{k},{p},{t},{r},{pt},{gt},{ft - s},{pre:.1f},{dec:.2f},")
PY
done; done; done; done
echo "wrote $OUT"; python3 "$HERE/summarize_device.py" "$OUT"
