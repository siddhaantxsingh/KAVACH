#!/usr/bin/env bash
# Quantisation ladder for the report: the same (LoRA-merged) Llama 3.2 1B exported as
#   bf16  ->  4-bit groupwise g=128 + 8-bit embeddings  ->  4-bit groupwise g=32 + 4-bit embeddings (shipped)
# Note: the 8-bit weight-only rung (qmode int8) fails to export on the GQA projections in
# ExecuTorch 1.5.1 (shape error, reproduced on the tiny model), and torchao 8da8w cannot be
# combined with XNNPACK delegation; retry int8 after upgrading ExecuTorch.
#
#   ./models/llama/export_ladder.sh <hf_merged_dir_or_meta_dir> <out_dir>
# Requires: pip install -r models/llama/requirements.txt (executorch pinned to the app's AAR version)
set -euo pipefail
SRC=${1:?hf merged dir (with config.json) or meta dir (with params.json)}
OUT=${2:?output dir}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"

if [[ -f "$SRC/config.json" ]]; then
  python "$HERE/hf_to_meta.py" "$SRC" "$OUT/meta"
  META="$OUT/meta"
else
  META="$SRC"
fi
CK="+base.checkpoint=$META/consolidated.00.pth"
PA="+base.params=$META/params.json"
EXPORT=(python -m executorch.extension.llm.export.export_llm)
HDR='base: {model_class: llama3_2, metadata: '"'"'{"get_bos_id":128000, "get_eos_ids":[128009, 128001]}'"'"'}'
BACKEND='backend: {xnnpack: {enabled: True, extended_ops: True}}'
SEQ='export: {max_seq_length: 2048, max_context_length: 2048}'

# 1) bf16, no quantisation (reference quality / memory)
printf '%s\n%s\n%s\n%s\n' "$HDR" "model: {use_kv_cache: True, use_sdpa_with_kv_cache: True, dtype_override: bf16}" "$SEQ" "$BACKEND" > "$OUT/bf16.yaml"
"${EXPORT[@]}" --config "$OUT/bf16.yaml" "$CK" "$PA" "+export.output_name=$OUT/kavach_bf16.pte"

# 2) 4-bit groupwise, coarse groups (g=128), 8-bit embeddings
printf '%s\n%s\n%s\n%s\n%s\n' "$HDR" "model: {use_kv_cache: True, use_sdpa_with_kv_cache: True, dtype_override: fp32}" "$SEQ" \
  "quantization: {qmode: 8da4w, group_size: 128, embedding_quantize: '8,0'}" "$BACKEND" > "$OUT/8da4w_g128.yaml"
"${EXPORT[@]}" --config "$OUT/8da4w_g128.yaml" "$CK" "$PA" "+export.output_name=$OUT/kavach_8da4w_g128.pte"

# 3) shipped: 4-bit groupwise (g=32) + 4-bit embeddings -> KleidiAI int4 kernels on Arm
"${EXPORT[@]}" --config "$HERE/configs/kavach_xnnpack_8da4w.yaml" "$CK" "$PA" "+export.output_name=$OUT/kavach_llama3_2_1b.pte"

ls -la "$OUT"/*.pte
echo "Push with: scripts/push_models.sh --llama $OUT/kavach_llama3_2_1b.pte <tokenizer.model>"
