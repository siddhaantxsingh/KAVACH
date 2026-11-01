# Classifiers

* `train_text.py` trains `cue_model` (call windows) and `sms_model`; writes `assets/models/*.bin|json` and `bench/results/text_models.json`.
* `train_url.py` trains `url_model` and evaluates the full Link Check; writes `bench/results/url_model.json`.
* Feature hashing and inference are identical in Python (`kavach_core/features.py`) and Kotlin (`android/core/.../Features.kt`); after retraining run `python tests/make_parity_vectors.py` and the Kotlin parity test.

**Phase 2:** App Inspector gradient-boosted model on public Android malware features
(e.g. CICMalDroid / AndroZoo manifests), keeping v0's rule reasons as explanations; Network
Watch DGA char-CNN + beacon periodicity from local-VPN metadata (CICIDS2017 / UNSW-NB15
pre-training).
