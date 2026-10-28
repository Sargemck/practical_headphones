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

---

## M0 device session, 2026-10-28

Common to every entry below unless stated:

- Firmware: `10.12.12+ga927c9a` (GET 0.5)
- Phone: Pixel 10 Pro, Android 17 (SDK 37, build CP3A.261005.005), app targets SDK 37
- Tool: debug-only `SpikeActivity` / `SpikeSession`, driven by `adb shell am start ... --es cmd <name>`. Logs tag `BMAP` with `elapsedRealtime` ms. Full `logcat -v threadtime` kept outside the repo.
- Headphones on the desk, not worn, nothing playing (except E0b).

### 2026-10-28 M0 E1/E8: connect paths

- Tested: 3 x each of SDP with the BMAP UUID and reflection `createInsecureRfcommSocket(2)`, each followed by GET battery. Then E8: listed the headphones' UUIDs and SDP-connected to SPP and each vendor UUID (4 x for SPP).
- Evidence:

  ```text
  sdp  uuid=00000000-deca-fade-deca-deafdecacaff  connect 128-489 ms  GET 2.2 -> TIMEOUT 3/3
       receives unasked: ff 55 02 00 ee 10   (6 bytes, about once per second)
  reflect channel 2                            connect 95-237 ms   GET 2.2 -> STATUS 50 ff ff 00 in 104-149 ms, 3/3
  sdp  uuid=00001101-0000-1000-8000-00805f9b34fb (SPP)  connect 108-1886 ms  BMAP replies in 40-84 ms, 6/6 sessions
  sdp  uuid=9b26d8c0-a8ed-440b-95b0-c4714a518bcc  connects, silent, GET -> TIMEOUT
  sdp  346d5c4b-..., 77265d41-..., 88b0ee3c-..., df21fe2c-...  connect refused (IOException)
  BluetoothSocket.getPort() -> NoSuchMethodException (hidden API blocked), so channels can't be read
  ```

  Cached UUIDs: BMAP UUID, `1101` SPP, `110b` A2DP sink, `110e` AVRCP, `111e` HFP, `180f` battery (LE), five vendor UUIDs. `fetchUuidsWithSdp()` returned true but no `ACTION_UUID` arrived within 15 s. HFP/HSP were not probed (the phone holds those links).
