# Recorded-audio evaluation reference

Our completed Phase 1 tests used live calls from another phone; see [the results](README.md). This page describes the input format for the separate `bench/eval_recorded.py` audio-evaluation utility.

Use consented fictional role-play audio and keep private recordings outside the repository. KAVACH does not save call audio. Record the audio path and conditions with each sample so live calls and acoustic playback remain distinguishable.

Place evaluation WAV files and `labels.csv` under a local dataset directory. Labels include file, label, type, language, speaker IDs, room, SNR estimate, money_t and notes. A reference transcript supports word-error-rate scoring. Keep any train/dev/test partition speaker-disjoint.

```bash
python bench/eval_recorded.py --dir data/recorded --lang hi
```

The utility evaluates the supplied audio through ASR and the detector. Its output describes that supplied dataset, separately from the completed live-call results.
