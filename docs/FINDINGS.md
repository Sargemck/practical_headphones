# FINDINGS

What we learned from the real phone and headphones. If this file contradicts CLAUDE.md or PLAN.md, this file wins; flag the conflict in the PR.

## Entry template

```
### <YYYY-MM-DD> <short title>

- Firmware: <headphone firmware, or n/a>
- Phone: <model>, Android <version> (SDK <n>, build <id>)
- Tested: <what was tried, exact commands>
- Evidence: <raw hex / log lines / timings>
- Conclusion: <what we now treat as fact>
```

---

### 2026-10-27 S0: phone and adb baseline

- Firmware: n/a (headphones not queried in S0)
- Phone: Pixel 10 Pro (`blazer`), Android 17 (SDK 37, build CP3A.261005.005, security patch 2026-10-05)
- Tested: `adb devices -l`, `getprop`, USB device enumeration on Windows
- Evidence:
  - `61170DLCH000FA  device product:blazer model:Pixel_10_Pro`
  - `ro.build.version.release=17`, `ro.build.version.sdk=37`
  - With USB debugging off, Windows sees the phone only as `USB\VID_18D1&PID_4EE1` (MTP). No Google USB driver was needed once debugging was on.
- Conclusion:
  - **Conflict:** CLAUDE.md and PLAN.md say Android 16. The phone runs Android 17 (API 37). targetSdk/compileSdk 36 still build and run on it; M0 should check whether anything in the plan (non-SDK reflection, RFCOMM, foreground service rules) behaves differently on 37.
  - USB adb works with the stock Windows driver.

### 2026-10-27 S0: hands-off toolkit

- Firmware: n/a
- Phone: Pixel 10 Pro, Android 17 (SDK 37)
- Tested:
  - `adb exec-out screencap -p > shot.png`
  - `adb shell uiautomator dump /sdcard/ui.xml` + `adb pull`
  - `adb shell input keyevent KEYCODE_HOME`, `adb shell input swipe 540 2000 540 600 250`
- Evidence:
  - screencap: valid PNG, 1080 x 2410, readable when viewed
  - HOME, then dump shows `com.google.android.apps.nexuslauncher:id/workspace`; swipe up, then dump shows `nexuslauncher:id/apps_view`
  - In Git Bash, `adb shell uiautomator dump /sdcard/ui.xml` wrote to `/Files/Git/sdcard/ui.xml` and `adb pull /sdcard/...` failed: MSYS rewrites arguments starting with `/` into Windows paths.
- Conclusion:
  - Screenshot, UI dump and input injection all work on Android 17.
  - Any adb command with a device path must run with `MSYS_NO_PATHCONV=1` (or use `//sdcard/...`).

### 2026-10-27 S0: apps and Bluetooth state

- Firmware: n/a
- Phone: Pixel 10 Pro, Android 17 (SDK 37)
- Tested: `adb shell pm list packages`, `adb shell dumpsys bluetooth_manager`, `adb shell settings get global stay_on_while_plugged_in`
- Evidence:
  - Headphones bonded as `Bose QC Ultra 2 HP` (address ends `37:F6`), classic BR/EDR, HEADSET + A2DP profiles. Disconnects logged with ACL reason 19 (remote user terminated).
  - `net.quietrebellion` installed. `com.bose.bosemusic` not installed in user 0.
  - `pm list packages` also tries user 10 and fails: `SecurityException: Shell does not have permission to access user 10`. There is a second profile (likely Private space or work profile) that adb shell can't list.
  - `stay_on_while_plugged_in=0` ("Stay awake" off at the time of the check)
- Conclusion:
  - Bose app is absent from the main profile. Its presence in user 10 is unknown.
  - Quiet Rebellion must be force-stopped (or uninstalled) before M0 connects, since it holds the same RFCOMM channel.

### 2026-10-27 S0: Bluetooth toggle, reboot, idle auto-off

Pending. Needs the desk setup with headphones powered on and connected.
