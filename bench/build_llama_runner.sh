#!/usr/bin/env bash
# Cross-compile ExecuTorch's llama_main for arm64 Android twice: KleidiAI ON (default) and OFF,
# to isolate the Arm kernel benefit on the same model and phone.
#   ANDROID_NDK=/path/to/ndk bench/build_llama_runner.sh <executorch_checkout>
set -euo pipefail
ET=${1:?path to executorch git checkout (same tag as the app, e.g. v1.4.0)}
: "${ANDROID_NDK:?set ANDROID_NDK}"
OUT=$(cd "$(dirname "$0")" && pwd)/device/bin
mkdir -p "$OUT"
cd "$ET"
for K in ON OFF; do
  B=cmake-out-android-kleidi$K
  cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-23 \
    -DCMAKE_INSTALL_PREFIX=$B -DCMAKE_BUILD_TYPE=Release -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON -DEXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR=ON \
    -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON -DEXECUTORCH_BUILD_EXTENSION_TENSOR=ON -DEXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP=ON \
    -DEXECUTORCH_BUILD_EXTENSION_LLM=ON -DEXECUTORCH_BUILD_EXTENSION_LLM_RUNNER=ON -DEXECUTORCH_ENABLE_LOGGING=1 -DPYTHON_EXECUTABLE=python \
    -DEXECUTORCH_BUILD_XNNPACK=ON -DEXECUTORCH_XNNPACK_ENABLE_KLEIDI=$K -DEXECUTORCH_BUILD_KERNELS_OPTIMIZED=ON -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=ON \
    -DEXECUTORCH_BUILD_KERNELS_LLM=ON -B$B .
  cmake --build $B -j"$(nproc)" --target install --config Release
  cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-23 \
    -DCMAKE_INSTALL_PREFIX=$B -DCMAKE_BUILD_TYPE=Release -DPYTHON_EXECUTABLE=python -DEXECUTORCH_BUILD_XNNPACK=ON \
    -DEXECUTORCH_XNNPACK_ENABLE_KLEIDI=$K -DEXECUTORCH_BUILD_KERNELS_OPTIMIZED=ON -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=ON \
    -DEXECUTORCH_BUILD_KERNELS_LLM=ON -DSUPPORT_REGEX_LOOKAHEAD=ON -B$B/examples/models/llama examples/models/llama
  cmake --build $B/examples/models/llama -j"$(nproc)" --config Release
  cp $B/examples/models/llama/llama_main "$OUT/llama_main_kleidi$K"
done
ls -la "$OUT"
