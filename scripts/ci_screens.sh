#!/usr/bin/env bash
# Runs on the CI emulator: installs Shield and Guardian, opens each main screen in dark and light
# mode and saves a screenshot of each into shots/. Used only to review the look of the apps.
set -x
mkdir -p shots
S=io.kavach.shield; G=io.kavach.guardian
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/guardian/build/outputs/apk/debug/guardian-debug.apk
adb root; sleep 3; adb wait-for-device
for p in POST_NOTIFICATIONS READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS BLUETOOTH_CONNECT; do adb shell pm grant $S android.permission.$p; done
for p in POST_NOTIFICATIONS RECORD_AUDIO BLUETOOTH_CONNECT; do adb shell pm grant $G android.permission.$p; done
adb shell settings put system font_scale 1.0
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "shots/$1.png"; }
open_s() { adb shell am start -n $S/kavach.app.MainActivity --ez quiet true "$@"; }
open_g() { adb shell am start -n $G/kavach.guardian.GuardianActivity --ez quiet true "$@"; }
SCAM="Dear customer your SBI account will be blocked today. Update KYC now at http://sbi-kyc-update.xyz/login and share the OTP"

for mode in dark light; do
  if [ $mode = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  sleep 2
  # ---- Shield, before setup
  adb shell am force-stop $S
  adb shell cmd notification disallow_listener $S/kavach.app.ShieldListenerService
  adb shell appops set $S GET_USAGE_STATS default
  open_s --es lang en --ei tab 0; shot shield_home_setup_$mode 6
  # ---- Shield, protection on
  adb shell cmd notification allow_listener $S/kavach.app.ShieldListenerService
  adb shell appops set $S GET_USAGE_STATS allow
  adb shell am start-foreground-service -n $S/kavach.app.ShieldService
  open_s --ei tab 0; shot shield_home_on_$mode 5
  open_s --ei tab 1 --es text "$SCAM" --ez run true; shot shield_check_$mode 6
  open_s --ei tab 2; shot shield_activity_$mode
  open_s --ei tab 3; shot shield_settings_$mode
  adb shell input swipe 500 1800 500 600 300; shot shield_settings2_$mode 2
  open_s --ei tab 0; shot shield_home_after_$mode
  adb shell am start -n $S/kavach.app.DialerActivity; shot shield_phone_$mode
  adb shell am start -n $S/kavach.app.StopActivity --es text "Real police never ask for money on a call. Do not send anything. Hang up and call 1930." --es rule "Unknown caller asking for money"
  shot shield_stop_$mode
  adb shell input keyevent KEYCODE_BACK
  # ---- Guardian
  adb shell am force-stop $G
  open_g --es lang en --ei tab 0; shot guardian_idle_$mode 6
  open_g --ei tab 1; shot guardian_setup_$mode
  adb shell input swipe 500 1800 500 600 300; shot guardian_setup2_$mode 2
  open_g --ei tab 0 --es demo kyc_bank_en; shot guardian_demo_a_$mode 9
  shot guardian_demo_b_$mode 25
  adb shell input swipe 500 1800 500 700 300; shot guardian_demo_c_$mode 2
  adb shell am force-stop $G
done
adb shell cmd uimode night yes
open_s --es lang hi --ei tab 0; shot shield_home_hi_dark 5
open_g --es lang hi --ei tab 0; shot guardian_idle_hi_dark 5
adb logcat -d -t 400 '*:E' > shots/logcat_errors.txt || true
ls -la shots
