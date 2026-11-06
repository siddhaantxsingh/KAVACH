# OEM / system mode (for phone makers)

**What it removes:** both the spare Guardian phone and the "put the call on speaker" step.

Normal Android apps cannot hear phone calls (Android 10+). A **privileged system app** that holds
`CAPTURE_AUDIO_OUTPUT` can. KAVACH Guardian already asks for that permission in its manifest. On an
ordinary install it is never granted and nothing changes. When a phone maker (or a custom ROM)
ships the Guardian as a privileged app with the permission granted:

* `GuardianService.oemMode()` sees the permission and `AsrEngine` opens
  `AudioSource.VOICE_CALL` (both sides of the call), falling back to `VOICE_DOWNLINK` (the caller
  only), and then the microphone if this phone's audio HAL refuses call capture;
* the Guardian runs **on the same phone** as the call. Shield's call awareness sends
  `call_started` over the same-phone link and the Guardian starts by itself (Standby mode);
* the Guardian screen says "system mode: reading the call audio directly".

Audio handling is unchanged: 100 ms chunks go straight into the speech recogniser and are never
stored or sent anywhere.

## How an OEM installs it

```
# platform-signed or allow-listed build of the same APK
/system_ext/priv-app/KavachGuardian/KavachGuardian.apk
/system_ext/etc/permissions/privapp-permissions-io.kavach.guardian.xml   (scripts/oem/)
```

On an engineering / userdebug build you can try it with
`adb root && adb remount`, then push both files and reboot.

## Limits, stated plainly

* **Not available to us on a normal retail phone.** It needs the phone maker's signature or
  privileged-app allow-list. For the competition demo it is a design + code path, not something
  we can show on a stock phone.
* **The audio HAL decides.** Some chipsets / Android builds return silence or refuse
  `VOICE_CALL` even to privileged apps (VoLTE and VoWiFi paths differ). The fallback chain handles
  that; the screen shows which source is in use.
* **Law and consent.** Listening to a call (even without storing it) may need the user's explicit
  consent under local law. KAVACH must show a clear opt-in. This is not legal advice.
* **Same detector.** Call audio is cleaner than a speaker in a room, so recognition should
  improve, but we have not measured it on a device.
