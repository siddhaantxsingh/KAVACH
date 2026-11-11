# KAVACH: every click (Windows + Git Bash + one Android phone)

Words used below: **KAVACH** = the one KAVACH app (shield icon, gold). **Network Watch** = the
optional second app. Paths are for a Xiaomi/Redmi/POCO phone; other phones use similar names.

## 1. Install or update (laptop, about 3 minutes)

1. Plug the phone in with the USB cable. If the phone asks **Allow USB debugging?** → **Allow**.
2. Open **Git Bash** and paste this one line, then press Enter:

   ```bash
   ADB="$(find ~/Downloads -name adb.exe 2>/dev/null | head -1)"; cd ~/Downloads && curl -L -o kavach-debug.apk https://github.com/siddhaantxsingh/KAVACH/releases/download/apks-latest/kavach-debug.apk && curl -L -o kavach-netwatch-debug.apk https://github.com/siddhaantxsingh/KAVACH/releases/download/apks-latest/kavach-netwatch-debug.apk && "$ADB" install -r kavach-debug.apk && "$ADB" install -r kavach-netwatch-debug.apk; "$ADB" uninstall io.kavach.shield
   ```

3. Wait for **Success** twice. The last part removes the old separate "KAVACH Shield" app if it is
   there (it prints *Success* or *Unknown package*; both are fine). Removing it deletes only its own
   warning list and counters. Your speech models and Llama stay on the phone.
4. If it says `INSTALL_FAILED_USER_RESTRICTED`: on the phone, Settings → Additional settings →
   Developer options → **Install via USB** → on, then paste the line again.

## 1b. Better Hindi speech model (once, about 5 minutes)

Phone plugged in. In Git Bash, inside your KAVACH folder (the one you cloned from GitHub), paste:

```bash
git pull && export PATH="$PATH:$(dirname "$(find ~/Downloads -name adb.exe 2>/dev/null | head -1)")" && bash scripts/push_models.sh --asr-hi-big
```

For the better English model (NVIDIA Parakeet 110M, 126 MB), the same line with `--asr-en-big` at the end.
Readiness then says *NVIDIA Parakeet 110M — on this phone*.

It downloads AI4Bharat's IndicConformer Hindi model (198 MB) from our GitHub release and copies it to
the phone. KAVACH → Calls → Setup & tools → Readiness then says *AI4Bharat IndicConformer — on this phone*.

## 2. Set up KAVACH on the phone (about 2 minutes)

Open **KAVACH** (Home tab, "Finish setup 0 of 4"):

1. **Message checking → Turn on** → in the list tap **KAVACH** → turn on → **Allow** → back.
2. **Screen-share guard → Turn on** → tap **KAVACH** → **Permit usage access** on → back.
3. **Call listening → Turn on** → **Open Settings** → **Downloaded apps** (Xiaomi: **Installed
   services**) → **KAVACH call listening** → turn on → **Allow** → back.
   - If the switch is grey or says *Restricted setting*: long-press the KAVACH icon → **App info** →
     ⋮ (top right) → **Allow restricted settings** → enter your PIN → repeat step 3.
4. **Turn on protection → Turn on** → microphone: **While using the app** (or **Allow**).
5. Allow the other pop-ups (notifications, phone, call logs, contacts).
6. Xiaomi only, so Android does not stop KAVACH in the background: long-press the KAVACH icon →
   **App info** → **Battery saver** → **No restrictions**, and **Autostart** → on.

Home now says **Protection is on**.

## 3. Call language (normally nothing to do)

**Auto** is the default: KAVACH runs both speech models on every sentence and keeps the one that
matches the language spoken, so a caller may start in English and switch to Hindi or Hinglish.
Auto needs both big models on the phone (`--asr-hi-big` and `--asr-en-big`); without them it
listens with the Hindi/Hinglish model. You can still fix the language by hand:


Tap **Calls** (bottom bar) → under *Language of the call* tap **हिंदी / Hinglish** or **English**.

## 4. Test with a real call (one phone)

1. A teammate calls your Android from their phone.
2. Answer, tap **Speaker**. Put the Android on the table.
3. The teammate plays a scam audio file into their phone near its microphone (or reads a script
   from `demo/roleplay_scripts.md`).
4. On your Android, open KAVACH → **Calls**: the transcript appears and it turns yellow or red,
   the phone vibrates, and a full-screen **Stop!** card appears on red.
   - The number must not be saved in your contacts, or tap **Start listening now** yourself.

## 5. Test without a second phone (laptop speaker)

KAVACH → **Calls** → choose the language → **Start listening now** → play the `.wav` on the laptop,
phone 10–30 cm from the speaker. Tap **Stop listening** before the next file.

## 6. Measure performance (for the report)

1. Unplug the USB cable (battery numbers are wrong while charging).
2. KAVACH → **Calls** → **Setup & tools** → **Measure performance** → **Start measuring**. Takes 3–4 minutes.
3. Take screenshots of the results, then plug back in and paste in Git Bash:

   ```bash
   ADB="$(find ~/Downloads -name adb.exe 2>/dev/null | head -1)"; MSYS_NO_PATHCONV=1 "$ADB" pull /sdcard/Android/data/io.kavach.guardian/files/bench/ "$(cygpath -w ~/Downloads/kavach_bench)"
   ```

   The JSON files are in your Downloads folder, in `kavach_bench`. Send them to the team (or to Claude).

## 7. Network Watch (optional)

1. Open **Network Watch** → **Turn on** → Android asks about a VPN connection → **OK**.
2. **Wi-Fi** tab → **Check my Wi-Fi** (about 15 s) on your home Wi-Fi.
3. Warnings also appear in KAVACH → **Activity**.
