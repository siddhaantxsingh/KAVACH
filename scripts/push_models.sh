#!/usr/bin/env bash
# Download the on-device models and push them to the phones with adb.
#
#   scripts/push_models.sh --asr                      # Hindi/Hinglish + English streaming ASR + VAD -> Guardian
#   scripts/push_models.sh --llama <file.pte> <tokenizer.model> [--to guardian|shield|both]
#   scripts/push_models.sh --official-spinquant       # Meta's pre-quantised Llama 3.2 1B Instruct (no KAVACH LoRA),
#                                                     # handy before your own fine-tune is ready
# Select a phone with ANDROID_SERIAL=<serial>. Models go to /sdcard/Android/data/<pkg>/files/models/.
set -euo pipefail
cd "$(dirname "$0")/.."
CACHE=${KAVACH_MODEL_CACHE:-$HOME/.cache/kavach-models}
mkdir -p "$CACHE"
G=io.kavach.guardian
S=io.kavach.shield
dst() { echo "/sdcard/Android/data/$1/files/models"; }

asr() {
  local R=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
  # English: streaming Zipformer transducer (20M, int8)
  if [[ ! -d "$CACHE/en20m" ]]; then
    curl -fL "$R/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2" | tar xj -C "$CACHE"
    mv "$CACHE/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17" "$CACHE/en20m"
  fi
  [[ -f "$CACHE/silero_vad.onnx" ]] || curl -fL -o "$CACHE/silero_vad.onnx" "$R/silero_vad.onnx"
  # Hindi/Hinglish: streaming Zipformer2-CTC, Devanagari characters (Apache-2.0; see models/asr/README.md)
  if [[ ! -f "$CACHE/hi/model.int8.onnx" ]]; then
    mkdir -p "$CACHE/hi"
    huggingface-cli download mobilebytesensei/betterflow-hindi-streaming-ctc encoder.int8.onnx tokens.txt --local-dir "$CACHE/hi"
    mv "$CACHE/hi/encoder.int8.onnx" "$CACHE/hi/model.int8.onnx"
  fi
  local D; D=$(dst $G)
  adb shell mkdir -p "$D/asr-en" "$D/asr-hi"
  adb push "$CACHE/en20m/encoder-epoch-99-avg-1.int8.onnx" "$D/asr-en/encoder.onnx"
  adb push "$CACHE/en20m/decoder-epoch-99-avg-1.onnx" "$D/asr-en/decoder.onnx"
  adb push "$CACHE/en20m/joiner-epoch-99-avg-1.int8.onnx" "$D/asr-en/joiner.onnx"
  adb push "$CACHE/en20m/tokens.txt" "$D/asr-en/tokens.txt"
  adb push "$CACHE/hi/model.int8.onnx" "$D/asr-hi/model.int8.onnx"
  adb push "$CACHE/hi/tokens.txt" "$D/asr-hi/tokens.txt"
  adb push "$CACHE/silero_vad.onnx" "$D/silero_vad.onnx"
}

llama() {
  local pte=$1 tok=$2 to=${3:-both}
  for pkg in $([[ $to == both ]] && echo "$G $S" || ([[ $to == guardian ]] && echo $G || echo $S)); do
    local D; D=$(dst "$pkg")/llama
    adb shell mkdir -p "$D"
    adb push "$pte" "$D/kavach_llama3_2_1b.pte"
    adb push "$tok" "$D/tokenizer.model"
  done
}

case "${1:-}" in
  --asr) asr ;;
  --llama) llama "$2" "$3" "${5:-both}" ;;
  --official-spinquant)
    huggingface-cli download executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET \
      Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte tokenizer.model --local-dir "$CACHE/spinquant"
    llama "$CACHE/spinquant/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte" "$CACHE/spinquant/tokenizer.model" both ;;
  *) sed -n 2,9p "$0"; exit 1 ;;
esac
echo "done"
