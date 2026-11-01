# Recording protocol: human-recorded evaluation set

Goal: **100+ scam and 100+ genuine** calls in Hindi, Hinglish and English, recorded **on
speaker-phone in real rooms**, held out from day one and used only for the final report.

1. **Consent first**: every speaker signs `consent_form.md`. Use role-play only; never record real victims or real scam calls.
2. **Setup**: two phones on a real call; the "victim" phone on speaker at 50–80 cm from the Guardian phone.
   Vary rooms (bedroom, living room with TV, office, outdoors), distance, speaker volume and phone models.
3. **Speakers**: at least 8 different speakers, mixed gender and age; elderly volunteers where possible.
4. **Content**: use `roleplay_scripts.md` as an outline, not a script; let speakers improvise. Each scam type ≥ 6 calls,
   each genuine scenario ≥ 8 calls. Mark the time of the first OTP/PIN/payment/remote-access request.
5. **Capture**: the KAVACH apps never store audio, by design. Record with a separate recorder app on the
   Guardian phone (16 kHz mono WAV), placed exactly where the Guardian would sit.
6. **Labels** (`data/recorded/labels.csv`, not committed): file, label, type, language, speaker ids, room,
   SNR estimate, money_t (seconds), notes. Transcribe 30 calls by hand for WER.
7. **Split**: 30 % for ASR adaptation/dev, 70 % **locked** test, split by speaker (no speaker in both).
8. **Storage**: on the team drive only; never commit audio to git (`.gitignore` covers `*.wav`).
9. **Evaluation**: `python bench/eval_recorded.py --dir data/recorded --lang hi` runs the files through the same ASR + engine and reports
   per-scam-type recall, false STOP, time-to-warning, WER.
