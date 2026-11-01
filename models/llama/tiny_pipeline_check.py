"""End-to-end check of the Llama -> ExecuTorch pipeline on a tiny random Llama-3.2-shaped model.

Runs on a laptop / CI in a minute and proves, without the gated 1B weights, that
  1. hf_to_meta.py converts HF weights exactly (HF logits == ExecuTorch eager logits),
  2. export_llm produces a working .pte with KV cache + custom SDPA on XNNPACK,
     both fp32 and 8da4w-quantised (the shipped recipe),
  3. the .pte executed by the ExecuTorch runtime reproduces the HF next-token logits
     step by step (fp32: ~exact; 8da4w: close, same argmax most of the time).
Results: bench/results/llama_pipeline_check.json
"""
from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

import torch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
WORK = HERE / "work" / "tiny"
sys.path.insert(0, str(HERE))
from hf_to_meta import convert_state_dict, params_json  # noqa: E402


def build_hf():
    from transformers import LlamaConfig, LlamaForCausalLM

    cfg = LlamaConfig(vocab_size=512, hidden_size=128, intermediate_size=384, num_hidden_layers=2, num_attention_heads=4,
                      num_key_value_heads=2, max_position_embeddings=131072, rms_norm_eps=1e-5, rope_theta=500000.0,
                      rope_scaling={"rope_type": "llama3", "factor": 32.0, "low_freq_factor": 1.0, "high_freq_factor": 4.0,
                                    "original_max_position_embeddings": 8192},
                      tie_word_embeddings=True, bos_token_id=1, eos_token_id=2)
    torch.manual_seed(0)
    m = LlamaForCausalLM(cfg).eval()
    return m, cfg.to_dict()


def et_eager(meta_sd, params, max_seq=64):
    from executorch.examples.models.llama.llama_transformer import construct_transformer
    from executorch.examples.models.llama.model_args import ModelArgs

    args = ModelArgs(**params, max_seq_len=max_seq, max_context_len=max_seq, generate_full_logits=True)
    if args.use_scaled_rope:
        args.rope_scale_factor = 32  # what export_llm does for llama3_2
    m = construct_transformer(args)
    missing, unexpected = m.load_state_dict(meta_sd, strict=False)
    missing = [k for k in missing if "rope" not in k and "freqs" not in k and "mask" not in k]
    assert not missing and not unexpected, (missing, unexpected)
    return m.eval()


def export(cfg_yaml: Path, meta_dir: Path, name: str) -> Path:
    out = WORK / name
    cmd = [sys.executable, "-m", "executorch.extension.llm.export.export_llm", "--config", str(cfg_yaml),
           f"+base.checkpoint={meta_dir/'consolidated.00.pth'}", f"+base.params={meta_dir/'params.json'}",
           f"+export.output_name={out}", "export.max_seq_length=64", "export.max_context_length=64"]
    t0 = time.time()
    r = subprocess.run(cmd, cwd=WORK, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-3000:], r.stderr[-3000:])
        raise SystemExit(f"export failed for {name}")
    print(f"exported {out.name} in {time.time()-t0:.1f}s ({out.stat().st_size/1024:.0f} KB)")
    return out


def run_pte(pte: Path, tokens: list[int]) -> torch.Tensor:
    """Token-by-token decode through the KV cache; returns logits after each step [T, V]."""
    # registers llama::custom_sdpa / update_cache and the quantized kernels with the runtime
    import executorch.extension.llm.custom_ops.custom_ops  # noqa: F401
    import executorch.kernels.quantized  # noqa: F401
    from executorch.runtime import Runtime

    rt = Runtime.get()
    prog = rt.load_program(str(pte))
    method = prog.load_method("forward")
    outs = []
    for pos, t in enumerate(tokens):
        o = method.execute([torch.tensor([[t]], dtype=torch.long), torch.tensor([pos], dtype=torch.long)])
        outs.append(o[0].reshape(-1)[-512:].clone())
    return torch.stack(outs)


def main() -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    hf, cfg = build_hf()
    meta = convert_state_dict({k: v.detach().clone() for k, v in hf.state_dict().items()}, cfg)
    params = params_json(cfg)
    meta_dir = WORK / "meta"
    meta_dir.mkdir(exist_ok=True)
    torch.save(meta, meta_dir / "consolidated.00.pth")
    (meta_dir / "params.json").write_text(json.dumps(params))

    torch.manual_seed(1)
    tokens = torch.randint(3, 512, (1, 24))
    with torch.no_grad():
        hf_logits = hf(tokens).logits[0]
        et = et_eager(meta, params)
        et_logits = et(tokens)
        et_logits = (et_logits[0] if isinstance(et_logits, tuple) else et_logits)[0]
    eager_diff = float((hf_logits - et_logits).abs().max())
    print(f"HF vs ExecuTorch eager max |diff| = {eager_diff:.2e}")

    res = {"model": "tiny llama3.2-shaped (2 layers, dim 128, GQA 4/2, llama3 rope scaling x32, tied embeddings)",
           "conversion_max_abs_logit_diff": eager_diff, "conversion_ok": eager_diff < 1e-3}

    fp32_cfg = WORK / "fp32.yaml"
    fp32_cfg.write_text((HERE / "configs/kavach_xnnpack_8da4w.yaml").read_text().split("quantization:")[0] +
                        "backend:\n  xnnpack:\n    enabled: True\n    extended_ops: True\n")
    q_cfg = HERE / "configs/kavach_xnnpack_8da4w.yaml"
    for name, cfgf in (("tiny_fp32.pte", fp32_cfg), ("tiny_8da4w.pte", q_cfg)):
        pte = export(cfgf, meta_dir, name)
        steps = run_pte(pte, tokens[0].tolist())
        d = float((steps - hf_logits).abs().max())
        agree = float((steps.argmax(-1) == hf_logits.argmax(-1)).float().mean())
        rel = float(((steps - hf_logits).norm(dim=-1) / hf_logits.norm(dim=-1)).mean())
        print(f"{name}: max |diff| {d:.3e}, mean relative error {rel:.3e}, next-token argmax agreement {agree:.2%}")
        res[name] = {"size_kb": round(pte.stat().st_size / 1024, 1), "max_abs_logit_diff": d, "mean_rel_err": rel, "argmax_agreement": agree}
    out = ROOT / "bench/results/llama_pipeline_check.json"
    out.write_text(json.dumps(res, indent=2))
    print("wrote", out)


if __name__ == "__main__":
    main()
