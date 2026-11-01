# Speech models

| Language | Model | Format | Why |
|---|---|---|---|
| Hindi / Hinglish (default) | streaming Zipformer2-CTC, Devanagari characters (`mobilebytesensei/betterflow-hindi-streaming-ctc`, from `Vengadanathan/hindi-hinglish-zipformer-ctc-streaming`, Apache-2.0) | int8 ONNX, ~69 MB | runs on stock sherpa-onnx; handles Hinglish (writes English words in Devanagari) |
| English | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` | int8 ONNX | small and fast (host RTF ≈ 0.05) |
| VAD | Silero VAD | ONNX, 2 MB | skips decoding during silence |

**Not chosen:** the Hindi FastConformer streaming export named in the proposal. Its card states
that it needs a patched sherpa-onnx feature extractor (librosa mel) and cannot write Latin
script (12 WER points worse on code-switched speech).

**Upgrade path:** an Indian-English streaming Zipformer (Svarah-fine-tuned, about half the WER of
the LibriSpeech model on Indian accents), ASR adaptation on our own consented recordings, and
NPU export through ExecuTorch QNN/MediaTek backends (Phase 2).

* `make_asr_noisy_calls.py` creates real ASR transcripts of synthetic training calls, used by `models/classifiers/train_text.py`.
* `expand_lexicon_asr.py` is the acoustic-alias experiment. It is **not used**: isolated phrases produced fragmentary hypotheses (see report §7.3).
