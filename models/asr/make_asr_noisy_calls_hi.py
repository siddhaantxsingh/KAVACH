"""Hindi version of make_asr_noisy_calls.py: synthetic Hindi calls -> Piper Hindi TTS ->
speaker-phone channel (random SNR 10-30 dB, random loudness) -> the Guardian's Hindi streaming
ASR. The output is what the phone really "hears": mixed Devanagari/Latin with recognition errors.

  --split train : data/synth/out/calls_train_asr_hi.jsonl (used by train_text.py and alias mining)
  --split test  : data/synth/out/calls_test_asr_hi.jsonl  (evaluation only; never used for tuning)

The Hindi model is mirrored by .github/workflows/fetch-hi-asr.yml (release "dev-models").
Usage: python models/asr/make_asr_noisy_calls_hi.py --split train --n 160 [--shard 0/2]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bench"))
from e2e_audio import SR, speakerphone, stream_asr  # noqa: E402


def engines(models: Path, threads: int):
    import sherpa_onnx
    d = models / "vits-piper-hi_IN-pratham-medium"
    onnx = next(d.glob("*.onnx"))
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
        num_threads=threads)))
    h = models / "hi"
    rec = sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(
        tokens=str(h / "tokens.txt"), model=str(h / "encoder.int8.onnx"), num_threads=threads, sample_rate=SR, feature_dim=80,
        enable_endpoint_detection=True, rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8,
        rule3_min_utterance_length=12.0, decoding_method="greedy_search")
    return tts, rec


class IcAsr:
    """AI4Bharat IndicConformer Hindi (offline CTC) behind Silero VAD: one result per spoken sentence,
    exactly as the phone runs it (AsrEngine, Hindi with the IndicConformer files present)."""

    def __init__(self, models: Path, threads: int):
        import sherpa_onnx
        self.so = sherpa_onnx
        self.rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
            model=str(models / "ic/model.int8-matmul.onnx"), tokens=str(models / "ic/tokens.txt"), num_threads=threads)
        self.vad_model = str(models / "silero_vad.onnx")

    def decode(self, y: np.ndarray) -> list[tuple[float, str]]:
        c = self.so.VadModelConfig()
        c.silero_vad.model = self.vad_model
        c.silero_vad.min_silence_duration = 0.5
        c.silero_vad.min_speech_duration = 0.25
        c.silero_vad.max_speech_duration = 12
        c.sample_rate = SR
        vad = self.so.VoiceActivityDetector(c, buffer_size_in_seconds=180)
        y = np.concatenate([y, np.zeros(2 * SR, dtype=np.float32)])
        out = []
        for i in range(0, len(y) - 512, 512):
            vad.accept_waveform(y[i:i + 512])
            while not vad.empty():
                seg = vad.front
                x = np.asarray(seg.samples, dtype=np.float32)
                end = (seg.start + len(x)) / SR
                vad.pop()
                st = self.rec.create_stream()
                st.accept_waveform(SR, x)
                self.rec.decode_stream(st)
                if st.result.text.strip():
                    out.append((end, st.result.text.strip()))
        return out


def near(x: np.ndarray, snr_db: float, rng: np.random.Generator) -> np.ndarray:
    """Full-band audio from a nearby loudspeaker in a quiet room: mild reverb + room noise."""
    from scipy.signal import lfilter
    n = int(0.12 * SR)
    ir = rng.standard_normal(n) * np.exp(-np.arange(n) / (0.025 * SR))
    ir[0] = 1.0
    y = np.convolve(x, ir / np.abs(ir).sum() * 3)[: len(x)]
    noise = lfilter([1.0], [1.0, -0.95], rng.standard_normal(len(y)))
    act = y[np.abs(y) > 1e-4]
    p = float(np.mean(act ** 2)) if len(act) else 1e-6
    y = y + noise * np.sqrt(p / (10 ** (snr_db / 10)) / np.mean(noise ** 2))
    return (0.7 * y / max(1e-6, np.abs(y).max())).astype(np.float32)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="/home/claude/models_dl")
    ap.add_argument("--split", default="train", choices=["train", "test"])
    ap.add_argument("--n", type=int, default=160)
    ap.add_argument("--shard", default="0/1")
    ap.add_argument("--channel", default="phone", choices=["phone", "near"],
                    help="phone: 8 kHz telephone band + room, SNR 10-30 dB (a real call on speaker). "
                         "near: full band + mild room, SNR 18-35 dB (audio played close to the phone in a quiet room)")
    ap.add_argument("--asr", default="stream", choices=["stream", "ic"],
                    help="stream: the streaming Hindi model; ic: AI4Bharat IndicConformer Hindi (VAD + sentence-by-sentence)")
    a = ap.parse_args()
    si, sk = (int(x) for x in a.shard.split("/"))
    tts, rec = engines(Path(a.models), 1 if sk > 1 else 2)
    if a.asr == "ic":
        rec = IcAsr(Path(a.models), 1 if sk > 1 else 2)
    rng = np.random.default_rng(23 + si + (100 if a.split == "test" else 0) + (1000 if a.channel == "near" else 0))
    tag = ("" if a.channel == "phone" else "_near") + ("" if a.asr == "stream" else "_ic")
    calls = [json.loads(l) for l in open(ROOT / f"data/synth/out/calls_{a.split}.jsonl", encoding="utf-8")]
    calls = [c for c in calls if c["lang"] == "hi" and c["segments"]]
    half = a.n // 2  # balanced: half scam, half benign
    calls = [c for c in calls if c["label"] == 1][:half] + [c for c in calls if c["label"] == 0][:half]
    calls = calls[si::sk]
    suffix = "" if sk == 1 else f".{si}"
    out = open(ROOT / f"data/synth/out/calls_{a.split}_asr_hi{tag}.jsonl{suffix}", "w", encoding="utf-8")
    for k, c in enumerate(calls):
        end = c["segments"][-1]["t1"] + 3
        audio = np.zeros(int(end * SR) + SR, dtype=np.float32)
        spans = []
        for s in c["segments"]:
            g = tts.generate(s["text"], sid=0, speed=float(rng.uniform(0.9, 1.15)))
            x = np.asarray(g.samples, dtype=np.float32)
            if g.sample_rate != SR:
                from scipy.signal import resample_poly
                x = resample_poly(x, SR, g.sample_rate).astype(np.float32)
            i = int(s["t0"] * SR)
            if i >= len(audio):
                break
            x = x[: len(audio) - i]
            audio[i:i + len(x)] += x
            spans.append((s["t0"], s["t0"] + len(x) / SR, s.get("stage")))
        if a.channel == "phone":
            y = speakerphone(audio, float(rng.uniform(10, 30)), rng) * float(rng.uniform(0.35, 1.0))
        else:
            y = near(audio, float(rng.uniform(18, 35)), rng) * float(rng.uniform(0.4, 1.0))
        finals = rec.decode(y.astype(np.float32)) if a.asr == "ic" else stream_asr(rec, y.astype(np.float32))[0]
        segs = []
        for t, text in finals:
            stage = None
            for t0, t1, st in spans:
                if t0 <= t <= t1 + 1.2:
                    stage = st
            segs.append({"t0": t, "t1": t, "text": text, "speaker": "mixed", "stage": stage if c["label"] == 1 else None})
        out.write(json.dumps({**c, "id": c["id"] + "-asr", "segments": segs, "split": a.split + "_asr"}, ensure_ascii=False) + "\n")
        out.flush()
        if k % 10 == 0:
            print(a.split, a.shard, k, "calls", flush=True)
    out.close()
    print("done", len(calls))


if __name__ == "__main__":
    main()
