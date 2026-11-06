# Network Watch: the third KAVACH pipeline

KAVACH has three on-device pipelines:

| Pipeline | Runs on | Looks at | Needs |
|---|---|---|---|
| **Guardian** | the phone that hears the call on speaker | the conversation (speech → text → cues → Llama) | microphone |
| **Shield** | the user's phone | SMS / WhatsApp text, links, new apps, screen sharing, payments, **calls (that a call is on, never its audio)** | notification + usage access, phone state |
| **Network Watch** | the user's phone | which app sends how much data, when; which websites apps look up | usage access (part A); optional VPN app (part B) |

All three feed one kill chain. A risky network event during a call from an unknown number is
STOP ("unknown caller + a suspicious data upload"); a network event plus a suspicious
conversation is STOP; a network event on its own is CAUTION.

Why it matters for PS3: a digital-arrest or "KYC update" scam often ends with the victim
installing a remote-control or "support" app, or a fake loan app that uploads their contacts and
photos. The conversation detector catches the talk; Network Watch catches what the installed app
then *does*, even after the call has ended.

## Part A: app traffic watch (built into Shield, no INTERNET permission)

`NetworkStatsManager` gives byte counters per app. Shield polls them every 30 s (also when the
screen is off, because spyware likes to upload at night) and feeds the per-app difference to
`NetWatch` (`kavach_core/netwatch.py`, Kotlin `kavach.core.NetWatch`, parity-tested).

Only **suspect apps** are judged: installed from outside an app store, installed in the last 7
days, remote-access apps, or apps that the app inspector already rates suspicious. Play-store apps
the user has had for a while are never flagged, so backups, WhatsApp and streaming stay quiet.
The user can mark a flagged app as trusted ("I know this app"); it is then ignored.

| Kind | Rule (defaults) |
|---|---|
| `suspicious_upload` | ≥ 5 MB sent in 5 min, ≥ 3× what was received, ≥ 80 % of it while the app was in the background; **or** ≥ 4 MB in the background over the last hour, ≥ 10× received, in ≥ 6 bursts (slow exfiltration) |
| `beaconing_app` | ≥ 8 small (≤ 64 KB) background bursts over ≥ 10 min at a steady rhythm (interval CV ≤ 0.25, period ≥ 45 s), no large transfers |

At most one alert per app and kind per hour.

**Measured** (`bench/netwatch_sim.py`, 200 simulated 2-hour traces per scenario at the real 30 s
polling granularity; synthetic, there is no labelled phone-traffic corpus):

| Scenario | Result | Median time to alert |
|---|---|---|
| Spyware uploads 8–40 MB of photos | detected 100 % | 30 s |
| Spyware uploads ~6 MB of SMS / contacts | detected 100 % | 90 s |
| Slow exfiltration, 400 KB every 5 min | detected 100 % | 45 min |
| Remote-control heartbeat every 60 s / 5 min | detected 100 % / 100 % | 10 min / 35 min |
| Heartbeat every 2 min with ±40 % jitter | detected 100 % | 15.5 min |
| Chat, sending a video, streaming, Play-store backup | 0 % false alarms | |
| Irregular background sync (3–20 min) by a suspect app | 0.5 % false alarms | |
| **Sideloaded** backup app uploading 60 MB / sideloaded app syncing exactly every 2 min | **100 % false alarms** (by design: indistinguishable from spyware by byte counts; this is what the "I know this app" button is for) | |

Limits: byte counts only (no destinations, no content); counter freshness depends on the phone's
Android build; a spyware app that is not sideloaded/new/suspicious is not judged.

## Part B: DNS watch (optional separate app `io.kavach.netwatch`)

A local DNS-only `VpnService`: only traffic to a private DNS address is routed into the app, every
other packet flows normally and is never seen. For each lookup it learns which app asked
(`getConnectionOwnerUid`) and the name, forwards the query unchanged to the phone's own DNS server
(never to a new resolver), and judges name + answer with `DnsWatch`:

* **bank / payment / government look-alike** (the link checker's deterministic rules: typo-squat,
  look-alike letters, brand on an unofficial domain) → `malicious_domain` at once;
* **≥ 3 different machine-generated names that do not exist (NXDOMAIN) from one app within 10 min**
  → `malicious_domain` (malware searching for its control server).

The link checker's *learned* score is deliberately not used here: it was trained on links in SMS
and calls ordinary background hosts (`app-measurement.com`, `firebaseinstallations.googleapis.com`)
"dangerous". Verdicts go to Shield over the same-phone signature-protected link.

**Trade-off, stated plainly:** this app needs the INTERNET permission (it must forward the lookups
it inspects), and Android shows the VPN key icon. That is why it is a separate, opt-in app; Shield
and Guardian still have no INTERNET permission. Bypassed by apps that use their own
DNS-over-HTTPS and by "Private DNS: strict"; IPv4 DNS only.

### Domain model

Hashed character n-gram linear model (same format as the other KAVACH models, 256 KB) over the
labels of the domain plus length / digits / entropy / consonant-run / vowel tokens.
Data: Cucchiarelli et al. 2021 (25 DGA families from Netlab 360, 13,500 each) + Alexa domains;
6 whole families held out. Threshold for 0.5 % false alarms on validation.

