# Data card

## Synthetic call transcripts (`data/synth/generate_calls.py`)
* **What:** timestamped, ASR-style transcripts of 11 scam types (digital arrest, courier
  parcel, TRAI/SIM, fake KYC, remote-support takeover, UPI collect/refund, family emergency,
  electricity bill, investment group, task job, lottery) and 9 genuine scenarios chosen as
  hard negatives (bank fraud alerts, delivery agents asking for the delivery OTP, relatives asking
  for money, people discussing scams, genuine police, insurance/loan offers, doctor, office).
* **How:** 41 utterance pools, each in English, romanised Hinglish and Devanagari Hindi,
  with English loan words in Devanagari as a Hindi ASR writes them. The generator adds slot
  filling, ASR corruption (dropped and duplicated words, character slips, matra drops,
  Hinglish spelling variants), code-switching and realistic timing.
* **Splits:** every pool is partitioned by item into train / dev / test (template-disjoint).
  8,000 / 600 / 1,000 calls, about 50 % scam.
* **Limits:** written by the team, so the vocabulary and style are narrower than real callers;
  the label "scam starts" is attached to stage-annotated caller utterances.

## Hand-written calls (`data/dev/handwritten_calls.txt`, 30) and locked held-out calls (`data/dev/heldout_calls.txt`, 20)
Free-form calls written in a different style. The hand-written set was used once for error
analysis and is therefore a *second development set*. The held-out set was written before any
tuning against it and is used for reporting only.

## ASR-noisy calls (`models/asr/make_asr_noisy_calls.py`)
88 English training calls synthesised with Piper TTS, passed through a speaker-phone
channel (8 kHz band-pass, reverb, noise at 8–30 dB SNR) and transcribed by the Guardian's
streaming ASR. Stage labels are aligned by time. Train only.

## SMS
UCI SMS Spam Collection (5,572 messages, CC BY 4.0, split 70/10/20 by line) plus 4,300
synthetic Indian messages (KYC/PAN block, electricity, e-challan APK, parcel fee, refunds, jobs,
loans, lottery, cyber-police threats vs genuine OTP, debit/credit alerts, delivery, bills, family
texts), template-disjoint splits. UCI "spam" includes plain promotions; we treat it as risky.

## URLs
40k sampled active phishing domains (Phishing.Database, MIT), top-10k popular domains, the
genuine Indian domain list ×15 with typical paths, `*.bank.in` examples, and 8k generated
look-alikes of Indian brands. Split by registrable domain.

## LoRA pairs (`models/llama/make_sft_data.py`)
4,000 train / 400 dev (system + user prompt → JSON verdict), built from the call splits with
exactly the phone's prompt code. Targets: label, scam type, latest stage, verbatim cue quotes,
advice from the vetted playbook.

## Privacy
No real victim audio, transcripts or personal data are used or committed. Human recordings
follow `demo/recording_protocol.md` (consent, role-play only, never committed).
