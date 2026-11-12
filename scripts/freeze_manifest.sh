#!/usr/bin/env bash
# Write docs/FREEZE_MANIFEST.md for the build that will be evaluated and submitted.
# Run on the freeze day with the phone connected (USB debugging on):
#   scripts/freeze_manifest.sh [dist/kavach-guardian-debug.apk] [dist/kavach-netwatch-debug.apk]
# Records: git commit, APK SHA-256 + version + signing certificate, model file hashes on the phone,
# device model / SoC / RAM / Android build. Nothing here is uploaded anywhere.
set -euo pipefail
cd "$(dirname "$0")/.."
G_APK=${1:-dist/kavach-guardian-debug.apk}
N_APK=${2:-dist/kavach-netwatch-debug.apk}
OUT=docs/FREEZE_MANIFEST.md
M=/sdcard/Android/data/io.kavach.guardian/files/models

{
echo "# KAVACH freeze manifest"
echo
echo "Written $(date '+%Y-%m-%d %H:%M %Z') by scripts/freeze_manifest.sh. Do not change the build, thresholds or models after this point until the 20-call pilot is scored."
echo
echo "## Source"
echo
echo "| Item | Value |"
echo "|---|---|"
echo "| Commit | \`$(git rev-parse HEAD)\` |"
echo "| Uncommitted changes | $(git status --porcelain | wc -l | tr -d ' ') files |"
echo
echo "## APKs"
echo
echo "| APK | SHA-256 | Version | Signing certificate SHA-256 |"
echo "|---|---|---|---|"
for apk in "$G_APK" "$N_APK"; do
  [[ -f "$apk" ]] || { echo "| $apk | missing | | |"; continue; }
  sha=$(sha256sum "$apk" | cut -d' ' -f1)
  ver=$( (aapt dump badging "$apk" 2>/dev/null || true) | sed -n "s/.*versionCode='\([^']*\)' versionName='\([^']*\)'.*/\2 (\1)/p" | head -1)
  cert=$( (apksigner verify --print-certs "$apk" 2>/dev/null || true) | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
  echo "| \`$(basename "$apk")\` | \`$sha\` | ${ver:-run with Android build-tools on PATH} | ${cert:-run with apksigner on PATH} |"
done
echo
if adb get-state >/dev/null 2>&1; then
  p() { adb shell getprop "$1" | tr -d '\r'; }
  echo "## Phone"
  echo
  echo "| Item | Value |"
  echo "|---|---|"
  echo "| Model | $(p ro.product.manufacturer) $(p ro.product.model) |"
  echo "| SoC | $(p ro.soc.manufacturer) $(p ro.soc.model) |"
  echo "| RAM | $(adb shell cat /proc/meminfo | tr -d '\r' | awk '/MemTotal/{printf "%.1f GB", $2/1048576}') |"
  echo "| Android | $(p ro.build.version.release) (SDK $(p ro.build.version.sdk)), build \`$(p ro.build.fingerprint)\` |"
  echo "| Installed app | $(adb shell dumpsys package io.kavach.guardian | tr -d '\r' | awk -F= '/versionName/{print $2; exit}') |"
  echo
  echo "## Model files on the phone"
  echo
  echo "| File | SHA-256 | Bytes |"
  echo "|---|---|---|"
  adb shell "cd $M 2>/dev/null && find . -type f | sort | while read f; do echo \"\$f \$(sha256sum \"\$f\" | cut -d' ' -f1) \$(stat -c %s \"\$f\")\"; done" | tr -d '\r' \
    | while read -r f h s; do echo "| \`${f#./}\` | \`$h\` | $s |"; done
else
  echo "## Phone"
  echo
  echo "No phone connected: re-run with the phone on USB to record the device and model hashes."
fi
echo
echo "## Runtimes"
echo
echo "sherpa-onnx $(grep sherpaOnnxVersion android/gradle.properties | cut -d= -f2) (speech) and executorch-android $(grep executorchVersion android/gradle.properties | cut -d= -f2) with the XNNPACK delegate (Llama), from \`android/gradle.properties\`; Llama exported with executorch==1.4.0 in \`notebooks/kavach_llama_lora_colab.ipynb\`."
} > "$OUT"
echo "wrote $OUT"
