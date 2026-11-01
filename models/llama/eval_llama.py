"""Evaluate a Llama verdict model on the held-out SFT dev pairs (quantisation ladder & LoRA effect).

Backends
  --hf  <dir>                       Hugging Face model (base or merged LoRA), any device
  --pte <file.pte> --tokenizer <f>  ExecuTorch .pte through the ExecuTorch Python runner (host CPU)
Metrics (same validator the phone uses, kavach_core.llm.validate):
  json_valid       first-try parse rate (before the one re-prompt)
  scam_acc         scam / not-scam accuracy
  type_acc         scam-type accuracy on scam examples
  stop_stage_acc   stage in {isolation, action, payment} agreement
  evidence_ok      share of verdicts whose quotes all survived the verbatim check
  gen_tokens, seconds per example
Run for BF16 -> 8-bit -> 4-bit and LoRA on/off; results JSON goes to bench/results/.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from kavach_core.llm import validate  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402

STOP_STAGES = {"isolation", "action", "payment"}


def transcript_of(user_msg: str) -> str:
    return user_msg.split('Transcript (most recent last):\n"""', 1)[-1].rstrip('"')


def hf_backend(path: str):
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(path)
    model = AutoModelForCausalLM.from_pretrained(path, torch_dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32)
    model.eval().to("cuda" if torch.cuda.is_available() else "cpu")

    def gen(msgs):
        ids = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt").to(model.device)
        with torch.no_grad():
            out = model.generate(ids, max_new_tokens=160, do_sample=False, pad_token_id=tok.eos_token_id)
        return tok.decode(out[0, ids.shape[1]:], skip_special_tokens=True)
    return gen


def pte_backend(pte: str, tokenizer: str):
    import executorch.extension.llm.custom_ops.custom_ops  # noqa: F401
    import executorch.kernels.quantized  # noqa: F401
    from executorch.extension.llm.runner import GenerationConfig, TextLLMRunner

    from kavach_core.llm import format_llama3

    runner = TextLLMRunner(pte, tokenizer)

    def gen(msgs):
        prefix, suffix = format_llama3(msgs[0]["content"], msgs[1]["content"])
        buf: list[str] = []
        runner.reset()
        runner.generate(prefix + suffix, GenerationConfig(echo=False, max_new_tokens=160, temperature=0.0), token_callback=buf.append)
        return "".join(buf)
    return gen


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hf")
    ap.add_argument("--pte")
    ap.add_argument("--tokenizer")
    ap.add_argument("--data", default=str(ROOT / "data/synth/out/sft_dev.jsonl"))
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--name", default="llama_eval")
    a = ap.parse_args()
    gen = hf_backend(a.hf) if a.hf else pte_backend(a.pte, a.tokenizer)
    ids = [e["id"] for e in Playbook().entries]
    rows = [json.loads(l) for l in open(a.data, encoding="utf-8")][: a.n]
    n = valid = scam_ok = type_ok = type_n = stage_ok = ev_ok = 0
    secs = 0.0
    for r in rows:
        msgs = r["messages"]
        gold = json.loads(msgs[2]["content"])
        t0 = time.time()
        raw = gen(msgs[:2])
        secs += time.time() - t0
        v, errs = validate(raw, transcript_of(msgs[1]["content"]), ids)
        n += 1
        if v is None:
            continue
        valid += 1
        scam_ok += v.scam == gold["scam"]
        if gold["scam"]:
            type_n += 1
            type_ok += v.type == gold["type"]
            stage_ok += (v.stage in STOP_STAGES) == (gold["stage"] in STOP_STAGES)
        ev_ok += not any("non-verbatim" in e for e in errs)
    res = {"name": a.name, "n": n, "json_valid": round(valid / n, 4), "scam_acc": round(scam_ok / n, 4),
           "type_acc": round(type_ok / max(1, type_n), 4), "stop_stage_acc": round(stage_ok / max(1, type_n), 4),
           "evidence_ok": round(ev_ok / max(1, valid), 4), "sec_per_example": round(secs / n, 2)}
    print(json.dumps(res, indent=2))
    out = ROOT / f"bench/results/{a.name}.json"
    out.write_text(json.dumps(res, indent=2))


if __name__ == "__main__":
    main()
