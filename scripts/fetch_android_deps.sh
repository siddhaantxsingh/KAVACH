#!/usr/bin/env bash
# Fetch the sherpa-onnx Android AAR (streaming ASR + Silero VAD, Apache-2.0) into android/guardian/libs/.
set -euo pipefail
cd "$(dirname "$0")/.."
V=$(grep sherpaOnnxVersion android/gradle.properties | cut -d= -f2)
mkdir -p android/guardian/libs
curl -fL -o "android/guardian/libs/sherpa-onnx-$V.aar" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-$V.aar"
echo "ok: android/guardian/libs/sherpa-onnx-$V.aar"
