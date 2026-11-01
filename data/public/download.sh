#!/usr/bin/env bash
# Download the public datasets used for training. Raw files go to data/public/raw/
# (git-ignored). Every source is listed in docs/data_card.md with its licence.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw

# UCI SMS Spam Collection (Almeida & Hidalgo, 2011), CC BY 4.0. Listed in the PS3 brief.
# GitHub mirror of the original TSV (5,572 messages, label \t text).
curl -fsSL -o raw/sms_spam_uci.tsv \
  https://raw.githubusercontent.com/justmarkham/pycon-2016-tutorial/master/data/sms.tsv

# Phishing.Database (mitchellkrogza), MIT: currently active phishing domains.
curl -fsSL -o raw/phishing-domains-ACTIVE.txt \
  https://raw.githubusercontent.com/mitchellkrogza/Phishing.Database/master/phishing-domains-ACTIVE.txt

# Popular-domain list used as benign URLs.
curl -fsSL -o raw/top-10000-domains.txt \
  https://raw.githubusercontent.com/zer0h/top-1000000-domains/master/top-10000-domains

# Optional extra sources (download manually; licences differ, not redistributed here):
#   * Hindi Spam SMS dataset, IEEE DataPort, DOI 10.21227/5y8x-n678  -> raw/hindi_sms.csv
#   * Hinglish & English financial scam text (Hugging Face: bolewara)  -> raw/hinglish_scam.csv
#   * PhishTank verified_online.csv (free API key)                    -> raw/phishtank.csv
#   * CICIDS2017 / UNSW-NB15 flows for Network Watch (Phase 2)
# models/classifiers/train_text.py picks these up automatically if present.

wc -l raw/* | sed 's/^/  /'
