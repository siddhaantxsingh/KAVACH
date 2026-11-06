# 90-second jury demo runbook

Written for **one Android phone + two iPhones**. The two-Android setup from the proposal is
at the end.

| Phone | Role | What runs on it |
|---|---|---|
| iPhone 1 | **Scammer** (teammate B) | nothing; just makes the call and sends the SMS |
| iPhone 2 | **Victim's call phone**: answers on **speaker** | nothing |
| Android | **KAVACH**: placed next to iPhone 2; also used as the victim's phone for the SMS / app / payment part | KAVACH Guardian **and** KAVACH Shield |

Why the call is on an iPhone: during a call, Android does not let other apps use the
microphone. So the call happens on iPhone 2 and the Android listens to its speaker.

**Rule:** Wi-Fi and mobile data are **off** on the Android. Neither KAVACH app even has the
INTERNET permission; show this in Settings > Apps > KAVACH > Permissions. The Android needs a
SIM (or Wi-Fi calling off) only to *receive* the scam SMS, so keep the SIM in and mobile data off.

## One-time setup
1. Build **both** APKs on the **same computer** (`scripts/build_apks.sh`). The two apps talk to
   each other only if they are signed with the same key.
2. Install both on the Android. Push the models: `scripts/push_models.sh --asr` and
   `scripts/push_models.sh --official-spinquant`.
3. Install the Hindi offline voice: Settings > Text-to-speech > Google > Install voice data > Hindi.
4. KAVACH Shield: grant **notification access** and **usage access**, then tap **Start protection**.
5. KAVACH Guardian: tap **Link phone** → **This phone (KAVACH Shield is installed here)**.
6. Install AnyDesk and a payment app (PhonePe/GPay) on the Android. Sideload a harmless test
   app named "Secure KYC" that asks for SMS + accessibility permissions.
7. Save iPhone 1's number on iPhone 2 as an unknown caller (don't name it "Scammer").

## 10 minutes before
* Android: Guardian open. The status line shows `Llama: READY` and `Shield link: connected (same phone)`.
  Volume at maximum, screen brightness up, the phone 30–60 cm from iPhone 2, both lying flat.
* iPhone 2: speaker volume at about 70 % (too loud distorts, too soft is missed).
* Room: no fan pointing at the phones; the TV off.
* Start a 10 s test call and check the Guardian's **Placement** line says *good*. If it says
  *move closer* or *too noisy*, move the phones together (a TV or people talking is the worst case).

## Timeline
| Time | What happens | What the jury sees |
|---|---|---|
| 0:00 | Presenter taps **▶ हिंदी** on the Guardian. B calls iPhone 2 from iPhone 1; the victim answers on **speaker**. | live Devanagari transcript on the Android |
| 0:15 | B: cyber police, Aadhaar linked to money laundering | amber **CAUTION** banner + short spoken caution |
| 0:30 | B: "call mat kaatna, kisi ko mat batana, video call pe raho" | red **STOP**; the Android speaks in Hindi: digital arrest is not real, hang up, call 1930 |
| 0:45 | Victim hangs up. Presenter: "now the scammer moves to other channels". | — |
| 0:50 | B sends the SMS link to the **Android** and says (on a new call or in the SMS) to install "Secure KYC" and AnyDesk | Shield notification: link is a **look-alike of SBI**, with reasons; installing "Secure KYC" → **high-risk app** with reasons. The Guardian's kill-chain also receives these over the same-phone link. |
| 1:05 | Victim opens PhonePe while AnyDesk is running | full-screen **STOP card**; family contact gets an SMS (if a number is saved in Shield) |
| 1:20 | Presenter taps the Guardian banner (**Perf**) | live CPU clock per core, which core each KAVACH thread runs on, RAM, battery, Llama speed |

**What to say about the phones:** "In real use KAVACH Shield is on the victim's own Android
phone and the Guardian is any spare phone placed next to it. Today one Android plays both
roles."

## Things that can go wrong
* **The Guardian reacts to its own warning?** It can't: it ignores everything it hears while it
  is speaking and for 1.2 s afterwards (the status line shows "ignored our own spoken warning").
* **Noisy room / ASR mishears:** Guardian → **Demo replay → digital_arrest_hi** runs the same engine on a scripted transcript.
* **Llama not loaded:** the rule + classifier path still raises STOP and speaks the warning. Call it the fail-safe design.
* **Shield link shows "waiting":** open KAVACH Shield once and tap **Start protection**, then restart guarding.
  If it still waits, the two APKs were built on different computers (different signing keys): rebuild both on one machine.
* **SMS doesn't arrive:** paste the link in Shield's "Check" box. The same detection runs.

## Two-Android setup (from the proposal)
Victim's Android runs Shield and takes the call on speaker. A second Android runs the Guardian,
linked over Bluetooth (pair once, then **Link phone → Bluetooth: <phone>**). The scammer can use
any phone. Everything else is the same, and the STOP card also pops up on the victim's phone.


## Extra demos (optional, about 30 s each)

**One-phone mode (no Guardian).** On the Android, close the Guardian. Call the Android from an
iPhone with a number that is not in contacts and keep talking. While the call is on, open the
scam link from the SMS (or open AnyDesk). Shield shows STOP: *"Unknown caller + a dangerous link"*.
Point out: Shield never heard the call; it only knew a call with an unknown number was going on.

**Both modes together (needs two Androids).** Guardian on Android 2 in **⏸ Standby**. Call Android 1
from an unknown number: the Guardian starts listening by itself ("call detected — listening"),
and both screens show which channel triggered the warning. Hang up: it returns to standby
about 30 s later.

**KAVACH Phone (dialer).** On Android 1, Shield → *KAVACH Phone* → *Make KAVACH my phone app*.
Call it from an unknown number and answer: it says "Unknown number. KAVACH is turning on the
speaker…" and switches to speaker. When the Guardian reaches STOP, the red **END CALL NOW** button
appears on the call screen.

**Network Watch.**
* On the laptop: `python bench/netwatch_pcap_demo.py`. In 3 seconds it finds a Wi-Fi port scan,
  malware domain lookups, a fake bank site, a remote-control heartbeat and a photo upload, and
  raises nothing for normal browsing.
* On the phone (optional; needs Wi-Fi on, so do it after the main demo): install
  `kavach-netwatch-debug.apk`, *Start Network Watch*, then open `hdfcbnak.com` in the browser.
  The app flags the look-alike and Shield records it.