| Test | Result |
|---|---|
| Families seen in training (test split) | recall 61.8 % at 0.5 % false alarms; ≥ 99 % for emotet, murofet, ramdo, ranbyus, rovnix |
| **Families never seen**: cryptolocker / necurs | 92.2 % / 91.8 % |
| **Families never seen**: suppobox / matsnu / gozi / pushdo (dictionary-word or pronounceable DGAs) | ≈ 0–1 % (blind spot) |
| Independent popular-domain list (85,228 not in training) | 0.36 % false alarms |
| Genuine Indian bank / government domains | 0 flagged |

Because 0.36 % per name is too many for a phone that looks up hundreds of names a day, a
single DGA-looking name never alarms; the NXDOMAIN-burst rule above is required.
(Families such as corebot / symmi appear only in the test split because all their names share a
dynamic-DNS parent domain and the split is by domain.)

## Part C: flow model (research component, raises no alerts)

A 1,665-parameter MLP (`assets/models/flow_model.json`, ExecuTorch `flow_model.pte`, 11 KB) over
per-connection statistics a phone-side flow tracker can compute (duration, packets, bytes, rate,
mean packet size, protocol / state / service, counts of recent connections sharing the host or
service). TTL, TCP sequence numbers, window sizes and payload fields are excluded.

UNSW-NB15 official partitions (158,000 train / 17,341 validation / 82,332 test), threshold for 2 %
validation false alarms:

| Model | Precision | Recall | F1 | False alarms (test) | ROC-AUC |
|---|---|---|---|---|---|
| MLP, phone-observable features (shipped) | 0.939 | 0.885 | 0.911 | 7.1 % | 0.971 |
| Gradient-boosted trees, same features | 0.920 | 0.931 | 0.926 | 9.9 % | 0.980 |
| Trees, all features incl. TTL (reference only) | 0.923 | 0.944 | 0.934 | 9.7 % | 0.984 |

Per attack (recall): Analysis 99 %, Backdoor 99 %, Generic 99 %, Worms 95 %, DoS 95 %,
Reconnaissance 95 %, Shellcode 92 %, Exploits 91 %, **Fuzzers 41 %**. Leave-one-attack-out (the
category removed from training): most categories stay above 88 %; Exploits drops to 72 %,
Reconnaissance to 63 %, Fuzzers to 30 %. TTL alone reaches ROC-AUC 0.89, which is why we exclude it
(testbed artefact).
NSL-KDD (gradient-boosted trees, phone-observable fields, KDDTest+ once): F1 0.854 at 4.4 % false
alarms; DoS 92 %, Probe 96 %, R2L 22 %, U2R 42 % (these need packet contents); attack types never
seen in training 65 % vs. seen 82 %.

**Why it raises no alerts:** UNSW-NB15 contains **zero normal TLS/HTTPS flows** (checked in both
partitions). On a replayed capture, the model called all 60 ordinary HTTPS sessions "attack".
Phone traffic is almost all HTTPS, so the model cannot be trusted on a phone until it is
retrained on phone traffic (Phase 2: CIC-AndMal2017 Android captures, or our own labelled
captures). The false-alarm drift from 2 % (validation) to 7 % (test) points the same way.
CICIDS2017 was not reachable from our build machine.

## Host demo: pcap replay

`python bench/netwatch_pcap_demo.py` writes a synthetic capture with Scapy (normal browsing plus a
Wi-Fi port scan, malware DNS lookups, a bank look-alike, a 30 s heartbeat and a 2.4 MB upload),
rebuilds flows, and runs the rules. All five are found, normal browsing raises nothing:

```
suspicious_upload | 2.5 MB sent to 203.0.113.9
port_scan         | 192.168.1.66 tried 399 ports of the phone within a minute
malicious_domain  | the phone looked up 3 machine-generated domains that do not exist
malicious_domain  | the phone opened a look-alike website hdfcbnak.com
beaconing         | the phone contacts 203.0.113.9 every 30 s
```

`--pcap file.pcap` analyses your own capture (for example one exported from PCAPdroid).

## Llama explanation

`network_user_prompt` / `LlmContract.networkUserPrompt` (same JSON contract and validator as calls
and messages) turns a finding into plain Hindi/English advice: what the app may be doing and what
to do now (uninstall it, turn off data, call 1930). Runs only if the Llama model is on the phone.

## Datasets and licences

| Data | Source | Licence / terms |
|---|---|---|
| UNSW-NB15 | Moustafa & Slay 2015, UNSW Canberra (GitHub mirror, row counts match) | free for academic research, cite |
| NSL-KDD | Tavallaee et al. 2009, UNB CIC | free for research, cite |
| DGA domains | Cucchiarelli et al. 2021, ESWA 170:114551 (Netlab 360 feed) | research use, cite |
| Popular domains | zer0h/top-1000000-domains | false-alarm test only |

Raw data is downloaded by `data/public/download.sh` and never committed.

## Reproduce

```
bash data/public/download.sh
python models/netwatch/train_domain.py     # -> assets/models/domain_model.*, bench/results/netwatch_domain.json
python models/netwatch/train_ids.py        # -> assets/models/flow_model.{json,pte}, bench/results/netwatch_ids.json
python bench/netwatch_sim.py               # -> bench/results/netwatch_sim.json
python bench/netwatch_pcap_demo.py         # -> bench/results/netwatch_pcap_demo.json
python tests/make_parity_vectors.py && (Kotlin parity, see README)
```
