"""Evaluate the Guardian on human-recorded calls (demo/recording_protocol.md).

  python bench/eval_recorded.py --dir data/recorded --lang hi --asr-dir ~/.cache/kavach-models
labels.csv columns: file,label(scam|benign),type,language,money_t[,reference_transcript]
Each WAV (16 kHz mono) is streamed through the same sherpa-onnx ASR configuration as the
Guardian app (Hindi Zipformer2-CTC or English Zipformer) and the guardian engine; prints
the call-level metrics of bench/eval_calls.py plus WER where a reference is given.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "bench"))
from e2e_audio import stream_asr, wer  # noqa: E402
from eval_calls import summarize  # noqa: E402
from kavach_core.features import LinearModel  # noqa: E402
from kavach_core.guardian import Guardian, GuardianConfig  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.normalize import tokens  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402


def recognizer(asr_dir: Path, lang: str):
    import sherpa_onnx

    common = dict(num_threads=2, sample_rate=16000, feature_dim=80, enable_endpoint_detection=True, rule1_min_trailing_silence=2.4,
                  rule2_min_trailing_silence=0.8, rule3_min_utterance_length=12.0, decoding_method="greedy_search")
    if lang == "hi":
        return sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(tokens=str(asr_dir / "hi/tokens.txt"), model=str(asr_dir / "hi/model.int8.onnx"), **common)
    d = asr_dir / "en20m"
    return sherpa_onnx.OnlineRecognizer.from_transducer(tokens=str(d / "tokens.txt"), encoder=str(d / "encoder-epoch-99-avg-1.int8.onnx"),
                                                        decoder=str(d / "decoder-epoch-99-avg-1.onnx"), joiner=str(d / "joiner-epoch-99-avg-1.int8.onnx"), **common)


def read_wav(p: Path) -> np.ndarray:
    with wave.open(str(p)) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1, f"{p}: need 16 kHz mono"
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--lang", default="hi")
    ap.add_argument("--asr-dir", default=str(Path.home() / ".cache/kavach-models"))
    a = ap.parse_args()
    d = Path(a.dir)
    rec = recognizer(Path(a.asr_dir), a.lang)
    g = Guardian(Lexicon(), LinearModel.load(ROOT / "assets/models", "cue_model"), Playbook(), GuardianConfig(lang=a.lang))
    per, wers = [], []
    for r in csv.DictReader(open(d / "labels.csv", encoding="utf-8")):
        finals, _ = stream_asr(rec, read_wav(d / r["file"]))
        hist = g.run_transcript(finals)
        t_stop = next((s.t for s in hist if s.level == "stop"), None)
        t_c = next((s.t for s in hist if s.level in ("caution", "stop")), None)
        money = float(r["money_t"]) if r.get("money_t") else None
        per.append({"id": r["file"], "label": 1 if r["label"] == "scam" else 0, "type": r["type"], "lang": r["language"], "t_stop": t_stop,
                    "t_caution": t_c, "money_t": money, "cue_t": 0.0, "rule": g.rule})
        if r.get("reference_transcript"):
            wers.append(wer(tokens(r["reference_transcript"]), tokens(" ".join(t for _, t in finals))))
    res = summarize(per)
    if wers:
        res["wer_mean"] = round(float(np.mean(wers)), 3)
    print(json.dumps(res, indent=2, ensure_ascii=False))
    (ROOT / f"bench/results/recorded_{a.lang}.json").write_text(json.dumps(res, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
