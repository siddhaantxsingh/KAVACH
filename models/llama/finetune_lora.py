"""LoRA fine-tune of Llama-3.2-1B-Instruct on KAVACH verdict pairs (free Colab / Kaggle T4 is enough).

  pip install -r models/llama/requirements-train.txt
  huggingface-cli login            # accept the Llama 3.2 licence on huggingface.co first
  python models/llama/finetune_lora.py --train data/synth/out/sft_train.jsonl --dev data/synth/out/sft_dev.jsonl \
      --out models/llama/work/kavach-lora

Only the assistant JSON is trained on (prompt tokens are masked with -100). The adapter is
merged into the base weights at the end (<out>/merged) so hf_to_meta.py + export_llm can
quantise the whole model to 4-bit for ExecuTorch. On a T4 (fp16) 2 epochs of 4k pairs at
~800 tokens take roughly 1-1.5 hours; use --max-steps for a quick smoke run.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch


def load(path: str) -> list[dict]:
    return [json.loads(l) for l in open(path, encoding="utf-8")]


def encode(tok, rows: list[dict], max_len: int) -> list[dict]:
    out = []
    for r in rows:
        msgs = r["messages"]
        prompt_ids = tok.apply_chat_template(msgs[:2], add_generation_prompt=True, tokenize=True)
        full_ids = tok.apply_chat_template(msgs, tokenize=True)
        if len(full_ids) > max_len:
            continue
        labels = [-100] * len(prompt_ids) + full_ids[len(prompt_ids):]
        out.append({"input_ids": full_ids, "labels": labels})
    return out


class Collator:
    def __init__(self, pad_id: int):
        self.pad = pad_id

    def __call__(self, batch):
        n = max(len(b["input_ids"]) for b in batch)
        ids = torch.full((len(batch), n), self.pad, dtype=torch.long)
        lab = torch.full((len(batch), n), -100, dtype=torch.long)
        att = torch.zeros((len(batch), n), dtype=torch.long)
        for i, b in enumerate(batch):
            L = len(b["input_ids"])
            ids[i, :L] = torch.tensor(b["input_ids"])
            lab[i, :L] = torch.tensor(b["labels"])
            att[i, :L] = 1
        return {"input_ids": ids, "labels": lab, "attention_mask": att}


def main() -> None:
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments

    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="meta-llama/Llama-3.2-1B-Instruct")
    ap.add_argument("--train", required=True)
    ap.add_argument("--dev", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--max-steps", type=int, default=-1)
    ap.add_argument("--max-len", type=int, default=1280)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--lr", type=float, default=2e-4)
    a = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(a.base)
    tok.pad_token = tok.pad_token or "<|finetune_right_pad_id|>"
    bf16 = torch.cuda.is_available() and torch.cuda.is_bf16_supported()
    model = AutoModelForCausalLM.from_pretrained(a.base, torch_dtype=torch.bfloat16 if bf16 else torch.float16)
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(r=a.rank, lora_alpha=2 * a.rank, lora_dropout=0.05, task_type="CAUSAL_LM",
                                             target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]))
    model.print_trainable_parameters()
    train, dev = encode(tok, load(a.train), a.max_len), encode(tok, load(a.dev), a.max_len)
    print(f"{len(train)} train / {len(dev)} dev examples after length filter")

    args = TrainingArguments(output_dir=a.out, num_train_epochs=a.epochs, max_steps=a.max_steps, per_device_train_batch_size=2,
                             gradient_accumulation_steps=8, learning_rate=a.lr, lr_scheduler_type="cosine", warmup_ratio=0.03,
                             logging_steps=10, eval_strategy="steps", eval_steps=100, save_steps=200, save_total_limit=2,
                             bf16=bf16, fp16=torch.cuda.is_available() and not bf16, report_to=[], per_device_eval_batch_size=2, remove_unused_columns=False)
    Trainer(model=model, args=args, train_dataset=train, eval_dataset=dev, data_collator=Collator(tok.pad_token_id)).train()

    model.save_pretrained(Path(a.out) / "adapter")
    merged = model.merge_and_unload()
    merged = merged.to(torch.float32)  # export_llm quantises from fp32
    merged.save_pretrained(Path(a.out) / "merged", safe_serialization=True)
    tok.save_pretrained(Path(a.out) / "merged")
    print("merged model ->", Path(a.out) / "merged")


if __name__ == "__main__":
    main()
