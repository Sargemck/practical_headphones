# CLAUDE.md

Android app to control Bose QC Ultra Headphones (2nd Gen) directly over Bluetooth. No Bose app, no cloud, no account, no internet permission.

Owner: Baris. Planner/reviewer: a separate Claude chat. Baris relays plans, PRs and review comments between us.

## Working agreement

- Work from `PLAN.md`, one milestone at a time. Never start the next milestone until Baris says the current PR is approved.
- At the start of a milestone, enter plan mode, write the plan, and stop. Baris takes it to the reviewer. Code after approval.
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

## Tech stack

- Kotlin, Jetpack Compose, Material 3 with dynamic colour (Material You), single activity.
- minSdk 31, targetSdk 36, compileSdk 36. (Play requires target 36 for new apps since 31 Aug 2026.)
- Edge-to-edge done properly: `Scaffold` + window insets. Nothing may draw under the status bar.
- Coroutines + Flow. Plain constructor injection; no DI framework unless the plan says so.
- Versions pinned in `gradle/libs.versions.toml` (version catalog). When adding or bumping a dependency (AGP, Kotlin, Compose BOM, anything), check the official release page for the current stable version. Never guess version numbers from memory.

## Dev environment

- Windows, native (not WSL). Claude Code's shell is Git Bash: use forward slashes and `./gradlew` (the sh script), not `gradlew.bat`.
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
- Hands-off device toolkit (proven in S0, see FINDINGS): `adb exec-out screencap -p` then view the PNG yourself, `adb shell input`, `adb shell uiautomator dump`, instrumented tests via `./gradlew connectedDebugAndroidTest`, `adb shell cmd bluetooth_manager disable|enable`, `adb reboot` + `adb wait-for-device`. Never send power-off or pairing-mode during hands-off runs.
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
- Classic Bluetooth RFCOMM, channel 2. BMAP service UUID `00000000-deca-fade-deca-deafdecacaff`.
- The Bose app holds the same channel. If package `com.bose.bosemusic` is installed and running, we can't connect.

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
| Multipoint | 1.10 | GET / SETGET | |
| Sidetone (Self Voice) | 1.11 | GET / SETGET | `[persist, level]`, level 0 off 1 high 2 med 3 low |
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
| Default mode | 31.4 | GET | |
| Mode config | 31.6 | STATUS 48 B / SETGET 40 B | see below |
| Favourites | 31.8 | GET / SETGET | `[total, 0, mask]` |
| Audio settings (live) | 31.10 | GET / SETGET | `[cnc, autoCnc, spatial, wind, anc]` |

**Audio settings [31.10]**
- `cnc` 0..10 is inverted: 0 = max noise cancelling, 10 = most ambient. UI shows strength = `10 - cnc` with left = let world in, right = block.
- `autoCnc` must be 0. Firmware rejects 1 with Runtime error 8.
- `spatial` 0 off, 1 fixed-to-room, 2 fixed-to-head.
- Wind Block masks CNC: with wind on, CNC level changes are inaudible. Explain this in the UI.

**Mode config [31.6]**
- STATUS (48 B): `[0]` idx, `[1:3]` voice prompt, `[3]` editable, `[4]` configured, `[5]` ?, `[6:38]` name (UTF-8, NUL padded), `[38:42]` ?, `[42]` cnc, `[43]` autoCnc, `[44]` spatial, `[45]` wind, `[46]` ?, `[47]` anc.
- SETGET (40 B): idx, prompt b1, prompt b2, name (32 B), cnc, autoCnc, spatial, wind, anc.
- Presets 0..3 (Quiet, Aware, Immersion, Cinema) are locked (Runtime error 8). Custom slots 4..10 are writable.
- Create/update/delete logic: port `create_profile`, `update_profile`, `delete_profile`, `_find_free_slot` from `connection.py`.

**Known unknowns.** Settle these (in M0 unless noted) and record the answers in FINDINGS:
1. Which connect path works on Baris's Pixel 10 Pro (Android 16).
2. Does the device need a gap between commands? Upstream says "200 ms post-send delay required". Unclear if that's a real device requirement or an artefact of their blocking reads.
3. Does the device push unsolicited STATUS frames when you press its buttons, change mode on the headphones, or take them off? (Needs Baris's hands, so it's answered in H1, not M0. Until then, code must work whether it does or not.)
4. What the device sends right after connect (greeting frames).
5. Whether firmware 10.12.12 (LE Audio / Auracast beta) changed anything vs the 8.2.20 notes.

## Transport rules

- One reader coroutine per connection, reading the socket continuously into a frame parser. The parser handles partial frames and multiple frames per read.
- Requests go through a queue with one request in flight (Mutex).
- A request completes on the first frame with the same `(fblock, func)` and op STATUS / RESULT / ERROR. PROCESSING means keep waiting. Timeout 3 s.
- Drain requests (31.1) collect matching frames until 500 ms of silence.
- Every frame that doesn't complete the in-flight request goes to an `unsolicited` SharedFlow, never to a caller.
- If M0 shows the device needs spacing between commands, add a measured minimum gap in the queue. Not a sleep before reading.
- Timeout or desync: retry once, then drop the socket and reconnect with backoff.

## Android rules

- Connect: try public `createInsecureRfcommSocketToServiceRecord(BMAP_UUID)` first. If that fails or lands on the wrong channel, fall back to reflection `createInsecureRfcommSocket(2)`. The reflection method is a non-SDK API: currently allowed at every target SDK, but it could be blocked later. Log which path was used.
- A foreground service (type `connectedDevice`) owns the connection and a `HeadphoneRepository` exposing `StateFlow<HeadphoneState>`. UI, tile and notification only observe and send intents.
- Writes are optimistic: update state immediately, send, roll back with an error snackbar if it fails.
- Writes to 31.10 build the payload from cached state. No read-before-write.
- Initial load after connect: wait for greeting frames to settle (M0 decides how), then read each feature in turn. Retry a failed read once. The UI must never show a half-loaded screen as if it were real values: show loading per section.
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
