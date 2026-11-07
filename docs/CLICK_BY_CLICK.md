# KAVACH: every click, from zero to a recorded demo

Written for **Setup A**: a Windows laptop, 1 Android phone, 2 iPhones. On a Mac, use the Terminal
app wherever this says "Git Bash". Android menus differ slightly between brands; where Samsung or
Xiaomi/Redmi differ, it says so. Budget: Part 1 about 1 hour, Part 2 about 1 hour, Part 3 one evening.

Words used below: **Shield** = the "KAVACH Shield" app, **Guardian** = the "KAVACH Guardian" app,
**victim iPhone** = the iPhone that receives the scam call, **scammer iPhone** = the one that calls.

---

## Part 1: set up the laptop (once)

### 1.1 Install Git (gives you "Git Bash")
1. Open https://git-scm.com/download/win → the download starts → open the `.exe`.
2. Click **Next** on every screen (the defaults are fine) → **Install** → **Finish**.

### 1.2 Install Python
1. Open https://www.python.org/downloads/ → click the yellow **Download Python 3.x** button → open it.
2. On the first screen **tick "Add python.exe to PATH"** (bottom) → **Install Now** → **Close**.

### 1.3 Install adb (Android platform-tools)
1. Open https://developer.android.com/tools/releases/platform-tools → **Download SDK Platform-Tools for Windows** → tick the agreement → **Download**.
2. Open the downloaded `.zip` → **Extract all** → choose `C:\` → **Extract**. You now have `C:\platform-tools`.

### 1.4 Get the project
1. Press the **Windows key**, type `Git Bash`, press **Enter**. A black window opens. (All commands below go here: paste with **Shift+Insert** or right-click → **Paste**, then press **Enter**.)
2. Paste these, one line at a time:
   ```
   cd ~
   git clone https://github.com/siddhaantxsingh/KAVACH
   cd KAVACH
   echo 'export PATH="$PATH:/c/platform-tools"' >> ~/.bashrc && source ~/.bashrc
   pip install -U huggingface_hub
   ```
   (If `git clone` asks you to sign in, click **Sign in with your browser** and allow it.)
3. Check adb works: type `adb version` → it prints a version number.

### 1.5 Download the apps
1. In your browser open https://github.com/siddhaantxsingh/KAVACH/releases → under **apks-latest** click
   `kavach-shield-debug.apk` and `kavach-guardian-debug.apk` (and `kavach-netwatch-debug.apk` if you want Network Watch).
2. In Git Bash:
   ```
   mkdir -p dist && mv ~/Downloads/kavach-*.apk dist/
   ```

### 1.6 Hugging Face account (needed for the speech models and Llama)
1. Open https://huggingface.co/join → sign up → confirm the email.
2. Open https://huggingface.co/settings/tokens → **Create new token** → type: **Read** → name `kavach` → **Create token** → **Copy**.
3. Open https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET →
   fill in Meta's form (name, country, affiliation "MIT Manipal") → **Agree and access repository**.
   It may take a few minutes to hours to be approved; you get an email.
4. In Git Bash: `hf auth login` (older installs: `huggingface-cli login`) → paste the token
   (**nothing appears while you paste, that's normal**) → **Enter** → when asked "Add token as git credential?" type `n` → **Enter**.

---

## Part 2: set up the Android phone

### 2.1 Turn on USB debugging
1. **Settings → About phone** → tap **Build number** 7 times (Xiaomi: tap **MIUI/HyperOS version**; Samsung: **Software information → Build number**) → enter your PIN → "You are now a developer".
2. Go back → **Settings → System → Developer options** (Samsung: at the bottom of Settings; Xiaomi: **Additional settings → Developer options**) → turn on **USB debugging** → **OK**.
   Xiaomi only: also turn on **Install via USB** and **USB debugging (Security settings)** (needs a Mi account sign-in).
3. Connect the phone to the laptop with a USB cable. On the phone: **Allow USB debugging?** → tick **Always allow from this computer** → **Allow**.
4. Git Bash: `adb devices` → you see one line ending in `device`. (If it says `unauthorized`, unlock the phone and press **Allow**.)

### 2.2 Install the apps
If an older KAVACH is on the phone, first remove it: `adb uninstall io.kavach.shield ; adb uninstall io.kavach.guardian`
```
adb install dist/kavach-shield-debug.apk
adb install dist/kavach-guardian-debug.apk
```
Each prints `Success`. (Xiaomi may show a pop-up on the phone: tap **Install**.)
Install by adb, not by tapping the file on the phone: apps installed from a file manager get
"restricted settings" on Android 13+ and cannot get notification access.

### 2.3 Copy the speech models (about 300 MB download, 5–15 min)
1. **Open KAVACH Guardian once** on the phone and close it (this creates its folder).
2. Git Bash: `scripts/push_models.sh --asr` → wait until the prompt comes back with no red `error`.

### 2.4 Copy Llama (only after the approval email from step 1.6.3)
Git Bash: `scripts/push_models.sh --official-spinquant` (about 1.1 GB). If it says "403" or "gated",
your access is not approved yet. Skip this and come back later: everything else works without Llama.

### 2.5 Hindi voice for the spoken warnings
1. **Settings → System → Languages & input → Text-to-speech output** (Samsung: **General management → Text-to-speech**; Xiaomi: **Additional settings → Languages & input → Text-to-speech output**).
2. **Preferred engine** → **Speech Services by Google**.
3. Tap the ⚙ next to it → **Install voice data** → **Hindi (India)** → tap the download icon. Also make sure **English (India)** is downloaded.

### 2.6 Stop the phone from killing KAVACH
For **KAVACH Shield** and then **KAVACH Guardian**: long-press the app icon → **App info** →
**Battery** (Samsung: **Battery**; Xiaomi: **Battery saver**) → **Unrestricted** (Xiaomi: **No restrictions**).
Xiaomi only: **App info → Autostart → On**.

### 2.7 Set up Shield
1. Open **KAVACH Shield**. Pop-ups appear one by one: tap **Allow** on every one
   (notifications, phone calls, call logs, contacts, nearby devices).
2. Tap **1. Allow message checking (notification access)** → in the list tap **KAVACH Shield** →
   turn on **Allow notification access** → **Allow** → press **Back** until you are in Shield again.
   (If Android says **Restricted setting**: App info → ⋮ top right → **Allow restricted settings** → try again.)
3. Tap **2. Allow screen-share guard (usage access)** → tap **KAVACH Shield** → turn on
   **Permit usage access** → **Back** to Shield.
4. Optional: type a family member's number in **Family member's number (optional)** → **Save** → **Allow** (SMS).
5. Tap **3. Start protection**. A notification "KAVACH is protecting this phone" stays in the bar.
6. Quick check: paste `https://sbi-kyc-update.xyz/login` into **Paste a link or message to check** → **Check** → it must say dangerous.

