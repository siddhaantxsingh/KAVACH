#!/usr/bin/env bash
# Build both debug APKs (needs Android SDK: ANDROID_HOME or android/local.properties with sdk.dir).
# All apps are signed with android/kavach-shared-debug.keystore, so builds from any computer (or GitHub) work together.
#   scripts/build_apks.sh            -> dist/kavach-guardian-debug.apk, dist/kavach-shield-debug.apk,
#                                       dist/kavach-netwatch-debug.apk (optional DNS watch app)
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f android/guardian/libs/sherpa-onnx-$(grep sherpaOnnxVersion android/gradle.properties | cut -d= -f2).aar ]] || scripts/fetch_android_deps.sh
( cd android && ./gradlew --no-daemon :core:test :guardian:assembleDebug :app:assembleDebug :netwatch:assembleDebug )
mkdir -p dist
cp android/guardian/build/outputs/apk/debug/guardian-debug.apk dist/kavach-guardian-debug.apk
cp android/app/build/outputs/apk/debug/app-debug.apk dist/kavach-shield-debug.apk
cp android/netwatch/build/outputs/apk/debug/netwatch-debug.apk dist/kavach-netwatch-debug.apk
ls -la dist/
