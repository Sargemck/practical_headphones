# CLAUDE.md

Android app to control Bose QC Ultra Headphones (2nd Gen) directly over Bluetooth. No Bose app, no cloud, no account, no internet permission.

Owner: Baris. Planner/reviewer: a separate Claude chat. Baris relays plans, PRs and review comments between us.

## Working agreement

- Work from `PLAN.md`, one milestone at a time. Never start the next milestone until Baris says the current PR is approved.
- At the start of a milestone, enter plan mode, write the plan, and stop. Commit it as `docs/plans/<milestone>.md` on the milestone branch and push; the reviewer reads plans and PRs from GitHub. Code after approval.
- One branch per milestone: `m<N>-<slug>`. Open a PR into `main`. Never commit to `main` directly.
- PR description: what changed, how it was tested (unit + on-device), screenshots for UI, open questions, anything that differs from the plan and why.
- Small commits with clear messages.
- Baris's time with the phone is the scarcest resource. Follow "How testing works" in PLAN.md: JVM tests and hands-off device runs do the work; Baris is only needed for the two hands-on sessions (H1, H2). Outside those, the most you ask for is the desk setup. If you're truly blocked on a physical action, stop, explain why it can't be tested any other way, and batch every ask into one.
- Anything learned from the real device goes into `docs/FINDINGS.md` with date, firmware version, and what was observed. Facts beat assumptions: if FINDINGS contradicts this file, FINDINGS wins. Flag the conflict in the PR.
- If something in the plan turns out wrong or much harder than expected, stop and say so. Don't quietly work around it.

## Hard rules

- Only use BMAP operators GET (1), SETGET (2) and START (5). Never send SET (0). Never touch the Authentication block (18). No key extraction, no replaying captured auth, no firmware update code.
- No `INTERNET` permission. No analytics, no crash reporting SDKs.
- No JNI / native sockets.
- Never force-push. Never push to `main` after S0; everything goes through a PR.
- Don't install software on Baris's PC without saying what and why and getting a yes.
- No fixed `delay()` to wait for a reply. Replies are awaited by matching frames (see Transport).
- `:bmap` module has zero Android imports.
- The repo is public. Never commit serial numbers, full MAC addresses, paired-device names, or anything about the work profile. Fixtures and logs are redacted before committing: MACs keep their format with the bytes replaced, names and serials replaced, and every redaction listed in the fixtures README.

## Tech stack

- Kotlin, Jetpack Compose, Material 3 with dynamic colour (Material You), single activity.
- minSdk 31, targetSdk 37, compileSdk 37 (SDK package `platforms;android-37.0`). Baris's phone runs Android 17. (Play requires at least target 36 for new apps since 31 Aug 2026.)
- Edge-to-edge done properly: `Scaffold` + window insets. Nothing may draw under the status bar.
- Coroutines + Flow. Plain constructor injection; no DI framework unless the plan says so.
- Versions pinned in `gradle/libs.versions.toml` (version catalog). When adding or bumping a dependency (AGP, Kotlin, Compose BOM, anything), check the official release page for the current stable version. Never guess version numbers from memory.

## Dev environment

- Windows, native (not WSL). Claude Code's shell is Git Bash: use forward slashes and `./gradlew` (the sh script), not `gradlew.bat`.
- Git Bash rewrites arguments starting with `/` into Windows paths, which breaks adb device paths like `/sdcard/...`. `.claude/settings.json` sets `MSYS_NO_PATHCONV=1` for every command. Side effect: pass local paths to Windows programs as `C:/...` or relative, never `/c/...` (see FINDINGS). In Git Bash, `sdkmanager` is `sdkmanager.bat`.
- Editor is VS Code. No Android Studio. Everything must work from the command line: no reliance on Compose previews, Layout Inspector or the Studio debugger. Use the debug-build demo mode on the phone instead of previews.
- JDK 21 (Temurin). `JAVA_HOME` set as a user env var.
- Android SDK, command-line tools only, at `%LOCALAPPDATA%\Android\Sdk` (`$LOCALAPPDATA/Android/Sdk` in Git Bash). `ANDROID_HOME` set; `platform-tools` and `cmdline-tools/latest/bin` on PATH. `local.properties` is gitignored.
- No global Gradle install. Always `./gradlew`.
- Env var changes don't reach a running Claude Code session or VS Code. After changing them, ask Baris to restart VS Code, then continue.
- Device: Baris's Pixel 10 Pro over USB, or wireless debugging. If `adb devices` shows `unauthorized`, ask Baris to accept the prompt on the phone.
- Commands (also VS Code tasks in `.vscode/tasks.json`):
  - `./gradlew :bmap:test`: fast unit tests
  - `./gradlew test lint assembleDebug`: full check, must pass before every PR
  - `./gradlew installDebug`, then `adb shell am start -n <applicationId>/.MainActivity`
  - `adb logcat -c && adb logcat -s BMAP:* AndroidRuntime:E`
