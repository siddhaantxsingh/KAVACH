#!/usr/bin/env bash
# 30-minute thermal soak: run the Guardian in demo-replay loop (or a real call) and sample
# per-core frequency, battery temperature, current and thermal status every 5 s.
#   bench/thermal_soak.sh [minutes=30]  -> bench/results/device_<model>_soak.csv
set -euo pipefail
MIN=${1:-30}
HERE=$(cd "$(dirname "$0")" && pwd)
MODEL=$(adb shell getprop ro.product.model | tr -d '\r ')
OUT="$HERE/results/device_${MODEL}_soak.csv"
echo "t_s,battery_pct,battery_temp_c,current_ua,thermal_status,cpu_freqs_khz" > "$OUT"
END=$(( $(date +%s) + MIN * 60 )); T0=$(date +%s)
while [[ $(date +%s) -lt $END ]]; do
  B=$(adb shell dumpsys battery | tr -d '\r')
  lvl=$(echo "$B" | awk -F': ' '/level/{print $2; exit}'); tmp=$(echo "$B" | awk -F': ' '/temperature/{print $2/10; exit}')
  cur=$(adb shell cat /sys/class/power_supply/battery/current_now 2>/dev/null | tr -d '\r' || echo "")
  th=$(adb shell dumpsys thermalservice 2>/dev/null | tr -d '\r' | awk -F': ' '/Thermal Status/{print $2; exit}')
  fr=$(adb shell 'for c in /sys/devices/system/cpu/cpu[0-9]*; do cat $c/cpufreq/scaling_cur_freq 2>/dev/null || echo -; done' | tr -d '\r' | paste -sd'|')
  echo "$(( $(date +%s) - T0 )),$lvl,$tmp,$cur,$th,$fr" >> "$OUT"
  sleep 5
done
echo "wrote $OUT"
