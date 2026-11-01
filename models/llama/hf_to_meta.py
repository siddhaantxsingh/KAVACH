"""Convert a Hugging Face Llama checkpoint (e.g. our LoRA-merged Llama-3.2-1B-Instruct)
into the Meta format that ExecuTorch's export_llm expects (consolidated.00.pth + params.json).

The only non-trivial step is undoing the rotary-embedding row permutation that the
Meta->HF conversion applies to q_proj / k_proj. tiny_pipeline_check.py proves this
conversion is exact by comparing HF logits with ExecuTorch's own Llama implementation.

Usage: python models/llama/hf_to_meta.py <hf_dir> <out_dir>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import torch


def unpermute(w: torch.Tensor, n_heads: int) -> torch.Tensor:
    """Inverse of transformers' convert_llama_weights_to_hf.permute()."""
    dim1, dim2 = w.shape
    return w.view(n_heads, 2, dim1 // n_heads // 2, dim2).transpose(1, 2).reshape(dim1, dim2)


def convert_state_dict(sd: dict[str, torch.Tensor], cfg: dict) -> dict[str, torch.Tensor]:
    n_heads = cfg["num_attention_heads"]
    n_kv = cfg.get("num_key_value_heads", n_heads)
    out: dict[str, torch.Tensor] = {}
    for k, v in sd.items():
        if k == "model.embed_tokens.weight":
            out["tok_embeddings.weight"] = v
        elif k == "model.norm.weight":
            out["norm.weight"] = v
        elif k == "lm_head.weight":
            out["output.weight"] = v
        elif k.startswith("model.layers."):
            parts = k.split(".")
            i, rest = parts[2], ".".join(parts[3:])
            p = f"layers.{i}."
            m = {
                "self_attn.q_proj.weight": ("attention.wq.weight", lambda t: unpermute(t, n_heads)),
                "self_attn.k_proj.weight": ("attention.wk.weight", lambda t: unpermute(t, n_kv)),
                "self_attn.v_proj.weight": ("attention.wv.weight", None),
                "self_attn.o_proj.weight": ("attention.wo.weight", None),
                "mlp.gate_proj.weight": ("feed_forward.w1.weight", None),
                "mlp.down_proj.weight": ("feed_forward.w2.weight", None),
                "mlp.up_proj.weight": ("feed_forward.w3.weight", None),
                "input_layernorm.weight": ("attention_norm.weight", None),
                "post_attention_layernorm.weight": ("ffn_norm.weight", None),
            }.get(rest)
            if m is None:
                if "rotary_emb" in rest:
                    continue
                raise KeyError(f"unexpected key {k}")
            name, fn = m
            out[p + name] = fn(v) if fn else v
        elif "rotary_emb" in k:
            continue
        else:
            raise KeyError(f"unexpected key {k}")
    if "output.weight" not in out:  # Llama 3.2 1B ties input and output embeddings
        out["output.weight"] = out["tok_embeddings.weight"]
    return out


def params_json(cfg: dict) -> dict:
    """params.json in Meta format; hidden_dim is given explicitly so no multiplier maths is needed."""
    rs = cfg.get("rope_scaling") or {}
    return {
        "dim": cfg["hidden_size"],
        "n_layers": cfg["num_hidden_layers"],
        "n_heads": cfg["num_attention_heads"],
        "n_kv_heads": cfg.get("num_key_value_heads", cfg["num_attention_heads"]),
        "vocab_size": cfg["vocab_size"],
        "hidden_dim": cfg["intermediate_size"],
        "norm_eps": cfg.get("rms_norm_eps", 1e-5),
        "rope_theta": cfg.get("rope_theta", 500000.0),
        "use_scaled_rope": rs.get("rope_type", rs.get("type")) == "llama3",
    }


def main() -> None:
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)
    cfg = json.loads((src / "config.json").read_text())
    from safetensors.torch import load_file

    sd: dict[str, torch.Tensor] = {}
    for f in sorted(src.glob("*.safetensors")):
        sd.update(load_file(str(f)))
    if not sd:
        sd = torch.load(src / "pytorch_model.bin", map_location="cpu")
    meta = convert_state_dict(sd, cfg)
    torch.save(meta, dst / "consolidated.00.pth")
    (dst / "params.json").write_text(json.dumps(params_json(cfg), indent=2))
    print(f"wrote {dst/'consolidated.00.pth'} ({len(meta)} tensors) and params.json")


if __name__ == "__main__":
    main()