### 2.8 Set up Guardian
1. Open **KAVACH Guardian** → microphone pop-up → **While using the app** (or **Allow**); notifications → **Allow**.
2. Tap **Link phone** → choose **This phone (KAVACH Shield is installed here)**.
3. Tap **Demo replay** → choose **digital_arrest_hinglish**. Within about a minute the transcript fills
   in, the banner goes orange then **red STOP**, and you hear the warning. Tap **■ Stop**.
   This proves the apps, the link and the voice work (no speech models are needed for this).

---

## Part 3: the real tests

Keep the Android's **Wi-Fi and mobile data off** for Parts 3.1–3.4 (shows nothing goes online).

### 3.1 Scam call through the iPhones
1. Put the victim iPhone flat on the table, volume at maximum. Put the Android next to it (10–30 cm), microphone side towards the iPhone speaker. No TV or fan nearby.
2. Scammer iPhone → **Phone** → call the victim iPhone. On the victim iPhone tap **Accept** → tap **Speaker**.
3. On the Android, in **Guardian**, tap **▶ हिंदी / Hinglish** (or **▶ English** if you will speak English).
4. Look at the **Placement** line after about 5 seconds: it should be green **good**. Orange or red: move the phones closer, or switch off the fan or TV.
5. The scammer speaks a script from `demo/roleplay_scripts.md` (for example **digital-arrest-hinglish**). Expected: words appear, orange CAUTION, then red **STOP** and a spoken warning.
6. End the call → in Guardian tap **■ Stop**.

### 3.2 Genuine call (must stay calm)
Same as 3.1, but act out the **Bank fraud alert** call from the "Genuine calls" list at the end of `demo/roleplay_scripts.md`. Expected: no STOP (green or at most orange).

### 3.3 One-phone mode (no Guardian)
1. Close Guardian (swipe it away).
2. Call the **Android** from a number that is **not saved** in its contacts. Answer and keep the call going.
3. During the call, on the Android, paste `https://sbi-kyc-update.xyz/login` into Shield → **Check**
   (or tap that link in an SMS sent to the Android). Expected: full-screen **STOP**, "Unknown caller + a dangerous link".

### 3.4 If something goes wrong
Take a screenshot (power + volume down), note what you did, and send both to me.
For crashes, also run `adb logcat -d > log.txt` in Git Bash right afterwards and send `log.txt`.

### 3.5 Optional: Network Watch app (Wi-Fi ON for this one)
1. `adb install dist/kavach-netwatch-debug.apk`
2. Open **KAVACH Network Watch** → **Allow** notifications → **Start Network Watch** → **OK** on Android's VPN pop-up.
3. In Chrome open `hdfcbnak.com`. Expected: a notification "contacted a dangerous website".
4. Back in the app → **Stop**.

### 3.6 Laptop part of the Network Watch demo
Git Bash:
```
pip install scapy scipy numpy scikit-learn
python bench/netwatch_pcap_demo.py
```
Expected: 5 alerts (port scan, two malicious domains, heartbeat, upload).

### 3.7 Optional: phone speed numbers for the report
Only if Llama is on the phone. In Guardian tap **Perf** during a call: it shows speech speed and
Llama timing on screen. Take a screenshot and send it to me; I add the numbers to the report.
(The full benchmark, `bench/llama_bench.sh`, needs the Android NDK; tell me if you want to do it.)

---

## Part 4: finish and submit

1. **Team details.** Send me the three names, their roles and the faculty mentor; I put them in the README and report and rebuild the PDF.
2. **Rehearse** `demo/jury_demo_runbook.md` twice, with a timer (90 seconds for the live part).
3. **Record the 3-minute video:**
   - Android: swipe down twice → **Screen recorder** (Samsung) / **Screen recorder** app (Xiaomi) / **Screen record** tile → **Start**.
   - A second person films the table from above with any phone.
   - Show: 3.1 (scam → STOP), 3.2 (genuine stays calm), 3.3 (one-phone STOP), then 10 seconds on the report's results tables.
   - Join both videos side by side in any editor (CapCut on the phone works) → export 1080p.
4. **Submit on the challenge portal:** the GitHub link, `docs/KAVACH_Phase1_Technical_Report.pdf`, the APK link
   (https://github.com/siddhaantxsingh/KAVACH/releases) and the video. Read the portal once for anything else it asks for.
5. If the GitHub repo is private, either make it public (**Settings → General → Danger Zone → Change visibility**) or add the judges' accounts if the portal asks for that.
