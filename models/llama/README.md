# Llama 3.2 1B — KAVACH scam advisor (ExecuTorch, Arm CPU)

Llama is the **reasoning layer**, not the hot loop. The always-on screen (cue lexicon +
hashed linear classifier + kill-chain) runs on every ASR segment in < 1 ms. Llama runs only
when the Guardian escalates to CAUTION/STOP, returns a validated JSON verdict, and may raise
CAUTION to STOP early ("llm-early-warning"). It can never lower a rule STOP (fail-safe).

```
transcript window + cue scores + top-3 playbook entries ──► Llama 3.2 1B (4-bit, XNNPACK/KleidiAI)
                                                             ──► {"scam","type","stage","confidence","evidence","say","action"}
                                                             ──► validator (enums, verbatim evidence, ≤35 words) ──► UI / TTS
```

## What is verified in this repository (no gated weights needed)

`python models/llama/tiny_pipeline_check.py` builds a tiny random Llama with the exact 3.2
architecture features (GQA, llama3 RoPE scaling ×32, tied embeddings) and checks the whole
path. Results (`bench/results/llama_pipeline_check.json`):

| Check | Result |
|---|---|
| `hf_to_meta.py` conversion: HF logits vs ExecuTorch eager Llama | max \|Δ\| 3.6e-7 |
| fp32 `.pte` (KV cache + custom SDPA, XNNPACK) vs HF, token by token | max \|Δ\| 4.8e-7, 100% argmax agreement |
| shipped 8da4w g32 `.pte` vs HF | 100% argmax agreement (random weights are a worst case for 4-bit) |
| `export_ladder.sh` (bf16, 8da4w g128, 8da4w g32) | all three export |

Known limitation found here: `qmode: int8` fails on GQA projections in ExecuTorch 1.5.1 and
`torchao:8da8w` cannot be combined with XNNPACK, so the ladder's middle rung is 4-bit g128.

## Easiest way: the Colab notebook

Open `notebooks/kavach_llama_lora_colab.ipynb` in Google Colab (File → Open notebook → GitHub →
`siddhaantxsingh/KAVACH`), choose a T4 GPU and Run all. It does steps 2–5 below with
`executorch==1.4.0` (the app's runtime), scores the model before and after (including
`copied_example` and `say_lang_ok`), and saves the `.pte`, tokenizer and score files to
Google Drive → `kavach/`. The pipeline was smoke-tested end to end on a tiny random Llama here.

## Steps on real weights

1. **Accept the licence** for `meta-llama/Llama-3.2-1B-Instruct` on Hugging Face, `huggingface-cli login`.
2. **Data**: `python data/synth/generate_calls.py && python models/llama/make_sft_data.py`
   (4,000 train / 400 dev pairs; see `sft_sample.jsonl`). The prompt is produced by the same
   code the phone runs, so train and inference inputs are identical.
3. **LoRA fine-tune** (Colab/Kaggle T4, ~1–1.5 h):
   `pip install -r models/llama/requirements-train.txt`
   `python models/llama/finetune_lora.py --train data/synth/out/sft_train.jsonl --dev data/synth/out/sft_dev.jsonl --out models/llama/work/kavach-lora`
4. **Export** (Linux/macOS, ~10 min per rung):
   `pip install -r models/llama/requirements.txt`
   `./models/llama/export_ladder.sh models/llama/work/kavach-lora/merged models/llama/work/pte`
   Also export the **base** model the same way for the LoRA on/off comparison.
5. **Evaluate quality per rung** (host): `python models/llama/eval_llama.py --pte models/llama/work/pte/kavach_llama3_2_1b.pte --tokenizer <tokenizer.model> --name llama_8da4w`
   and `--hf models/llama/work/kavach-lora/merged --name llama_lora_bf16`, `--hf meta-llama/Llama-3.2-1B-Instruct --name llama_base`.
6. **Push to phones**: `scripts/push_models.sh --llama models/llama/work/pte/kavach_llama3_2_1b.pte <tokenizer.model>`
7. **On-device speed** (TTFT, prefill/decode tok/s, KleidiAI on/off, big vs LITTLE): `bench/README.md`.

Tokenizer: use `original/tokenizer.model` from the Hugging Face repo (tiktoken format), which
the ExecuTorch runner loads directly.

## Prompt and latency design

* System prefix (rules + schema + scam ids) is constant → pre-filled into the KV cache when
  guarding starts and re-armed in the background after each alert (`LlamaAdvisor.warm`).
* Generation stops as soon as the JSON object closes; hard timeout 8 s.
* Greedy decoding (temperature 0) → deterministic verdicts.
* The prompt is ~700–900 tokens (Hindi tokenises less efficiently); the biggest remaining
  lever for TTFT is retrieving 2 instead of 3 playbook entries — measure both.
