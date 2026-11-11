#!/usr/bin/env bash
# Download the on-device models and push them to the phones with adb.
#
#   scripts/push_models.sh --asr                      # English + Hindi speech models + VAD -> the KAVACH app
#   scripts/push_models.sh --asr-en-big               # only the NVIDIA Parakeet 110M English model (126 MB, from GitHub)
#   scripts/push_models.sh --asr-hi-big               # only the AI4Bharat IndicConformer Hindi model (198 MB, from GitHub)
#   scripts/push_models.sh --asr-small                # same, but the small 20M English model (low-end phones)
#   scripts/push_models.sh --llama <file.pte> <tokenizer.model>
#   scripts/push_models.sh --official-spinquant       # Meta's pre-quantised Llama 3.2 1B Instruct (no KAVACH LoRA),
#                                                     # handy before your own fine-tune is ready
# Select a phone with ANDROID_SERIAL=<serial>. Models go to /sdcard/Android/data/<pkg>/files/models/.
set -euo pipefail
cd "$(dirname "$0")/.."
CACHE=${KAVACH_MODEL_CACHE:-$HOME/.cache/kavach-models}
mkdir -p "$CACHE"
G=io.kavach.guardian
S=io.kavach.shield  # former separate Shield app (now part of the one KAVACH app); unused
dst() { echo "/sdcard/Android/data/$1/files/models"; }
# Git Bash on Windows rewrites arguments that start with "/" into Windows paths; keep phone paths as they are
export MSYS2_ARG_CONV_EXCL="/sdcard;/data"
# Python launcher that really runs Python 3 (paths may contain spaces; Windows may have a fake python3 alias)
PY=()
for cand in "python3" "python" "py -3"; do
  read -r -a c <<< "$cand"
  if command -v "${c[0]}" >/dev/null 2>&1 && "${c[@]}" -c 'import sys; sys.exit(sys.version_info < (3,))' >/dev/null 2>&1; then PY=("${c[@]}"); break; fi
