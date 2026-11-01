"""End-to-end audio test: TTS call -> speaker-phone simulation -> streaming ASR -> Guardian.

Pipeline (host CPU, same components as the Android Guardian):
  1. synthesise each line of a scripted call with an offline Piper voice (sherpa-onnx TTS)
     and place it at its timestamp;
  2. simulate a phone on speaker in a room: 8 kHz narrow-band telephone channel
     (300-3400 Hz), short room reverb, background noise at a chosen SNR;
  3. stream 100 ms chunks into sherpa-onnx OnlineRecognizer with the Guardian's endpoint
     rules; every final segment goes to the Guardian engine (event-driven ticks);
  4. report WER against the script, time-to-CAUTION / STOP, and the ASR real-time factor.

English only here (the Hindi streaming ASR model is hosted on Hugging Face, which this
build machine cannot reach); scripts/push_models.sh installs the Hindi model on the phone
and bench/README.md describes the same test on device with real recorded calls.

Usage: python bench/e2e_audio.py --models <dir with asr/tts models> [--snr 15]
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
from kavach_core.features import LinearModel  # noqa: E402
from kavach_core.guardian import Guardian  # noqa: E402
from kavach_core.lexicon import Lexicon  # noqa: E402
from kavach_core.normalize import tokens  # noqa: E402
from kavach_core.retrieval import Playbook  # noqa: E402

SR = 16000
CALLS = {
    "digital_arrest_en": [
        (4, "Hello, am I speaking with Mister Verma?"),
        (9, "I am Inspector Vikram Singh from the Mumbai cyber crime branch."),
        (16, "A parcel booked on your Aadhaar card contains drugs and fake passports."),
        (24, "Your name has come up in a money laundering case. An arrest warrant has been issued."),
        (33, "Do not disconnect this call and do not tell anyone, not even your family."),
        (41, "Transfer your savings to the RBI safe account for verification. It will be returned."),
    ],
    "kyc_bank_en": [(5, "Hello sir, good afternoon."), (9, "I am calling from the SBI head office, KYC department."),
                    (15, "Your account will be blocked within two hours because your KYC is not updated."),
                    (23, "I have sent you a link by SMS. Please click on it and fill the form."),
                    (31, "You will now receive an OTP. Please tell me the OTP for verification.")],
    "genuine_bank_en": [(5, "Good evening, this is the fraud monitoring team of HDFC bank."),
                        (11, "We noticed a card transaction of eight thousand rupees at an online store. Was this done by you?"),
                        (19, "No problem, I have blocked the card for your safety, and a new card will be sent."),
                        (27, "Please remember, never share your OTP or PIN with anyone. The bank will never ask for it."),
                        (35, "Thank you, have a good day.")],
    "delivery_en": [(4, "Hello, I am the delivery boy from Amazon. I am outside your building."),
                    (10, "Which floor are you on? I have your parcel, cash on delivery six hundred rupees."),
                    (17, "Please tell me the delivery OTP shown in your app so I can mark it delivered.")],
}


def tts_engine(models: Path):
    import sherpa_onnx

    d = models / "vits-piper-en_US-amy-low"
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(d / "en_US-amy-low.onnx"), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
        num_threads=2))
    return sherpa_onnx.OfflineTts(cfg)


def synth_call(tts, lines) -> np.ndarray:
    end = lines[-1][0] + 12
    out = np.zeros(int(end * SR), dtype=np.float32)
    for t, text in lines:
        a = tts.generate(text, sid=0, speed=1.0)
        s = np.asarray(a.samples, dtype=np.float32)
        if a.sample_rate != SR:
            s = np.interp(np.arange(0, len(s), a.sample_rate / SR), np.arange(len(s)), s).astype(np.float32)
        i = int(t * SR)
        out[i:i + len(s)] += s[: max(0, len(out) - i)]
    return out


def speakerphone(x: np.ndarray, snr_db: float, rng: np.random.Generator) -> np.ndarray:
    from scipy.signal import butter, lfilter, resample_poly

    nb = resample_poly(x, 1, 2)  # 8 kHz telephone channel
    b, a = butter(4, [300 / 4000, 3400 / 4000], btype="band")
    nb = lfilter(b, a, nb)
    y = resample_poly(nb, 2, 1)[: len(x)].astype(np.float32)
    ir_len = int(0.25 * SR)  # small room, RT60 ~ 0.3 s
    ir = rng.standard_normal(ir_len) * np.exp(-np.arange(ir_len) / (0.05 * SR))
    ir[0] = 1.0
    y = np.convolve(y, ir / np.abs(ir).sum() * 4)[: len(x)]
    noise = rng.standard_normal(len(y))
    noise = lfilter([1.0], [1.0, -0.95], noise)  # brownish room noise
    p_sig = np.mean(y[np.abs(y) > 1e-4] ** 2) if np.any(np.abs(y) > 1e-4) else 1e-6
    noise *= np.sqrt(p_sig / (10 ** (snr_db / 10)) / np.mean(noise ** 2))
    y = y + noise
    return (0.7 * y / max(1e-6, np.abs(y).max())).astype(np.float32)


def recognizer(models: Path):
    import sherpa_onnx

    d = models / "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(d / "tokens.txt"), encoder=str(d / "encoder-epoch-99-avg-1.int8.onnx"),
        decoder=str(d / "decoder-epoch-99-avg-1.onnx"), joiner=str(d / "joiner-epoch-99-avg-1.int8.onnx"),
        num_threads=2, sample_rate=SR, feature_dim=80, enable_endpoint_detection=True,
        rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8, rule3_min_utterance_length=12.0, decoding_method="greedy_search")


def stream_asr(rec, audio: np.ndarray) -> tuple[list[tuple[float, str]], float]:
    s = rec.create_stream()
    finals, chunk = [], SR // 10
    t0 = time.time()
    for i in range(0, len(audio), chunk):
        s.accept_waveform(SR, audio[i:i + chunk])
        while rec.is_ready(s):
            rec.decode_stream(s)
        if rec.is_endpoint(s):
            txt = rec.get_result(s).strip()
            if txt:
                finals.append((round((i + chunk) / SR, 2), txt.lower()))
            rec.reset(s)
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    txt = rec.get_result(s).strip()
    if txt:
        finals.append((round(len(audio) / SR, 2), txt.lower()))
    return finals, (time.time() - t0) / (len(audio) / SR)


def wer(ref: list[str], hyp: list[str]) -> float:
    d = np.zeros((len(ref) + 1, len(hyp) + 1), dtype=int)
    d[:, 0] = range(len(ref) + 1)
    d[0, :] = range(len(hyp) + 1)
    for i in range(1, len(ref) + 1):
        for j in range(1, len(hyp) + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + (ref[i - 1] != hyp[j - 1]))
    return d[len(ref), len(hyp)] / max(1, len(ref))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="/home/claude/models_dl")
    ap.add_argument("--snr", type=float, nargs="+", default=[30.0, 15.0, 8.0])
    ap.add_argument("--save-wav", action="store_true")
    a = ap.parse_args()
    models = Path(a.models)
    tts, rec = tts_engine(models), recognizer(models)
    g = Guardian(Lexicon(), LinearModel.load(ROOT / "assets/models", "cue_model"), Playbook())
    rng = np.random.default_rng(0)
    results = []
    for name, lines in CALLS.items():
        clean = synth_call(tts, lines)
        for snr in a.snr:
            audio = speakerphone(clean, snr, rng)
            if a.save_wav:
                import wave
                p = ROOT / f"bench/device/{name}_snr{int(snr)}.wav"
                p.parent.mkdir(parents=True, exist_ok=True)
                with wave.open(str(p), "wb") as w:
                    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
                    w.writeframes((audio * 32767).astype(np.int16).tobytes())
            finals, rtf = stream_asr(rec, audio)
            hist = g.run_transcript(finals)
            ref = tokens(" ".join(t for _, t in lines))
            hyp = tokens(" ".join(t for _, t in finals))
            t_c = next((s.t for s in hist if s.level in ("caution", "stop")), None)
            t_s = next((s.t for s in hist if s.level == "stop"), None)
            r = {"call": name, "snr_db": snr, "wer": round(wer(ref, hyp), 3), "asr_rtf_host": round(rtf, 3), "final_level": g.level,
                 "rule": g.rule, "t_caution": t_c, "t_stop": t_s, "money_line_t": lines[-1][0] if "genuine" not in name and "delivery" not in name else None,
                 "transcript": " | ".join(t for _, t in finals)}
            results.append(r)
            print(f"{name:18s} SNR {snr:4.0f} dB  WER {r['wer']:.2f}  RTF {rtf:.3f}  -> {g.level:7s} ({g.rule}) caution@{t_c} stop@{t_s}")
    out = ROOT / "bench/results/e2e_audio_en.json"
    out.write_text(json.dumps(results, indent=2))
    print("wrote", out)


if __name__ == "__main__":
    main()
