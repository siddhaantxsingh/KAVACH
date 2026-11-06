# KAVACH: exact setup steps

Two setups. Pick the one that matches your phones. Everything stays on the phones: neither main
app has the INTERNET permission, and audio is never stored.

| | Setup A: 1 Android + 2 iPhones (our team) | Setup B: 2 Androids (full "both modes") |
|---|---|---|
| Call happens on | an iPhone (the "victim" phone), on speaker | Android 1 (the user's phone) |
| KAVACH Shield (messages, links, apps, payments, calls) | the Android | Android 1 |
| KAVACH Guardian (listens to the call) | the same Android, next to the iPhone | Android 2, next to Android 1 |
| Guardian starts by itself on an unknown call | no (Shield cannot see an iPhone's call): press **Start guarding** | **yes** (Standby mode) |
| Scammer phone | the second iPhone | any phone |

## 0. Once, on a computer (both setups)

1. **Get the APKs (no Android Studio needed):** GitHub builds them on every push. Open the repo's
   **Releases → apks-latest** and download `kavach-shield-debug.apk`, `kavach-guardian-debug.apk`
   and (optional) `kavach-netwatch-debug.apk` into a `dist/` folder.
   (Or build yourself with Android Studio / JDK 17: `scripts/build_apks.sh`.) All builds use the
   same demo signing key (`android/kavach-shared-debug.keystore`), so the apps always work together.
2. Install `adb` (Android platform-tools) for pushing the speech models.
3. Turn on Developer options → USB debugging on each Android and connect it.

## Setup A: 1 Android + 2 iPhones

On the Android:

1. `adb install dist/kavach-shield-debug.apk` and `adb install dist/kavach-guardian-debug.apk`.
2. `scripts/push_models.sh --asr` (speech models into the Guardian). Optional:
   `scripts/push_models.sh --official-spinquant` (Llama 3.2 1B explanations).
3. Open **KAVACH Shield**:
   1. *Allow message checking* → turn on notification access for KAVACH Shield.
   2. *Allow screen-share guard* → turn on usage access (also powers Network Watch).
   3. Allow the permission pop-ups (notifications, phone state, call log, contacts, Bluetooth).
   4. *Start protection*.
4. Open **KAVACH Guardian**: allow the microphone → **Link phone → This phone**.
5. Optional: install `dist/kavach-netwatch-debug.apk`, open it, **Start Network Watch**, accept
   Android's VPN prompt (it only checks website names, locally).

For each test call:

1. Put the iPhone **on speaker** and lay the Android next to it (10–30 cm, no TV nearby).
2. In the Guardian press **Start guarding** and choose the call language.
3. Watch the **Placement** line: it should say *good*. If it says *move closer* or *too noisy*,
   move the phones together or turn the TV down.
4. Make the scam call from the second iPhone (scripts: `demo/roleplay_scripts.md`).

## Setup B: 2 Androids (both modes together)

Android 1 = the user's phone (Shield). Android 2 = the Guardian.

1. Android 1: `adb install dist/kavach-shield-debug.apk` and do step 3 of Setup A.
   Optional: `dist/kavach-netwatch-debug.apk` as in Setup A step 5.
2. Android 2: `adb install dist/kavach-guardian-debug.apk`, `ANDROID_SERIAL=<android2> scripts/push_models.sh --asr`.
3. Pair the two phones in Android Bluetooth settings.
4. Android 2, Guardian: **Link phone** → pick Android 1 → **⏸ Standby: start by itself on calls**.
   Keep *Auto-start: ON*.
5. Optional (removes the "press speaker" step): Android 1, Shield → **KAVACH Phone** →
   *Make KAVACH my phone app*. Unknown callers then go on speaker by themselves after a spoken
   notice, and STOP shows a one-tap **End call**.

For each test call: just answer the call on Android 1 (on speaker, or let KAVACH Phone do it)
with Android 2 next to it. The Guardian starts listening when the call starts and stops about
30 s after it ends. Both screens say *which* channel triggered a warning
(e.g. "Unknown caller + a dangerous link").

## One-phone mode (no Guardian at all)

Shield alone already knows *that* a call with an unknown number is going on (never its audio).
During such a call it raises STOP when the user taps a dangerous link, installs a risky app,
starts screen sharing, or an app starts uploading data, and CAUTION when a payment app is opened
in a call longer than 3 minutes.

## Before the final test (checklist)

- [ ] All APKs built on the same computer, installed, permissions granted
- [ ] `scripts/push_models.sh --asr` done (Guardian shows "listening", not "models missing")
- [ ] Demo replay works without models (Guardian → *Demo replay*)
- [ ] One real role-play call reaches STOP; one genuine bank-style call does not
- [ ] Placement line says *good* at your demo spot
- [ ] Optional: Llama loaded (the Guardian shows "Llama 3.2 1B explains")
- [ ] Optional: Network Watch on; `python bench/netwatch_pcap_demo.py` ready on the laptop
