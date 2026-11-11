# KAVACH: exact setup steps (one phone)

KAVACH is **one app on one Android phone**. It checks messages, links, apps and screen sharing,
and it listens to a phone call you put on speaker, on the same phone. The optional
**Network Watch** app adds website and home Wi-Fi checks.

The main app has **no INTERNET permission** (check it yourself: App info → Permissions), and
audio is never stored. Network Watch is a separate app precisely because it needs the internet
permission to pass the phone's website lookups through.

## 0. Once, on a computer

1. Install `adb` (Android platform-tools) and turn on **Developer options → USB debugging** on the phone.
2. Plug the phone in and install the apps (GitHub builds them on every push, release `apks-latest`):

   ```bash
   curl -L -o kavach-debug.apk https://github.com/siddhaantxsingh/KAVACH/releases/download/apks-latest/kavach-debug.apk
   curl -L -o kavach-netwatch-debug.apk https://github.com/siddhaantxsingh/KAVACH/releases/download/apks-latest/kavach-netwatch-debug.apk
   adb install -r kavach-debug.apk
   adb install -r kavach-netwatch-debug.apk          # optional
   adb uninstall io.kavach.shield                     # only if the old separate Shield app is installed
   ```

   Removing the old Shield app deletes its saved warning list and counters (nothing else). The
   models and settings of the old Guardian app are kept: the one KAVACH app keeps its package id
   `io.kavach.guardian`.
3. Speech models and Llama (skip if they are already on the phone):
   `scripts/push_models.sh --asr` and `scripts/push_models.sh --official-spinquant`.

## 1. On the phone (about 2 minutes)

Open **KAVACH**. The Home tab shows **Finish setup, 0 of 4**:

1. **Message checking → Turn on**: allow notification access for KAVACH.
2. **Screen-share guard → Turn on**: allow usage access for KAVACH.
3. **Call listening → Turn on**: Settings → Accessibility → *Downloaded apps* (Xiaomi: *Installed
   services*) → **KAVACH call listening** → on. This is the only way Android lets an app keep
   hearing the microphone during a phone call; KAVACH uses it for nothing else (no screen
   reading, no taps). If Android says the setting is restricted: App info → ⋮ → *Allow restricted
   settings*, then try again.
4. **Turn on protection**: allow the microphone. Home now says **Protection is on**, and the
   Calls tab says **Ready for calls**.

In **Calls**, choose the **language of the call** (हिंदी / Hinglish or English).

Optional, removes the "press speaker" step: **Settings → KAVACH Phone → Make KAVACH your phone app**.
Calls from unknown numbers then go on speaker by themselves, and a Stop warning gets a one-tap
**End call**.

## 2. A real test call (the phone is the victim's phone)

1. A teammate calls this phone from another phone (an iPhone is fine) and plays a scam script:
   live (scripts in `demo/roleplay_scripts.md`) or by playing `test_call_*.wav` into their phone.
2. Answer and **put the call on speaker** (automatic with KAVACH Phone).
3. KAVACH starts listening by itself when the number is not in your contacts. The Calls tab shows
   the live transcript. On a scam it turns **yellow** (Caution) or **red** (Stop), the phone
   vibrates and the full-screen Stop card appears. During a real call KAVACH does **not** speak
   its warning (it would go into the call); after the call it can.
4. Calls from saved contacts are not listened to unless you tap **Start listening now**.

## 3. Without a second phone (laptop test)

Calls → **Start listening now**, then play a `test_call_*.wav` from a laptop speaker 10–30 cm
away. Here the warning is also spoken.

## 4. Network Watch (optional app)

Open **Network Watch → Turn on** and accept Android's VPN prompt (local only; only website names
are checked). The **Wi-Fi** tab → **Check my Wi-Fi** looks for devices on your own network with
risky open services (remote debugging on a TV box, Telnet on a camera, open file shares). Findings
also appear in KAVACH's Activity tab, explained by Llama when call protection is on.

## 5. Performance numbers for the report

Calls → **Setup & tools → Measure performance** (3–4 minutes, unplug USB/charger first). It
measures every model on this phone's Arm CPU and saves a JSON file; the screen shows the `adb pull`
command.

## Before the final test (checklist)

- [ ] Home says **Protection is on**; Calls says **Ready for calls**
- [ ] Calls → Setup & tools → Readiness: microphone, call listening, both speech models, Llama all ticked
- [ ] One real speaker-phone call with a scam script reaches yellow or red
- [ ] One genuine-style call stays calm
- [ ] Placement chip says *Hearing the call clearly* at your demo spot
- [ ] Optional: Network Watch on; Wi-Fi check run once
