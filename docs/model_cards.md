# Model cards

## cue_model — call-transcript window → scam probability
* Hashed linear model (logistic regression, L2, C = 0.1 chosen on the template-disjoint dev
  split). 2^18 FNV-1a features: words, word pairs, 3–5 letter fragments, cue-category hits
  and category pairs. 1 MB float32. Threshold for window-level F1: 0.65; the Guardian uses
  p ≥ 0.8 on two consecutive ticks.
* Training: 8,000 synthetic calls (one window per ASR segment, last 120 tokens) + 88 ASR-noisy calls ×3.
* Test (unseen templates): F1 0.903, ROC-AUC 0.953. Weakest on Hindi: call-level STOP recall
  is 80 % on Hindi calls vs 96 % on English calls.
* Not for: deciding alone. It is one input to the kill-chain and never the only STOP signal on weak rules.

## sms_model — message text → scam probability
* Same architecture, C = 4.0. UCI + synthetic Indian SMS. Test F1 0.943 (precision 0.994,
  recall 0.897). Genuine OTP and debit-alert formats are explicit hard negatives.

## url_model + Link Check rules
* Hashed character-fragment linear model over host and path pieces; the model-only threshold
  of 0.74 is set for ≤ 3 % false alarms on popular domains. Deterministic rules run first.
* Rules + model on the test split: precision 0.987, recall 0.752; Indian look-alikes 98.5 %,
  genuine Indian domains 0 % flagged.
* Known gap: generic global phishing domains with no brand cue (69 % caught).

## Llama 3.2 1B Instruct + KAVACH LoRA (advisor)
* Role: a JSON verdict and an explanation on escalation. May raise CAUTION → STOP; never lowers STOP.
* Fine-tuning: LoRA r = 16 on all projections, 4k pairs (`models/llama/`). Export: 8da4w g32 + 4-bit embeddings, XNNPACK.
* Safety: the validator drops quotes that are not in the transcript; the fallback text comes from
  the vetted playbook, not from the model.
* Status: pipeline verified on a tiny model; fine-tuned-model quality and speed are Phase 2 measurements.

## Streaming ASR
* Hindi/Hinglish: Zipformer2-CTC, about 69 MB int8, Devanagari characters. The upstream card reports
  25.7 % WER on IndicVoices-hi and 39.9 % on Hinglish calls. Our speaker-phone WER is to be measured.
* English: Zipformer transducer 20M int8. Host test on simulated speaker-phone audio:
  WER 0.27–0.61 depending on SNR; it mishears Indian proper nouns (RBI, KYC, Aadhaar).
