# Third-party components and data

| Component | Licence | How it is used |
|---|---|---|
| ExecuTorch (PyTorch) + XNNPACK + KleidiAI | BSD-3-Clause / Apache-2.0 | Android runtime via Maven `org.pytorch:executorch-android`; Python export tools |
| sherpa-onnx (k2-fsa) + onnxruntime | Apache-2.0 / MIT | Android AAR downloaded by `scripts/fetch_android_deps.sh`; Python package for host tests |
| Streaming Zipformer English 20M (k2-fsa) | Apache-2.0 | downloaded by `scripts/push_models.sh` |
| Hindi/Hinglish streaming Zipformer2-CTC (betterflow mirror of Vengadanathan/hindi-hinglish-zipformer-ctc-streaming) | Apache-2.0 | downloaded by `scripts/push_models.sh` |
| Silero VAD | MIT | downloaded by `scripts/push_models.sh` |
| Piper voices (en_US-amy-low, hi_IN-pratham-medium) via sherpa-onnx | see each MODEL_CARD | host-side test audio only, not shipped |
| Llama 3.2 1B Instruct (Meta) | Llama 3.2 Community License | fine-tuned and exported by the team; **weights are not in this repository** |
| UCI SMS Spam Collection (Almeida & Hidalgo) | CC BY 4.0 | training data, downloaded by `data/public/download.sh`, not redistributed |
| Phishing.Database (mitchellkrogza) | MIT | training data, downloaded, not redistributed |
| top-1000000-domains (zer0h) | see repository | benign URL list, downloaded, not redistributed |
| Noto Sans / Noto Sans Devanagari | OFL-1.1 | report typesetting |
| Gradle wrapper | Apache-2.0 | build |

Scam playbook texts are written by the team from public advisories (I4C/MHA, RBI, cybercrime.gov.in, Sanchar Saathi, bank awareness pages).

* **AI4Bharat IndicConformer Hindi** (`ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large`), MIT licence; CTC ONNX export by
  trysem/sulabhkatiyar (CC-BY-4.0); sherpa-onnx metadata and int8 quantisation by ngiftsun
  (`ngiftsun/indicconformer-hi-int8-sherpa`). Downloaded to the phone by `scripts/push_models.sh`; not stored in this repository.