- Hands-off device toolkit (proven in S0, see FINDINGS): `adb exec-out screencap -p` then view the PNG yourself, `adb shell input`, `adb shell uiautomator dump`, instrumented tests via `./gradlew connectedDebugAndroidTest`, `adb shell cmd bluetooth_manager disable|enable`, `adb shell am kill <pkg>` for process death. Never `force-stop` our own app to simulate process death: a force-stopped app isn't woken by the system. `adb reboot` is not hands-off on this phone (USB debugging prompt + PIN after boot), so reboot tests happen in H2. Never send power-off or pairing-mode during hands-off runs.
- The screen locks after at most 5 min idle (device policy; "Stay awake" can't be enabled). Debug builds keep our activity on screen and above the lock screen; see Android rules.
- The headphones drop the link about 10.5 min after it comes up when idle off-head, even with BMAP traffic (M0 FINDINGS). Every hands-off run starts by checking they're connected. If they're asleep, wake them with a spike connect (a connect attempt wakes them, M0 E0c). That's allowed for debug tooling only; the app itself follows the connect rule in Android rules. Only if the spike connect fails is it one batched ask.
- Permissions for routine commands live in `.claude/settings.json`. Pushing, uninstalling apps and winget installs still ask Baris.

## Module layout

```
:bmap      pure Kotlin/JVM. Protocol codec, frame parser, device configs, parsers/builders,
           connection API over an abstract byte transport. Unit tested.
           testFixtures: FakeBmapDevice (in-memory simulator of a QC Ultra 2).
:app       Android. RFCOMM transport, foreground service, state repository, Compose UI,
           Quick Settings tile, notification, companion-device auto-connect.
(:wear     v2, not now)
reference/bosectl   gitignored clone of github.com/aaronsb/bosectl. Read-only reference.
```

## Protocol facts (QC Ultra 2, codename "wolverine", PID 0x4082)

Source: `reference/bosectl/NOTES.md`, `python/pybmap/devices/qc_ultra2.py`, `parsers.py`, `connection.py`. Port the logic from the Python; ignore the Rust/C++.

**Link**
- Classic Bluetooth RFCOMM, channel 2, reached via SDP with the SPP UUID (see Android rules). The advertised BMAP service UUID `00000000-deca-fade-deca-deafdecacaff` resolves to a different, non-BMAP channel on fw 10.12.12.
- One BMAP connection at a time: a new connection is accepted and the previous one is dropped (M0). The Bose app or Quiet Rebellion connecting cuts us off with `read() == -1`, and our connect cuts them off. Don't fight another app for the link; see the contention rule in Android rules.

**Frame:** `[fblock][func][flags][len][payload...]`
- flags = `deviceId<<6 | port<<4 | op` (we send 0 for deviceId/port). len is 1 byte.
- Ops: 0 SET, 1 GET, 2 SETGET, 3 STATUS, 4 ERROR, 5 START, 6 RESULT, 7 PROCESSING.
- ERROR payload[0] codes: 1 Length, 3 FblockNotSupp, 4 FuncNotSupp, 5 auth required, 6 InvalidData, 7 DataUnavail, 8 Runtime, 9 Timeout, 10 InvalidState.

**Feature map**

| Feature | Addr | Ops | Notes |
|---|---|---|---|
| Firmware | 0.5 | GET | ASCII |
| Product name | 1.2 | GET / SETGET | GET payload `[flag, utf8...]`, max 31 bytes |
| EQ | 1.7 | GET / SETGET | GET: 3x `[min,max,current,bandId]`. SETGET `[value(signed), bandId]`, bands 0 bass 1 mid 2 treble, -10..10 |
| Buttons | 1.9 | GET only | SETGET echoes but is ignored (needs cloud auth). Don't build remapping. |
| Multipoint | 1.10 | GET / SETGET | GET is a bitfield (fw 10.12.12 sends `07`), 0x02 = enabled. Write semantics unverified: no builder until M2's hardware test |
| Sidetone (Self Voice) | 1.11 | GET / SETGET | GET `[persist, level, ?]` (fw 10.12.12 sends `01 02 0f`; third byte unverified, probably supported levels). SETGET `[persist, level]`. Level 0 off 1 high 2 med 3 low |
| Auto play/pause | 1.24 | GET / SETGET | bool |
| Auto answer | 1.27 | GET / SETGET | bool |
| Battery | 2.2 | GET | percent |
| Paired devices | 4.4 | GET | |
| Device info | 4.5 | GET(mac) | name lookup |
| Pairing mode | 4.8 | START `[1]` | device drops connection |
| Routing | 4.12 | START | only works while both sources stream; see fork's `switchToDevice` |
| Source | 5.1 | GET | |
| Power off | 7.4 | START `[0]` | |
| All modes | 31.1 | START + drain | returns N x STATUS on 31.6 |
| Current mode | 31.3 | GET / START `[idx, 0]` | SETGET needs auth; use START |
| Mode config | 31.6 | STATUS 48 B / SETGET 40 B | see below |
| Favourites | 31.8 | GET / SETGET | `[total, maskHi, maskLo]`, bit i = mode i. 16-bit byte order inferred (fw 10.12.12 sends `0b 00 d7`); confirmed in M4 |
| Audio settings (live) | 31.10 | GET / SETGET | `[cnc, autoCnc, spatial, wind, anc]` |

Not supported on firmware 10.12.12 (M0 FINDINGS), so not in the map: [1.4] standby timer (ERROR FuncNotSupp) and [31.4] default mode (no reply).

**Audio settings [31.10]**
- `cnc` 0..10 is inverted: 0 = max noise cancelling, 10 = most ambient. UI shows strength = `10 - cnc` with left = let world in, right = block.
- `autoCnc` must be 0. Firmware rejects 1 with Runtime error 8.
- `spatial` 0 off, 1 fixed-to-room, 2 fixed-to-head.
- Wind Block masks CNC: with wind on, CNC level changes are inaudible. Explain this in the UI.
- A mode switch changes the live audio settings (M0 E5: spatial went from 02 to 00 on START 31.3). After START 31.3 succeeds, re-read 31.10 before updating state. Never keep the cached 31.10 across a mode switch.

**Mode config [31.6]**
- STATUS (48 B): `[0]` idx, `[1:3]` voice prompt, `[3]` editable, `[4]` configured, `[5]` ?, `[6:38]` name (UTF-8, NUL padded), `[38:42]` ?, `[42]` cnc, `[43]` autoCnc, `[44]` spatial, `[45]` wind, `[46]` ?, `[47]` anc.
- SETGET (40 B): idx, prompt b1, prompt b2, name (32 B), cnc, autoCnc, spatial, wind, anc.
- Presets 0..3 (Quiet, Aware, Immersion, Cinema) are locked (Runtime error 8). Custom slots 4..10 are writable.
- Create/update/delete logic: port `create_profile`, `update_profile`, `delete_profile`, `_find_free_slot` from `connection.py`.

**Known unknowns.** Settle these (in M0 unless noted) and record the answers in FINDINGS:
1. Settled in M0: connect path (see Android rules and FINDINGS).
2. Settled in M0: no gap between commands is needed; replies can arrive out of order.
3. Does the device push unsolicited STATUS frames when you press its buttons, change mode on the headphones, or take them off? (Needs Baris's hands, so it's answered in H1, not M0. Until then, code must work whether it does or not.)
4. Settled in M0: nothing arrives on channel 2 after connect until we ask.
5. Settled in M0: firmware 10.12.12 differs from the 8.2.20 notes in [1.4] and [31.4] (see feature map).

## Transport rules

- One reader coroutine per connection, reading the socket continuously into a frame parser. The parser handles partial frames and multiple frames per read.
- `read() == -1` is a disconnect, same as an `IOException`. Apps targeting Android 17 get `-1` when an RFCOMM connection drops instead of only an exception.
- Headphones going to sleep is a normal disconnect, not an error. The service goes quiet and reconnects when they wake.
- Requests go through a queue with one request in flight (Mutex).
- A request completes on the first frame with the same `(fblock, func)` and op STATUS / RESULT / ERROR. PROCESSING means keep waiting. Timeout 3 s.
- Drain requests (31.1) collect the 31.6 STATUS frames and complete on `31.1 RESULT` (M0 E6). 500 ms of silence is only a fallback end.
- Every frame that doesn't complete the in-flight request goes to an `unsolicited` SharedFlow, never to a caller.
- No spacing between commands (M0: 10 back-to-back GETs all answered). Replies can arrive out of order, so never assume reply order matches send order; matching stays by `(fblock, func)`.
- An address that times out while other requests are answered is unsupported (e.g. [31.4]), not an error. It doesn't trigger a reconnect.
- Timeout or desync on a supported address: retry once, then drop the socket. Reconnect follows the connect rule in Android rules.

## Android rules

- Connect (M0 FINDINGS E1/E8): primary is public `createInsecureRfcommSocketToServiceRecord(SPP_UUID)` with the standard SPP UUID `00001101-0000-1000-8000-00805f9b34fb`, which reaches BMAP. Never SDP-connect with the BMAP UUID: on fw 10.12.12 it lands on a beacon channel that never answers. A connect counts only once a BMAP request is answered. If SPP fails or doesn't answer, fall back to reflection `createInsecureRfcommSocket(2)` (non-SDK API: allowed at every target SDK today, could be blocked later). Log which path was used.
- Never initiate an RFCOMM connect unless Android reports the headphones' ACL/A2DP as connected. No retry loops, no polling connects. A connect attempt wakes sleeping headphones (M0 FINDINGS), so retrying would keep them awake in a bag and drain the battery. Automatic connects happen only on a fresh ACL/A2DP connected event from the system.
- Contention: if our socket drops (`read() == -1`) while Android still reports the headphones' ACL connected, assume another app took over the link. Don't auto-reconnect. Set a state the UI shows as "Another app is using your headphones" with a Reconnect button. Only a user tap or a fresh connected event connects again. Exception: a `Closed(RemoteClosed)` within ~5 s after we sent power off or pairing mode is expected; the state shows "Headphones off" or "Pairing mode" instead.
- A foreground service (type `connectedDevice`) owns the connection and a `HeadphoneRepository` exposing `StateFlow<HeadphoneState>`. UI, tile and notification only observe and send intents.
- Writes are optimistic: update state immediately, send, roll back with an error snackbar if it fails.
- Writes to 31.10 build the payload from cached state. No read-before-write.
- Initial load starts immediately after connect (no greeting frames, M0), reading each feature in turn. Retry a failed read once. The UI must never show a half-loaded screen as if it were real values: show loading per section.
- Debug builds only: the debug entry activity sets `FLAG_KEEP_SCREEN_ON`, `setShowWhenLocked(true)` and `setTurnScreenOn(true)` so hands-off runs work on a locked phone. Release builds must not have these.
- The app isn't direct-boot aware. Nothing of ours runs before the first unlock after a reboot.
- Permissions: `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, plus companion-device permissions from M6. `<queries>` entry for `com.bose.bosemusic`.

## UX copy

- Plain English, one line under every setting saying what it does. No protocol words in the UI.
- Use Bose's names: "Self Voice", not "sidetone". For spatial: Off / Still (sound stays put when you turn your head, = fixed-to-room) / Motion (sound moves with you, = fixed-to-head). Mapping is inferred from the protocol enum names; confirm by ear in M3.
- Hide anything we can't explain confidently (e.g. Self Voice persistence flag).
- Preset modes show as read-only with a short "Bose preset, can't be edited" note.

## Logging

- Tag `BMAP`. Debug builds log every frame in and out as hex with direction and timestamp. Release builds log errors only.

## Licence

- Our code: MIT. Protocol knowledge and ported logic come from bosectl (MIT, (c) aaronsb/bosectl contributors). Keep a `NOTICE` file and an About screen credit.
- Never put "Bose" in the app name or icon. "for Bose QC Ultra" in descriptions is fine.
