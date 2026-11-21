# KAVACH freeze manifest

Written 2026-11-21 16:38 IST by scripts/freeze_manifest.sh. Keep this build, thresholds and models for the Phase 1 evidence calls. Scope updated 22 November: four to five documented live calls and demo video; the 20-call pilot is deferred to Phase 2. The hashes below remain unchanged.

## Source

| Item | Value |
|---|---|
| Commit | `ed541cede0d0567d8627fbd8841b13f742d77d38` |
| Uncommitted changes | none to the source (only dist downloads and this file) |

## APKs

| APK | SHA-256 | Version | Signing certificate SHA-256 |
|---|---|---|---|
| `kavach-debug.apk` (installed on the phone, pulled back and byte-identical) | `fd2e7069bb7907bd8edc0a81a823b0dcf888d7f3f318e5d73592f0bb15af25a6` | 0.2-phase1 (versionCode 2) | debug key, not checked |

## Phone

| Item | Value |
|---|---|
| Model | Xiaomi 2312DRA50I |
| SoC | QTI SM7435 |
| RAM | 7.1 GB |
| Android | 15 (SDK 35), build `Redmi/garnet_in/garnet:15/AQ3A.240912.001/OS2.0.207.0.VNRINXM:user/release-keys` |
| Installed app | 0.2-phase1 |

## Model files on the phone

| File | SHA-256 | Bytes |
|---|---|---|
| `asr-en-big/model.onnx` | `9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a` | 131652171 |
| `asr-en-big/tokens.txt` | `450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10` | 9953 |
| `asr-en/decoder.onnx` | `9da02b77cb08826756ec6a88635f35a40374e4164e7c6359121a9145958a6ceb` | 2092566 |
| `asr-en/encoder.onnx` | `32c98281c7bd8b63e3e142d007251b37f120572e8fdea9a4f5a79ce22b10ec4f` | 187823992 |
| `asr-en/joiner.onnx` | `831477d390e59a61f1b6a6f763b9903e6c6366ff6034f1ddba613be82637122f` | 259335 |
| `asr-en/tokens.txt` | `49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb` | 5048 |
| `asr-hi-ic/model.onnx` | `0782be8f731c97cb212d5416390e5c8129afec56ae0b2a785cb641cb13d12575` | 197677836 |
| `asr-hi-ic/tokens.txt` | `ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2` | 67605 |
| `asr-hi/model.int8.onnx` | `e5758a0c0549063edf02fafc8af1be19d42715eb816d1b8f4d45e8de0fa6d039` | 68954634 |
| `asr-hi/tokens.txt` | `800fa1703a555fe52fbd8271f1b3764c5911a94cd16e3b618333118aadd75771` | 659 |
| `llama/kavach_llama3_2_1b.pte` | `564b082f9ce920b6ee7f824b58496fa29f77042ae8c862315988762ada659f23` | 860434816 |
| `llama/tokenizer.model` | `82e9d31979e92ab929cd544440f129d9ecd797b69e327f80f17e1c50d5551b55` | 2183982 |
| `silero_vad.onnx` | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` | 643854 |

## Runtimes

sherpa-onnx 1.13.8 (speech) and executorch-android 1.4.0 with the XNNPACK delegate (Llama), from `android/gradle.properties`; Llama exported with executorch==1.4.0 in `notebooks/kavach_llama_lora_colab.ipynb`.
