"""Noisy-room benchmark: does a speech denoiser (GTCRN) and/or scam-word biasing (hotwords) help?

Same pipeline as bench/e2e_audio.py (Piper TTS call -> phone on speaker in a room -> streaming
sherpa-onnx ASR -> Guardian), with three kinds of room noise at 30 / 15 / 8 / 3 dB SNR:
  brown   fan / traffic hum (the e2e_audio.py noise)
  tv      a TV in the room: a different (Hindi) voice reading news + background music
  babble  several people talking at once (other English sentences, varied speed)
and four ASR front-ends:
  base       greedy search (what the Guardian shipped with)
  denoise    GTCRN streaming speech enhancement (sherpa-onnx OnlineSpeechDenoiser) before ASR
  hotwords   modified beam search + scam-vocabulary biasing (models/asr/hotwords_en.txt)
  both       denoise + hotwords
Metrics per condition: WER, whether the scam calls still reach STOP and the genuine calls stay
below STOP, time to STOP, ASR real-time factor (host). Also checks the placement-check SNR
estimator (kavach_core/audio_check.py) against the true SNR.
English only (the Hindi streaming model is CTC: hotwords are not available for it).

  python bench/e2e_noise.py --models /home/claude/models_dl     -> bench/results/e2e_noise.json
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "bench"))
from e2e_audio import CALLS, SR, stream_asr, synth_call, tts_engine, wer  # noqa: E402
from kavach_core.audio_check import estimate_snr_db  # noqa: E402
from kavach_core.features import LinearModel  # noqa: E402
from kavach_core.guardian import Guardian  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.normalize import tokens  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402

ASR_MODELS = {  # name -> (dir, encoder, decoder, joiner)
    "zipformer-en-20M": ("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17", "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx"),
    "zipformer-en-2023-06-21": ("sherpa-onnx-streaming-zipformer-en-2023-06-21", "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx"),
    "nemo-fastconformer-en-80ms": ("sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms", "encoder.onnx", "decoder.onnx", "joiner.onnx"),
}
BABBLE_TEXT = ["The weather will stay pleasant for the rest of the week with light showers.",
               "My sister is coming home tomorrow and we are going to the market in the evening.",
               "The match was cancelled because the ground was too wet after the rain.",
               "Please pass me the salt and the pickle, the food is really good today.",
               "He said the new restaurant near the station serves the best biryani in town.",
               "We should leave early on Sunday, the traffic on the highway is terrible."]
TV_TEXT = ["आज देश भर में मौसम साफ़ रहेगा और कई राज्यों में तापमान बढ़ेगा।",
           "शेयर बाज़ार में आज तेज़ी देखी गई, निवेशकों ने राहत की साँस ली।",
           "क्रिकेट मैच में भारत ने शानदार जीत दर्ज की और सीरीज़ अपने नाम की।",
           "सरकार ने किसानों के लिए नई योजना की घोषणा की है।"]


def phone_channel(x, rng):
    from scipy.signal import butter, lfilter, resample_poly

    nb = resample_poly(x, 1, 2)
    b, a = butter(4, [300 / 4000, 3400 / 4000], btype="band")
    y = resample_poly(lfilter(b, a, nb), 2, 1)[: len(x)].astype(np.float32)
    return room(y, rng)


def room(y, rng, rt=0.05):
    ir_len = int(0.25 * SR)
    ir = rng.standard_normal(ir_len) * np.exp(-np.arange(ir_len) / (rt * SR))
    ir[0] = 1.0
    return np.convolve(y, ir / np.abs(ir).sum() * 4)[: len(y)].astype(np.float32)


def tts_line(tts, text, speed=1.0):
    from scipy.signal import resample_poly

    g = tts.generate(text, sid=0, speed=1.0)
    a = np.asarray(g.samples, dtype=np.float32)
    if g.sample_rate != SR:
        a = resample_poly(a, SR, g.sample_rate).astype(np.float32)
    if speed != 1.0:  # crude speed/pitch change so babble voices differ
        up, down = int(round(100 / speed)), 100
        a = resample_poly(a, up, down).astype(np.float32)
    return a


def make_noise(kind, n, rng, tts_en, tts_hi):
    from scipy.signal import lfilter

    if kind == "brown":
        return lfilter([1.0], [1.0, -0.95], rng.standard_normal(n)).astype(np.float32)
    out = np.zeros(n, dtype=np.float32)
    if kind == "babble":
        lines = [tts_line(tts_en, t, s) for t, s in zip(BABBLE_TEXT, [0.9, 1.1, 1.0, 1.15, 0.95, 1.05])]
        for v in range(4):
            pos = int(rng.uniform(0, SR))
            while pos < n:
                a = lines[rng.integers(len(lines))]
                k = min(len(a), n - pos)
                out[pos:pos + k] += a[:k] * rng.uniform(0.6, 1.0)
                pos += k + int(rng.uniform(0.1, 0.6) * SR)
    elif kind == "tv":
        lines = [tts_line(tts_hi, t) for t in TV_TEXT]
        pos = 0
        while pos < n:
            a = lines[rng.integers(len(lines))]
            k = min(len(a), n - pos)
            out[pos:pos + k] += a[:k]
            pos += k + int(0.3 * SR)
        t = np.arange(n) / SR
        music = sum(np.sin(2 * np.pi * f * t) for f in (220.0, 277.2, 329.6)) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.5 * t))
        out += 0.08 * music.astype(np.float32) * np.abs(out).max()
    return room(out, rng, rt=0.08)


def mix(speech, noise, snr_db):
    act = np.abs(speech) > 1e-4
    p_sig = np.mean(speech[act] ** 2) if act.any() else 1e-6
    noise = noise * np.sqrt(p_sig / (10 ** (snr_db / 10)) / np.mean(noise ** 2))
    y = speech + noise
    return (0.7 * y / max(1e-6, np.abs(y).max())).astype(np.float32)


def recognizer(models: Path, hotwords: bool, asr: str = "zipformer-en-20M"):
    import sherpa_onnx

    dn, enc, dec, joi = ASR_MODELS[asr]
    d = models / dn
    kw = dict(tokens=str(d / "tokens.txt"), encoder=str(d / enc), decoder=str(d / dec), joiner=str(d / joi),
              num_threads=2, sample_rate=SR, feature_dim=80, enable_endpoint_detection=True,
              rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8, rule3_min_utterance_length=12.0)
    if hotwords:
        kw.update(decoding_method="modified_beam_search", max_active_paths=4,
                  hotwords_file=str(ROOT / "models/asr/hotwords_en.txt"), hotwords_score=1.5,
                  modeling_unit="bpe", bpe_vocab=str(ROOT / "models/asr/bpe_vocab_en20m.txt"))
    else:
        kw.update(decoding_method="greedy_search")
    return sherpa_onnx.OnlineRecognizer.from_transducer(**kw)


def denoiser(models: Path):
    import sherpa_onnx

    cfg = sherpa_onnx.OnlineSpeechDenoiserConfig(model=sherpa_onnx.OfflineSpeechDenoiserModelConfig(
        gtcrn=sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(model=str(models / "gtcrn_simple.onnx")), num_threads=1))
    return sherpa_onnx.OnlineSpeechDenoiser(cfg)


def denoise(dn, audio):
    dn.reset()
    out = []
    t0 = time.time()
    for i in range(0, len(audio), SR // 10):
        r = dn.run(audio[i:i + SR // 10], SR)
        out.append(np.asarray(r.samples, dtype=np.float32))
    out.append(np.asarray(dn.flush().samples, dtype=np.float32))
    y = np.concatenate(out)
    y = np.pad(y, (0, max(0, len(audio) - len(y))))[: len(audio)]
    return y, (time.time() - t0) / (len(audio) / SR)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="/home/claude/models_dl")
    ap.add_argument("--snr", type=float, nargs="+", default=[30.0, 15.0, 8.0, 3.0])
    ap.add_argument("--noise", nargs="+", default=["brown", "tv", "babble"])
    ap.add_argument("--asr", default="zipformer-en-20M", choices=list(ASR_MODELS))
    ap.add_argument("--configs", nargs="+", default=["base", "denoise", "hotwords", "both"])
    ap.add_argument("--out", default="bench/results/e2e_noise.json")
    a = ap.parse_args()
    models = Path(a.models)
    import sherpa_onnx

    tts_en = tts_engine(models)
    dh = models / "vits-piper-hi_IN-pratham-medium"
    tts_hi = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(next(dh.glob("*.onnx"))), tokens=str(dh / "tokens.txt"),
                                                   data_dir=str(dh / "espeak-ng-data")), num_threads=2)))
    recs = {False: recognizer(models, False, a.asr)}
    if any(c in ("hotwords", "both") for c in a.configs):
        recs[True] = recognizer(models, True, a.asr)
    dn = denoiser(models)
    g = Guardian(Lexicon(), LinearModel.load(ROOT / "assets/models", "cue_model"), Playbook())
    rng = np.random.default_rng(1)
    rows = []
    for name, lines in CALLS.items():
        speech = phone_channel(synth_call(tts_en, lines), rng)
        scam = "genuine" not in name and "delivery" not in name
        for kind in a.noise:
            noise = make_noise(kind, len(speech), rng, tts_en, tts_hi)
            for snr in a.snr:
                audio = mix(speech, noise, snr)
                est = estimate_snr_db(audio, SR)
                den, rtf_dn = denoise(dn, audio) if any(c in ("denoise", "both") for c in a.configs) else (audio, 0.0)
                for cfg in a.configs:
                    x = den if cfg in ("denoise", "both") else audio
                    finals, rtf = stream_asr(recs[cfg in ("hotwords", "both")], x)
                    hist = g.run_transcript(finals)
                    ref = tokens(" ".join(t for _, t in lines))
                    hyp = tokens(" ".join(t for _, t in finals))
                    t_s = next((s.t for s in hist if s.level == "stop"), None)
                    ok = (g.level == "stop") if scam else (g.level != "stop")
                    rows.append({"asr": a.asr, "call": name, "scam": scam, "noise": kind, "snr_db": snr, "cfg": cfg, "wer": round(wer(ref, hyp), 3),
                                 "level": g.level, "correct": ok, "t_stop": t_s, "asr_rtf": round(rtf, 3),
                                 "denoise_rtf": round(rtf_dn, 3) if cfg in ("denoise", "both") else 0.0, "snr_estimate_db": round(est, 1)})
                    print(f"{name:18s} {kind:6s} {snr:4.0f}dB {cfg:8s} WER {rows[-1]['wer']:.2f} {g.level:7s} {'ok' if ok else 'WRONG'}"
                          f"  est {est:5.1f}dB", flush=True)
    # summary
    summ = {}
    for cfg in a.configs:
        for kind in a.noise + ["all"]:
            for snr in a.snr + ["all"]:
                rs = [r for r in rows if r["cfg"] == cfg and (kind == "all" or r["noise"] == kind) and (snr == "all" or r["snr_db"] == snr)]
                if rs:
                    summ[f"{cfg}|{kind}|{snr}"] = {"wer": round(float(np.mean([r["wer"] for r in rs])), 3),
                                                  "correct": f"{sum(r['correct'] for r in rs)}/{len(rs)}"}
    est = [(r["snr_db"], r["snr_estimate_db"]) for r in rows if r["cfg"] == "base"]
    res = {"asr": a.asr, "rows": rows, "summary": summ,
           "snr_estimator": {str(s): round(float(np.median([e for t, e in est if t == s])), 1) for s in a.snr},
           "asr_rtf_host_median": round(float(np.median([r["asr_rtf"] for r in rows])), 3)}
    if any(r["cfg"] == "denoise" for r in rows):
        res["denoise_rtf_host_median"] = round(float(np.median([r["denoise_rtf"] for r in rows if r["cfg"] == "denoise"])), 3)
    (ROOT / a.out).write_text(json.dumps(res, indent=1), encoding="utf-8")
    for k, v in summ.items():
        if k.endswith("|all") or "|all|" in k:
            print(k, v)
    print("snr estimator (true -> median estimate):", res["snr_estimator"])


if __name__ == "__main__":
    main()
