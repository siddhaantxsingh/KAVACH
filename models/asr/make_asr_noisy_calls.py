"""Turn synthetic *training* calls into real ASR transcripts (train-on-noisy-transcripts).

Each English training call is synthesised segment by segment at its timestamps (offline
Piper TTS), passed through the speaker-phone channel at a random SNR (8-30 dB) and decoded
by the Guardian's streaming ASR. Every ASR final segment inherits the stage label of the
original segment that was being spoken when it ended, so the cue classifier can be trained
on what the phone will really see. Only the train split is used; dev/test stay text-only
and the end-to-end audio test (bench/e2e_audio.py) uses separate scripts.

Usage: python models/asr/make_asr_noisy_calls.py --n 300
Output: data/synth/out/calls_train_asr.jsonl
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bench"))
from e2e_audio import SR, recognizer, speakerphone, stream_asr, tts_engine  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="/home/claude/models_dl")
    ap.add_argument("--n", type=int, default=300)
    a = ap.parse_args()
    models = Path(a.models)
    tts, rec = tts_engine(models), recognizer(models)
    rng = np.random.default_rng(11)
    calls = [json.loads(l) for l in open(ROOT / "data/synth/out/calls_train.jsonl", encoding="utf-8")]
    calls = [c for c in calls if c["lang"] == "en" and c["segments"]][: a.n]
    out = open(ROOT / "data/synth/out/calls_train_asr.jsonl", "w", encoding="utf-8")
    for k, c in enumerate(calls):
        end = c["segments"][-1]["t1"] + 3
        audio = np.zeros(int(end * SR) + SR, dtype=np.float32)
        spans = []
        for s in c["segments"]:
            g = tts.generate(s["text"], sid=0, speed=float(rng.uniform(0.9, 1.15)))
            x = np.asarray(g.samples, dtype=np.float32)
            i = int(s["t0"] * SR)
            if i >= len(audio):
                break
            x = x[: len(audio) - i]
            audio[i:i + len(x)] += x
            spans.append((s["t0"], s["t0"] + len(x) / SR, s.get("stage")))
        finals, _ = stream_asr(rec, speakerphone(audio, float(rng.uniform(8, 30)), rng))
        segs = []
        for t, text in finals:
            stage = None
            for t0, t1, st in spans:
                if t0 <= t <= t1 + 1.2:
                    stage = st
            segs.append({"t0": t, "t1": t, "text": text, "speaker": "mixed", "stage": stage if c["label"] == 1 else None})
        out.write(json.dumps({**c, "id": c["id"] + "-asr", "segments": segs, "split": "train_asr"}, ensure_ascii=False) + "\n")
        if k % 25 == 0:
            print(k, "calls", flush=True)
    out.close()
    print("done", len(calls))


if __name__ == "__main__":
    main()
