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
  - `<serial redacted>  device product:blazer model:Pixel_10_Pro`
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

### 2026-10-27 M0 prep: `MSYS_NO_PATHCONV=1` for all commands

- Firmware: n/a
- Phone: n/a (PC only)
- Tested: with `MSYS_NO_PATHCONV=1`: `./gradlew --version`, `git log`, `sdkmanager.bat --list_installed`, `java -version`, and `jar.exe tf <path>` with three path styles. adb device paths were proven earlier in S0.
- Evidence:
  ```
  jar tf /c/Users/.../gradle-wrapper.jar   -> NoSuchFileException: \c\Users\...\gradle-wrapper.jar
  jar tf C:/Users/.../gradle-wrapper.jar   -> META-INF/LICENSE
  jar tf gradle/wrapper/gradle-wrapper.jar -> META-INF/LICENSE
  ```
  Gradle, git, sdkmanager and java all ran normally. Setting the variable to any value (even `0`) disables conversion.
- Conclusion: kept for all commands. The only breakage is POSIX-style absolute paths (`/c/...`) passed to Windows programs, so local paths use `C:/...` or relative form. Limiting it to adb would mean prefixing each adb call, which likely stops those calls matching the permission allowlist.

### 2026-10-27 S0: apps and Bluetooth state

- Firmware: n/a
- Phone: Pixel 10 Pro, Android 17 (SDK 37)
- Tested: `adb shell pm list packages`, `adb shell dumpsys bluetooth_manager`, `adb shell settings get global stay_on_while_plugged_in`
- Evidence:
  - Headphones bonded as `Bose QC Ultra 2 HP` (address ends `37:F6`), classic BR/EDR, HEADSET + A2DP profiles. Disconnects logged with ACL reason 19 (remote user terminated).
  - `net.quietrebellion` installed. `com.bose.bosemusic` not installed in user 0.
  - `pm list packages` can't list a second user profile (`SecurityException: Shell does not have permission to access user ...`).
  - `stay_on_while_plugged_in=0` ("Stay awake" off at the time of the check)
- Conclusion:
  - Bose app is absent from the main profile. Its presence in the second profile is unknown.
  - Quiet Rebellion must be force-stopped (or uninstalled) before M0 connects, since it holds the same RFCOMM channel.

### 2026-10-27 S0: "Stay awake" blocked by device policy

- Firmware: n/a
- Phone: Pixel 10 Pro, Android 17 (SDK 37)
- Tested: Developer options > Stay awake; `adb shell dumpsys power`
- Evidence:
  - Settings shows "Stay awake: Controlled by admin"
  - `mMaximumScreenOffTimeoutFromDeviceAdmin=300000 (enforced=true)`, `mStayOnWhilePluggedInSetting=0`, `mScreenOffTimeoutSetting=120000`
- Conclusion:
  - **Conflict with PLAN.md desk setup.** "Stay awake" can't be turned on, by Settings or by adb (PowerManager ignores stay-on while an admin timeout is enforced). The screen locks after at most 5 min idle.
  - Approved replacement (S0 review): debug builds keep the screen on and show over the lock screen; desk setup becomes "phone unlocked at the start of a run". M0 verifies both. Bluetooth toggling, dumpsys and logcat work with the screen locked.

### 2026-10-27 S0: Bluetooth toggle off/on

- Firmware: n/a
- Phone: Pixel 10 Pro, Android 17 (SDK 37)
- Tested: headphones connected (A2DP + HFP), Quiet Rebellion force-stopped. `adb shell cmd bluetooth_manager disable`, wait until `settings get global bluetooth_on` = 0, wait 5 s, `enable`, then poll the A2DP state machine for the headphones in `dumpsys bluetooth_manager` every ~0.5 s.
- Evidence:
  ```
  disable: Success      bt off after 0.7s
  enable: Success       bt on after 0.3s
  t+0.7s a2dp=Connecting
  t+8.7s a2dp=Connected
  ```
- Conclusion: `cmd bluetooth_manager disable|enable` works on Android 17 without root. The phone reconnects the headphones on its own about 9 s after Bluetooth comes back.
- Note: before the reboot below, the `dumpsys bluetooth_manager` event log printed timestamps 1 h ahead of `date` and logcat. After the reboot they matched. Likely a stale UTC offset from before the 25 Oct clock change (phone timezone Europe/Dublin). Use logcat or our own timestamps for timing.

### 2026-10-27 S0: reboot

- Firmware: n/a
- Phone: Pixel 10 Pro, Android 17 (SDK 37)
- Tested: `adb reboot` at 01:14:05 (PC clock; phone clock is 1.7 s behind). Timeline from `adb logcat -b events` and `-b crash`.
- Evidence (phone clock):
  ```
  01:14:27.9  boot_progress_start
  01:14:38.6  boot_progress_enable_screen        (~35 s after reboot)
  01:14:39.8  UsbDebuggingActivity shown         ("Allow USB debugging?" asked again)
  01:14:52.1  keyguardGoingAway, user 0 unlocking (Baris entered PIN)
  01:15:07.4  DeadSystemException in many apps    (system_server died and restarted; root-cause logs rotated out)
  01:15:08.1  boot_progress_start (second time)
  01:15:20.5  A2DP Connected for the headphones
  adb answered again ~63 s after the reboot command
  ```
- Conclusion:
  - A reboot is **not hands-off** on this phone. adb stayed unavailable until the USB debugging prompt was accepted on the phone, and the PIN is needed after every reboot.
  - Whether the headphones reconnect before the first unlock is unknown, and it doesn't matter: our app isn't direct-boot aware, so nothing of ours runs before the first unlock anyway. The reboot auto-connect test moves to H2; M6 uses `am kill` + Bluetooth toggle instead.
  - One unexplained system_server restart ~15 s after unlock. Seen once; watch for it in later reboots.

### 2026-10-27 S0: idle auto-off

- Firmware: n/a
- Phone: Pixel 10 Pro, Android 17 (SDK 37)
- Tested: headphones on the desk, not worn, no audio playing, nothing sent to them. A2DP state polled every 30 s from `dumpsys bluetooth_manager`.
- Evidence:
  ```
  01:15:20.226 [..37:F6] aclStateChangeCallback: State:Connected,    transport:BR/EDR, reason:0
  01:25:20.573 [..37:F6] aclStateChangeCallback: State:Disconnected, transport:BR/EDR, reason:19
  ```
- Conclusion:
  - The headphones drop the link exactly 10 min after connecting when idle on the desk. Reason 19 means the headphones ended it, not the phone.
  - Matches Bose's documented behaviour for the 1st-gen QC Ultra Headphones: they sleep when off-head and not in use for 10 min. We assume gen 2 is the same.
  - bosectl NOTES.md lists `Settings.StandbyTimer [1.4]` (auto-off timer). M0 reads it (GET only). An earlier search here missed it because of a bad file glob.
  - **Constraint for every hands-off run:** a run has under 10 min of idle headphone time, or our app must keep traffic going. M0 experiment 5 (10 min passive listen) will hit this limit and should be shortened or checked against it.