- Conclusion:
  - **Conflict with CLAUDE.md (fixed in this PR):** SDP with the BMAP UUID lands on a non-BMAP channel. bosectl NOTES lists RFCOMM channel 14 as a status beacon sending `ff55 02...`; this matches. `ff 55` is also the start of an Apple iAP1 packet (reviewer's inference, not verified).
  - **SDP with the standard SPP UUID reaches BMAP** through the public API. This becomes the primary path; reflection on channel 2 is the fallback. A connect is only trusted once a BMAP request is answered.

### 2026-10-28 M0 E2: greeting

- Tested: connected on channel 2 (reflection) and SPP, sent nothing for 1 to 3.4 s.
- Evidence: no bytes at all on channel 2 / SPP before the first request, in every session. The BMAP-UUID channel starts the `ff 55 02 00 ee 10` beacon immediately.
- Conclusion: no greeting. The initial load can start right after connect.

### 2026-10-28 M0 E6: every feature address (fixtures)

- Tested: GET on each feature-map address, GET 4.5 with the first MAC from 4.4, START 31.1 with drain. Redacted fixtures in `bmap/src/test/resources/fixtures/qc-ultra2/fw-10.12.12/`.
- Evidence (payloads; names and MACs redacted):

  ```text
  0.5   STATUS "10.12.12+ga927c9a"
  1.2   STATUS 00 + 18-byte name
  1.4   ERROR 04 (FuncNotSupp)                 <- standby timer
  1.7   STATUS f6 0a 00 00 | f6 0a 00 01 | f6 0a 00 02   (EQ flat)
  1.9   STATUS 80 09 0e 00 09 40 02 00 00 00 00
  1.10  STATUS 07      1.11 STATUS 01 02 0f     1.24 STATUS 00     1.27 STATUS 00
  2.2   STATUS 50 ff ff 00 (80 %)
  4.4   STATUS 01 + 5 x 6-byte MAC (31 bytes)
  4.5   STATUS <6-byte MAC> 03 02 03 <name>
  5.1   STATUS 00 0f 00 (no active source)
  31.3  STATUS 02      31.8 STATUS 0b 00 d7     31.10 STATUS 00 00 02 00 01
  31.4  no reply within 3 s, and none later    <- default mode
  31.1 START drain, 19 frames in ~750 ms:
        31.1 PROCESSING, 31.0 STATUS "1.0.0", 31.2 STATUS 07 04 07 00 00 00 7f 02, 31.3 STATUS 02,
        11 x 31.6 STATUS (48 B each: presets 0-3, 4 custom modes, 3 empty slots named "None"),
        31.8 STATUS, 31.10 STATUS, 31.11 STATUS 1f ff ff ff ff, then 31.1 RESULT
  ```

- Conclusion:
  - [1.4] and [31.4] are unsupported on this firmware; removed from the CLAUDE.md feature map.
  - The 31.1 drain starts with PROCESSING and ends with `31.1 RESULT`, so M1 can end the drain on RESULT instead of only on 500 ms of silence.
  - 31.11 is new (not in the 8.2.20 notes); meaning unknown.
  - No serial number appears in any captured frame.

### 2026-10-28 M0 E3/E4: latency and burst

- Tested: 20 x GET 2.2 one at a time; 10 GETs to different addresses written back to back with no gap.
- Evidence:

  ```text
  latency n=20: min 19.8  median 27.6  max 118.7 ms
  burst sent: 2.2, 0.5, 1.7, 1.10, 1.11, 1.24, 1.27, 31.3, 31.10, 5.1   (all written within 5 ms)
  burst got:  0.5, 2.2, 1.7, 1.11, 1.10, 1.24, 1.27, 31.3, 31.10, 5.1   10/10, no errors, last at 151 ms
  read chunks: [0.5] | [2.2 + 1.7] | [1.11 + 1.10] | [1.24 + 1.27] | [31.3] | [31.10] | [5.1]
  ```

  On a link idle for 60 s, a single GET took 38 to 471 ms (E0a heartbeats).
- Conclusion: the upstream "200 ms post-send delay" isn't a device requirement. No spacing needed. Replies can come back out of order (two swaps out of 10), and several replies can share one read.

### 2026-10-28 M0 E0: idle sleep and waking

- Tested:
  - E0a: fresh link (Bluetooth toggle, then our connect), GET battery every 60 s, spike screen in front.
  - E0c: with the headphones asleep, a Bluetooth toggle, then RFCOMM connect attempts.
- Evidence:

  ```text
  E0a  ACL Connected 11:09:47.372; heartbeats #1-#10 all answered
       ACL Disconnected 11:20:17.395 reason 19 (10 min 30 s); our socket: DISCONNECT read() == -1
  E0c  Bluetooth toggle: A2DP stays Disconnected for 2+ min
       SDP connect: ACL Connected 11:06:28, socket up in 4289 ms
       reflection connect: CONNECT failed after 5134 ms (IOException), but ACL Connected 11:24:36
  ```

  Also seen at session start: ACL connected 10:57:35 and dropped 20 s later (reason 19), cause unknown.
- Conclusion:
  - A BMAP heartbeat does not keep the headphones awake; they drop the link about 10.5 min after it comes up.
  - A connect attempt from our app wakes sleeping headphones (the first attempt may fail while doing so). A Bluetooth toggle doesn't. Hands-off runs can wake them without Baris.
  - Design rule added to CLAUDE.md: never connect unless Android reports the headphones connected, so the app doesn't keep waking them in a bag.

### 2026-10-28 M0 E0b: silent audio (incomplete)

- Tested: fresh link (ACL Connected 11:36:32 after E7), SPP socket open with no heartbeat, `SilentAudioService` playing zeros from the front spike activity.
- Evidence: `mIsPlaying: true`, audio output `bt_a2dp` = the headphones. Still connected at 11:38:31. Stopped at 11:39 (2.5 min in) because Baris needed the phone.
- Conclusion: **no result.** Needs a 12+ min run from a fresh link. Not blocking: hands-off runs can wake the headphones by connecting (E0c), and in real use they're worn or playing.

### 2026-10-28 M0 S1/S2: screen with the device policy

- Tested: locked the phone (`KEYCODE_SLEEP`, keyguard showing), `am start` the spike activity, screencap, `input swipe`. During E0a, `mWakefulness` checked every 30 s for 13.5 min.
- Evidence: after `am start`: `mWakefulness=Awake`, `topResumedActivity=...SpikeActivity`, `isKeyguardShowing=true` (shown over it); screencap readable; swipe delivered. E0a: `mWakefulness=Awake` at every check up to 818 s.
- Conclusion: `setShowWhenLocked` + `setTurnScreenOn` + `FLAG_KEEP_SCREEN_ON` beat the 5-min policy cap. Hands-off runs work on a locked phone.

### 2026-10-28 M0 E5: second connection, writes, passive listen

- Tested:
  - one second SPP socket while the first was open
  - snapshot, START 31.3 to mode 0, SETGET 31.10 with ANC off, restore both, read back; 3 s watch after each write. Run straight after a fresh connect (before the listen) so the 10.5 min sleep couldn't interrupt the restore.
  - 8 min with nothing sent
- Evidence:

  ```text
  PROBE2 second socket CONNECTED in 356 ms; first socket DISCONNECT read() == -1 24 ms later
  snapshot  mode=02  31.10=00 00 02 00 01
  START 31.3 [00 00]          -> RESULT 00 in 102 ms; 31.10 now 00 00 00 00 01 (spatial off)
  SETGET 31.10 00 00 00 00 00 -> STATUS 00 00 00 00 00 in 136 ms
  START 31.3 [02 00]          -> RESULT 02 in 81 ms
  SETGET 31.10 00 00 02 00 01 -> STATUS 00 00 02 00 01 in 132 ms
  read-back mode=02 31.10=00 00 02 00 01  -> RESTORE OK
  frames in the 3 s after each of the 4 writes: 0, 0, 0, 0
  passive listen 8 min: 0 frames
  ```

- Conclusion:
  - Only one BMAP connection at a time: a new connection is accepted and the old one is dropped. Another app connecting (Bose app, Quiet Rebellion) cuts ours off, and ours cuts theirs.
  - No unsolicited frames after protocol writes or while idle. Whether physical buttons or wearing them push frames is still H1.
  - START 31.3 replies RESULT `[idx]`; SETGET 31.10 replies STATUS with the applied values.

### 2026-10-28 M0 E7: Bluetooth toggle with the socket open

- Tested: SPP socket open, headphones awake, `cmd bluetooth_manager disable`, 3 s, `enable`, then our connect once A2DP was back.
- Evidence:

  ```text
  bt off after 0.5 s; socket: DISCONNECT read() == -1 (no IOException)
  after enable: A2DP Connecting at +1.3 s, Connected at +9.3 s
  our SPP connect 108 ms; GET 2.2 answered in 424 ms
  ```

- Conclusion: on target 37 a dropped link shows up as `read() == -1`, as the Android 17 notes say. Awake headphones come back by themselves in about 9 s; connecting after A2DP is back works first time.

### 2026-10-28 M0: session notes

- `adb shell am start ... --es hex "02 02 01 00"` passes only `02`: adb shell splits on spaces. Pass hex without spaces.
- At 11:28:06 the USB connection reset (adb transport id 2 -> 3, the logcat stream ended with exit 255). Cause unknown. adb came straight back; the phone's 16 MB log buffer covered the gap.
