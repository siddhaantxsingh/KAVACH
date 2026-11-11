#!/usr/bin/env bash
# Runs on the CI emulator: installs the one KAVACH app and Network Watch, opens each main screen in
# dark and light mode and saves a screenshot of each into shots/. Used only to review the look.
set -x
mkdir -p shots
K=io.kavach.guardian; N=io.kavach.netwatch
adb install -r android/guardian/build/outputs/apk/debug/guardian-debug.apk
adb install -r android/netwatch/build/outputs/apk/debug/netwatch-debug.apk
adb root; sleep 3; adb wait-for-device
for p in POST_NOTIFICATIONS READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS RECORD_AUDIO; do adb shell pm grant $K android.permission.$p; done
adb shell pm grant $N android.permission.POST_NOTIFICATIONS
adb shell settings put system font_scale 1.0
# the emulator's own launcher sometimes shows a "not responding" pop-up: hide such dialogs
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
sleep 20
shot() { sleep "${2:-4}"; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null; sleep 1; adb exec-out screencap -p > "shots/$1.png"; }
home() { adb shell am start --activity-clear-task -n $K/kavach.app.MainActivity --ez quiet true "$@"; }
calls() { adb shell am start -n $K/kavach.guardian.GuardianActivity --ez quiet true "$@"; }
nw() { adb shell am start --activity-clear-task -n $N/kavach.netwatch.NetWatchActivity --ez quiet true "$@"; }
SCAM="Dear customer your SBI account will be blocked today. Update KYC now at http://sbi-kyc-update.xyz/login and share the OTP"
A11Y=$K/kavach.guardian.CallListenAccessService

for mode in dark light; do
  if [ $mode = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  sleep 2
  # ---- before setup
  adb shell am force-stop $K
  adb shell cmd notification disallow_listener $K/kavach.app.ShieldListenerService
  adb shell appops set $K GET_USAGE_STATS default
  adb shell settings put secure enabled_accessibility_services null
  home --es lang en --ei tab 0; shot home_setup_$mode 6
  calls --ei tab 0; shot calls_setup_$mode 5
  # ---- everything on
  adb shell cmd notification allow_listener $K/kavach.app.ShieldListenerService
  adb shell appops set $K GET_USAGE_STATS allow
  adb shell settings put secure enabled_accessibility_services $A11Y
  adb shell settings put secure accessibility_enabled 1
  adb shell am start-foreground-service -n $K/kavach.app.ShieldService
  adb shell am start-foreground-service -n $K/kavach.guardian.GuardianService -a kavach.guardian.STANDBY --es lang en
  home --ei tab 0; shot home_on_$mode 6
  calls --ei tab 0; shot calls_ready_$mode 4
  calls --ei tab 1; shot calls_tools_$mode 4
  home --ei tab 2 --es text "'$SCAM'" --ez run true; shot check_$mode 7
  home --ei tab 3; shot activity_$mode
  home --ei tab 4; shot settings_$mode
  adb shell am start -n $K/kavach.guardian.BenchmarkActivity; shot bench_$mode
  adb shell am start -n $K/kavach.app.StopActivity --es text "'Real police never ask for money on a call. Do not send anything. Hang up and call 1930.'" --es rule "'Unknown caller asking for money'"
  shot stop_$mode 7
  adb shell input keyevent KEYCODE_BACK
  # ---- a demo call (scripted) on the Calls screen
  adb shell am startservice -n $K/kavach.guardian.GuardianService -a kavach.guardian.STOP
  sleep 2
  calls --ei tab 0 --es demo kyc_bank_en; shot demo_a_$mode 9
  shot demo_b_$mode 25
  adb shell input swipe 500 1800 500 700 300; shot demo_c_$mode 2
  adb shell am force-stop $K
  # ---- Network Watch
  nw --es lang en --ei tab 0; shot nw_overview_$mode 5
  nw --ei tab 1; shot nw_apps_$mode 4
  nw --ei tab 2 --ez scan true; shot nw_wifi_scan_$mode 40
done
adb shell cmd uimode night yes
home --es lang hi --ei tab 0; shot home_hi_dark 6
calls --ei tab 0; shot calls_hi_dark 5
adb logcat -d -t 600 '*:E' > shots/logcat_errors.txt || true
ls -la shots
