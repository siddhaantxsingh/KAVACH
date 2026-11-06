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

# ---- Network Watch (models/netwatch/) -------------------------------------------------
# DGA domains, Cucchiarelli et al. 2021 (ESWA 170:114551), from the Netlab 360 open data feed.
# Research use; cite the paper.
curl -fsSL -o raw/dga_domains_full.csv \
  https://raw.githubusercontent.com/chrmor/DGA_domains_dataset/master/dga_domains_full.csv
# Independent popular-domain list, false-alarm test only.
curl -fsSL -o raw/top-100000-domains \
  https://raw.githubusercontent.com/zer0h/top-1000000-domains/master/top-100000-domains
# UNSW-NB15 (Moustafa & Slay 2015, UNSW Canberra), official train/test partition CSVs.
# Free for academic research; cite the paper. GitHub mirror (row counts match the official files).
curl -fsSL -o raw/UNSW_NB15_training-set.csv \
  https://raw.githubusercontent.com/Nir-J/ML-Projects/master/UNSW-Network_Packet_Classification/UNSW_NB15_training-set.csv
curl -fsSL -o raw/UNSW_NB15_testing-set.csv \
  https://raw.githubusercontent.com/Nir-J/ML-Projects/master/UNSW-Network_Packet_Classification/UNSW_NB15_testing-set.csv
# NSL-KDD (Tavallaee et al. 2009, UNB CIC). Free for research; cite the paper.
curl -fsSL -o raw/KDDTrain+.txt https://raw.githubusercontent.com/jmnwong/NSL-KDD-Dataset/master/KDDTrain+.txt
curl -fsSL -o raw/KDDTest+.txt https://raw.githubusercontent.com/jmnwong/NSL-KDD-Dataset/master/KDDTest+.txt
#   * CICIDS2017 (UNB CIC): no mirror reachable from our build machine; download from
#     https://www.unb.ca/cic/datasets/ids-2017.html (registration) if you want to extend the eval.
# models/classifiers/train_text.py picks these up automatically if present.

wc -l raw/* | sed 's/^/  /'
