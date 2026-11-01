# 90-second jury demo runbook

**Rule:** Wi-Fi and mobile data are **off** on both KAVACH phones; only the voice call is live.
Neither app even has the INTERNET permission, so nothing can reach the cloud. Show this in
Settings > Apps > KAVACH > Permissions.

## Setup (10 minutes before)
1. Guardian phone: models pushed (`scripts/push_models.sh --asr` and `--llama ...`); open
   KAVACH Guardian and check the status line says `Llama: READY`. Volume at maximum. Install the Hindi TTS voice.
2. User's phone ("victim"): KAVACH Shield → notification access ON, usage access ON, *Start
   protection*. Bluetooth paired with the Guardian. In Guardian tap **Link phone** → pick the victim phone.
3. Scammer phone (teammate B): the script `roleplay_scripts.md#demo-digital-arrest-hi` printed.
4. Prepare the SMS: teammate B sends from a second SIM:
   `SBI: Your KYC is pending. Account will be blocked today. Update: http://sbi-kyc-update.xyz/login`
5. Sideload a harmless test APK named "Secure KYC" that requests SMS + accessibility (see `bench/` notes),
   and install AnyDesk on the victim phone.
6. Rehearse 10 times. If the room is too noisy, use the fallback (below).

## Timeline
| Time | What happens | What the jury sees |
|---|---|---|
| 0:00 | B calls the victim phone; the victim answers on **speaker**; Guardian taps **▶ हिंदी** | live Devanagari transcript on the Guardian |
| 0:15 | B: cyber police, Aadhaar linked to money laundering | amber **CAUTION** banner + short spoken caution |
| 0:30 | B: "call mat kaatna, kisi ko mat batana, video call pe raho" | red **STOP**; Guardian speaks in Hindi: digital arrest is not real, hang up, call 1930; the victim phone shows the STOP card too (over Bluetooth) |
| 0:50 | B sends the SMS link and asks the victim to install "Secure KYC" and AnyDesk | Shield flags the link as a look-alike of SBI and the APK as high-risk, with reasons |
| 1:05 | victim opens PhonePe/GPay while AnyDesk is active | full-screen STOP card; family contact gets an SMS (if configured) |
| 1:20 | presenter taps the Guardian banner (**Perf**) | live CPU clocks per core, which core each KAVACH thread runs on, RAM, battery, Llama TTFT and tok/s |

## Fallbacks
* Noisy room / ASR trouble → Guardian **Demo replay → digital_arrest_hi** (same engine, scripted transcript).
* Llama not loaded → the rule + classifier path still raises STOP and speaks the playbook text (say so: "fail-safe").
* Bluetooth fails → show each phone separately; the kill-chain still works per device.