done
# Hugging Face download through Python, so it works even when the hf command is not on PATH.
#   hfdl <repo> <file>... --local-dir <dir>
hfdl() {
  local repo=$1; shift; local files=() dir=.
  while [[ $# -gt 0 ]]; do
    if [[ $1 == --local-dir ]]; then dir=$2; shift 2; else files+=("$1"); shift; fi
  done
  [[ ${#PY[@]} -gt 0 ]] || { echo "Python 3 not found"; exit 1; }
  "${PY[@]}" -c 'import sys
from huggingface_hub import hf_hub_download
repo, d = sys.argv[1], sys.argv[2]
for f in sys.argv[3:]:
    print("downloading", f, "from", repo, flush=True)
    hf_hub_download(repo_id=repo, filename=f, local_dir=d)' "$repo" "$dir" "${files[@]}"
}

asr() {
  local R=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
  # English: streaming Zipformer transducer, int8. Default: zipformer-en-2023-06-21 (188 MB), which in
  # bench/e2e_noise.py reached STOP on 18/24 noisy scam calls vs 6/24 for the 20M model (0 false STOPs both).
  local EN=en0621 ENNAME=sherpa-onnx-streaming-zipformer-en-2023-06-21
  if [[ "${SMALL_EN:-0}" == 1 ]]; then EN=en20m; ENNAME=sherpa-onnx-streaming-zipformer-en-20M-2023-02-17; fi
  if [[ ! -d "$CACHE/$EN" ]]; then
    curl -fL "$R/$ENNAME.tar.bz2" | tar xj -C "$CACHE"
    mv "$CACHE/$ENNAME" "$CACHE/$EN"
  fi
  [[ -f "$CACHE/silero_vad.onnx" ]] || curl -fL -o "$CACHE/silero_vad.onnx" "$R/silero_vad.onnx"
  # Hindi/Hinglish: streaming Zipformer2-CTC, Devanagari characters (Apache-2.0; see models/asr/README.md)
  if [[ ! -f "$CACHE/hi/model.int8.onnx" ]]; then
    mkdir -p "$CACHE/hi"
    hfdl mobilebytesensei/betterflow-hindi-streaming-ctc encoder.int8.onnx tokens.txt --local-dir "$CACHE/hi"
    mv "$CACHE/hi/encoder.int8.onnx" "$CACHE/hi/model.int8.onnx"
  fi
  local D; D=$(dst $G)
  adb shell mkdir -p "$D/asr-en" "$D/asr-hi"
  adb push "$CACHE/$EN/encoder-epoch-99-avg-1.int8.onnx" "$D/asr-en/encoder.onnx"
  adb push "$CACHE/$EN/decoder-epoch-99-avg-1.onnx" "$D/asr-en/decoder.onnx"
  adb push "$CACHE/$EN/joiner-epoch-99-avg-1.int8.onnx" "$D/asr-en/joiner.onnx"
  adb push "$CACHE/$EN/tokens.txt" "$D/asr-en/tokens.txt"
  adb push "$CACHE/hi/model.int8.onnx" "$D/asr-hi/model.int8.onnx"
  adb push "$CACHE/hi/tokens.txt" "$D/asr-hi/tokens.txt"
  adb push "$CACHE/silero_vad.onnx" "$D/silero_vad.onnx"
  asr_hi_big
}

# Hindi, better model: AI4Bharat IndicConformer Hindi (120M, CTC; MIT, ONNX export CC-BY-4.0), int8 for
# sherpa-onnx, run sentence by sentence behind the VAD. Mirrored on this repo's GitHub release
# "dev-models" (no Hugging Face account needed). When present, the app uses it instead of the
# streaming Hindi model (docs/technical_report.md section 7.5).
asr_hi_big() {
  local R=https://github.com/siddhaantxsingh/KAVACH/releases/download/dev-models
  mkdir -p "$CACHE/ic"
  [[ -f "$CACHE/ic/model.int8-matmul.onnx" ]] || curl -fL -o "$CACHE/ic/model.int8-matmul.onnx" "$R/ic-hi-model.int8-matmul.onnx"
  [[ -f "$CACHE/ic/tokens.txt" ]] || curl -fL -o "$CACHE/ic/tokens.txt" "$R/ic-hi-tokens.txt"
  [[ -f "$CACHE/silero_vad.onnx" ]] || curl -fL -o "$CACHE/silero_vad.onnx" https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
  local D; D=$(dst $G)
  adb shell mkdir -p "$D/asr-hi-ic"
  adb push "$CACHE/ic/model.int8-matmul.onnx" "$D/asr-hi-ic/model.onnx"
  adb push "$CACHE/ic/tokens.txt" "$D/asr-hi-ic/tokens.txt"
  adb push "$CACHE/silero_vad.onnx" "$D/silero_vad.onnx"
}

# English, better model: NVIDIA Parakeet TDT-CTC 110M (CC-BY-4.0), int8 for sherpa-onnx, run sentence
# by sentence behind the VAD like the Hindi one. Mirrored on this repo's GitHub release "dev-models".
asr_en_big() {
  local R=https://github.com/siddhaantxsingh/KAVACH/releases/download/dev-models
  mkdir -p "$CACHE/en-big"
  [[ -f "$CACHE/en-big/model.int8.onnx" ]] || curl -fL -o "$CACHE/en-big/model.int8.onnx" "$R/parakeet-110m-en-model.int8.onnx"
  [[ -f "$CACHE/en-big/tokens.txt" ]] || curl -fL -o "$CACHE/en-big/tokens.txt" "$R/parakeet-110m-en-tokens.txt"
  [[ -f "$CACHE/silero_vad.onnx" ]] || curl -fL -o "$CACHE/silero_vad.onnx" https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
  local D; D=$(dst $G)
  adb shell mkdir -p "$D/asr-en-big"
  adb push "$CACHE/en-big/model.int8.onnx" "$D/asr-en-big/model.onnx"
  adb push "$CACHE/en-big/tokens.txt" "$D/asr-en-big/tokens.txt"
  adb push "$CACHE/silero_vad.onnx" "$D/silero_vad.onnx"
}

llama() {
  local pte=$1 tok=$2
  local D; D=$(dst $G)/llama
  adb shell mkdir -p "$D"
  adb push "$pte" "$D/kavach_llama3_2_1b.pte"
  adb push "$tok" "$D/tokenizer.model"
}

case "${1:-}" in
  --asr) asr ;;
  --asr-small) SMALL_EN=1 asr ;;
  --asr-hi-big) asr_hi_big ;;
  --asr-en-big) asr_en_big ;;
  --llama) llama "$2" "$3" ;;
  --official-spinquant)
    hfdl executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET \
      Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte tokenizer.model --local-dir "$CACHE/spinquant"
    llama "$CACHE/spinquant/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte" "$CACHE/spinquant/tokenizer.model" ;;
  *) sed -n 2,10p "$0"; exit 1 ;;
esac
echo "done"
