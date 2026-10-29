# PLAN.md

Scope is v1 below. v2/v3 are listed at the end so nothing designs them out, but don't build them.

Each milestone: plan mode, reviewer approves plan, build, PR, reviewer approves PR, merge.

## How testing works (read this first)

Baris's time on the phone is the scarcest resource in this project. Testing is layered so he's only needed twice.

1. **JVM tests, every change.** `:bmap` unit tests against real fixtures and `FakeBmapDevice`. Compose UI tests and screenshot tests (Robolectric + Roborazzi or equivalent) against the fake. Claude Code looks at the screenshots itself: light, dark, largest font.
2. **Hands-off device runs, Claude Code alone.** Needs only the *desk setup* (below). Claude Code installs, launches, drives the UI with adb, takes screenshots, reads logcat, and runs instrumented hardware tests against the real headphones: read everything, write, read back, restore original values. Claude Code can also toggle phone Bluetooth and reboot the phone to test reconnect and auto-connect.
3. **Hands-on sessions, Baris.** Only for what needs ears, hands on the headphones, wearing them, or his judgement. There are exactly two: **H1** after M3, **H2** after M7. Each comes with a prepared checklist and should take under 45 minutes.

Rules:
- Never ask Baris for a physical action outside H1/H2. If something truly can't proceed without one, stop, explain why, and batch everything needed into one ask.
- Anything that can't be verified until H1/H2 gets listed in the PR under "Deferred to H1/H2" so nothing gets forgotten.
- Never send power-off or pairing-mode during hands-off runs. Nobody's there to turn the headphones back on.

**Desk setup** (Claude Code asks for it at the start of a hands-off run; Baris sets it up and walks away):
- Pixel on the USB cable, phone unlocked at the start of the run ("Stay awake" is blocked by device policy; debug builds keep our screen on and show over the lock screen)
- Headphones on the desk, powered on, connected to the phone
- Bose app and Quiet Rebellion not running

Every hands-off run starts by checking the headphones are connected. They drop the link about 10.5 min after it comes up when idle off-head. If they're asleep, Claude Code wakes them with a spike connect (debug tooling only). Only if that fails is it one batched ask to Baris.

Definition of done for every milestone:
- `./gradlew test lint assembleDebug` green
- New logic in `:bmap` has unit tests; new UI has screenshot tests
- Hands-off device run for that milestone passed, with a log excerpt or screenshots in the PR
- `docs/FINDINGS.md` updated if anything was learned from the device

## Before S0 (Baris, ~20 min, can't be done by Claude Code)

1. Install **Git for Windows** (git-scm.com, defaults are fine). Claude Code uses its Git Bash as its shell.
2. Install **VS Code**.
3. Install **Claude Code**: in PowerShell `irm https://claude.ai/install.ps1 | iex`, open a new terminal, `claude --version`, then run `claude` once and log in.
4. **Phone:** Settings > About phone > tap Build number 7 times. Then Settings > System > Developer options > turn on USB debugging and "Stay awake". Plug the Pixel into the PC with a data cable.
5. Make an empty folder, e.g. `C:\dev\qc-app`. Put `CLAUDE.md` and `PLAN.md` in it. Open the folder in VS Code, open the terminal (Ctrl+`), run `claude`.

## Decisions (Baris)

- **D1 App name + package ID.** Done: Practical Headphones, `io.github.sargemck.practicalheadphones`.
- **D2 GitHub repo** (private is fine). Created in S0. Tell the reviewer `owner/repo` so PRs can be reviewed directly.

---

## S0: Dev environment (Claude Code, 1 evening)

The only milestone that commits straight to `main` (the repo is empty). No PR; Baris sends the reviewer the summary.

1. **Inventory first.** Report what's installed and what's missing: `java -version`, `git`, `gh`, `adb`, `sdkmanager`, `ANDROID_HOME`, `JAVA_HOME`, free disk space. Propose the install list. Wait for Baris's yes.
2. **JDK 21** (Temurin) via winget if missing. Set `JAVA_HOME` as a user env var.
3. **Android command-line tools.** Get the current Windows zip link from developer.android.com/studio ("Command line tools only"). Extract so `sdkmanager` ends up at `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin`. Set `ANDROID_HOME`; add `platform-tools` and `cmdline-tools\latest\bin` to the user PATH (PowerShell `[Environment]::SetEnvironmentVariable(..., "User")`).
4. **Restart.** Ask Baris to restart VS Code so the new env vars load, then resume.
5. **SDK packages:** `sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;<latest 36.x>"`. Accept licences (`yes | sdkmanager --licenses`).
6. **Phone link.** `adb devices` must list the Pixel as `device`.
   - `unauthorized`: ask Baris to tap Allow on the phone.
   - Not listed: try `sdkmanager "extras;google;usb_driver"` and walk Baris through the Device Manager driver update, or switch to wireless debugging (`adb pair`, `adb connect`).
7. **Prove the hands-off toolkit works** on the Pixel (Android 17), and record results in FINDINGS:
   - screenshot: `adb exec-out screencap -p > shot.png`, then view it
   - UI driving: `adb shell input tap/swipe/keyevent`, and `adb shell uiautomator dump` to find elements
   - Bluetooth toggle: `adb shell cmd bluetooth_manager disable` / `enable`. Do the headphones reconnect on their own afterwards? (Unverified on Android 17; may need a different command.)
   - reboot: `adb reboot`, then `adb wait-for-device`; time until `adb devices` is back
   - Do the headphones stay on while idle on the desk, or auto-off after a while? Note the time.
8. **Git:** `git init`. `.gitignore` covering the standard Android entries, `local.properties`, `*.jks`, `*.keystore`, `reference/`, `.claude/settings.local.json`, `build/`, `.gradle/`, `*.png` in the repo root (scratch screenshots). First commit with `CLAUDE.md` and `PLAN.md`.
9. **GitHub:** install `gh` via winget if Baris agrees. `gh auth login` is Baris's to do (browser). Create a private repo, push `main`.
10. **Reference:** `git clone https://github.com/aaronsb/bosectl reference/bosectl`.
11. **Gradle wrapper bootstrap:** download the current stable Gradle distribution to a temp folder, run `gradle wrapper` in the repo, delete the temp folder. From then on only `./gradlew`.
12. **VS Code workspace:**
    - `.vscode/extensions.json`: recommend Kotlin by JetBrains, Gradle for Java, Claude Code. Look up the real Marketplace IDs; don't guess.
    - `.vscode/tasks.json`: "Unit tests (bmap)", "Full check", "Install & launch", "Logcat BMAP" (the commands in CLAUDE.md).
    - `.vscode/settings.json`: exclude `**/build`, `**/.gradle`, `reference/` from search and the file watcher.
13. **`.claude/settings.json`:** exactly this, commit it:
    ```json
    {
      "$schema": "https://json.schemastore.org/claude-code-settings.json",
      "permissions": {
        "allow": [
          "Bash(./gradlew *)",
          "Bash(adb devices *)",
          "Bash(adb install *)",
          "Bash(adb logcat *)",
          "Bash(adb exec-out screencap *)",
          "Bash(adb shell input *)",
          "Bash(adb shell uiautomator dump *)",
          "Bash(adb shell am start *)",
          "Bash(adb shell am force-stop *)",
          "Bash(adb shell am instrument *)",
          "Bash(adb shell cmd bluetooth_manager *)",
          "Bash(adb wait-for-device *)",
          "Bash(adb pull *)",
          "Bash(git status *)",
          "Bash(git diff *)",
          "Bash(git log *)",
          "Bash(git add *)",
          "Bash(git commit *)",
          "Bash(git switch *)",
          "Bash(git branch *)",
          "Bash(gh pr create *)",
          "Bash(gh pr view *)"
        ],
        "ask": [
          "Bash(git push *)",
          "Bash(adb reboot *)",
          "Bash(adb uninstall *)",
          "Bash(adb shell pm *)",
          "Bash(winget *)"
        ],
        "deny": [
          "Bash(git push --force *)",
          "Bash(git push -f *)"
        ]
      }
    }
    ```
14. **`docs/FINDINGS.md`** stub with an entry template: date, firmware, phone + Android version, what was tested, raw evidence (hex/log lines), conclusion.

Done when:
- `./gradlew --version` runs and reports JDK 21
- `adb devices` shows the Pixel as `device`
- `sdkmanager --list_installed` shows the packages
- The hands-off toolkit checks are recorded in FINDINGS
- VS Code shows the four tasks and offers the recommended extensions
- Repo is on GitHub with `main` pushed

Baris then installs the recommended VS Code extensions when VS Code offers them.

---

## M0: Spike and device truths (hands-off)

Goal: answer the unknowns in CLAUDE.md before building anything real. Everything here runs with the desk setup only.

Build:
- Gradle project with `:bmap` and `:app`, minSdk 31, compileSdk/targetSdk 37, Compose, Material 3.
- `:bmap`: frame codec + streaming frame parser with unit tests (partial frames, multiple frames per chunk, zero-length payloads, garbage).
- `:app`: a throwaway debug path, driven from Claude Code. Either an Activity with intent extras or an instrumented test, whichever is easier to script. It must: connect via a chosen path (SDP / reflection), log every frame in/out to logcat (hex, ms timestamps), send arbitrary hex, run a burst of N GETs back-to-back. The RFCOMM read loop treats `read() == -1` as a disconnect, as well as `IOException`.
- Debug entry activity sets `FLAG_KEEP_SCREEN_ON`, `setShowWhenLocked(true)`, `setTurnScreenOn(true)`. Debug builds only.
- Check the VS Code "Install & launch" task's applicationId (set in S0) matches the debug build.
- Fixtures are redacted before committing (see the public-repo rule in CLAUDE.md), with the redactions listed in the fixtures README.

Screen checks (F6):
- With the debug activity in front for 7+ min, the screen stays on despite the 5-min policy cap.
- With the phone locked, `am start` brings the debug activity up over the lock screen, and screencap and input still work.

Experiments (all hands-off; ask Baris to uninstall Quiet Rebellion or force-stop it with adb first):
0. Keep-awake, run before experiment 5. The headphones sleep after 10 min idle off-head (S0 FINDINGS).
   - a. BMAP heartbeat: GET battery every 60 s. Still connected at 12 min?
   - b. If not: silent audio from a debug-only foreground service. Still connected at 12 min?
   - c. Once they've slept: does a Bluetooth toggle or a connect attempt wake them, or is a physical touch needed?
   - d. GET [1.4] (StandbyTimer, auto-off timer per bosectl NOTES.md). Read only, never write.
1. Connect via SDP. Then reflection. Which works, and which channel does SDP land on?
2. Greeting: what arrives in the first 2 s after connect, untouched?
3. Latency: send GET battery with no delay. Time to reply? Run 20 times, record min/median/max.
4. Burst: 10 GETs back-to-back with no gap. All answered and in order? If not, find the smallest gap that works.
5. Passive listen: connect, send nothing for 8 minutes (or longer under whichever keep-awake method works), record any unsolicited frames. Probe a second RFCOMM socket once while the first is open and record the error (expected to be refused). Then timed writes on the single connection: snapshot mode and 31.10, change mode (START 31.3) and ANC (SETGET 31.10), log every frame for 3 s after each write's reply (anything beyond the matching reply is unsolicited), restore, confirm by read-back. If the restore fails, stop and report.
6. Firmware: GET 0.5. Run GET on every address in the feature map and save the raw payloads into `:bmap` test fixtures.
7. Toggle phone Bluetooth off/on: does the socket die cleanly, and how long until the headphones reconnect?
8. Public connect path (added after F9): `fetchUuidsWithSdp()`, log the headphones' UUIDs, SDP-connect to each vendor UUID and SPP once, record what each lands on and whether it answers BMAP. If one does, it becomes the primary path and reflection the fallback.

Not tested here: whether the headphones push frames when their *physical* buttons are used or when worn/removed. That needs Baris, so it moves to H1. M2 is designed to work either way.

Deliverable: `docs/FINDINGS.md` with results. The PR includes the fixture files.

Review gate: reviewer reads FINDINGS and adjusts the plan before M1 starts.

## M1: `:bmap` library

- Device config for QC Ultra 2 as data (addresses + parser/builder refs), structured so other models are just another config later.
- Parsers/builders for every row in the feature map, including mode config 48 B STATUS / 40 B SETGET, favourites, EQ, sidetone, source, paired devices.
- `BmapConnection` over an abstract `FrameTransport` interface. Request/reply matching, PROCESSING handling, drain (31.1 ends on `31.1 RESULT`, 500 ms silence as fallback), timeout, retry-once, unsolicited flow.
- Profile logic ported from upstream: create, update, rename, delete, find free slot, refuse preset names, refuse presets 0–3.
- `FakeBmapDevice` in testFixtures: answers like the real device using M0 fixtures; can emit unsolicited frames; can inject errors and delays.
- Tests: every parser against real M0 fixtures; connection tests against the fake (stray frames, desync, timeout, PROCESSING then STATUS, drain).

Hands-off device run: none. Pure library.

## M2: Connection service and state

- Android `RfcommTransport` implementing `FrameTransport`, with the connect strategy M0 proved.
- Foreground service (`connectedDevice`) owning connection + `HeadphoneRepository` with `StateFlow<HeadphoneState>`.
- Initial load starts right after connect (M0: no greeting frames), with per-section loading state and retry. **Fixes bug 1** (first connect doesn't load modes/spatial).
- Optimistic writes from cached state, no read-before-write. **Fixes bug 2** (ANC lag, app's share of it). Reconcile against what `Headphones.write()` returns: the applied value where the reply is parsed, otherwise the raw reply.
- Only offer modes from `allModes()` that are configured (name not blank or `None`). Never call `selectMode` on an empty slot.
- After a mode switch (START 31.3) succeeds, re-read 31.10 before updating state: a mode switch changes the live audio settings (M0 E5). Otherwise the slider and spatial buttons go stale.
- Live updates, built both ways because we won't know until H1 which one the device needs:
  - map any unsolicited frames into state
  - light polling of current mode + 31.10 + battery while the UI or notification is visible, behind a flag so it can be dialled down or switched off after H1
- Automatic connects only on a fresh ACL/A2DP connected event. No retry loops or polling connects: a connect attempt wakes sleeping headphones (M0). Clean stop when the headphones disconnect from the phone.
- Contention: if our socket drops while Android still reports the headphones connected, assume another app (Bose app, Quiet Rebellion) took over. Don't auto-reconnect; state shows "Another app is using your headphones" with a Reconnect button. Avoids two apps kicking each other off in a loop (M0: one connection at a time). Exception: a `Closed(RemoteClosed)` within ~5 s after `powerOff()` or `pairingMode()` is expected; state shows "Headphones off" or "Pairing mode" instead.
- Headphones going to sleep is a normal disconnect, not an error: the service goes quiet and reconnects when they wake.
- Detect `com.bose.bosemusic` installed: state flag for the UI warning.
- Notification: name, battery, mode; actions for next favourite mode and ANC toggle.

Hands-off device run:
- Cold start 10 times in a row (force-stop, launch): everything loaded every time, verified from logcat state dumps.
- Instrumented hardware test: read all, then each write below as read → write → read back → restore, all logged with the reply frame and the time from write to confirmed read-back:
  - 1.2 product name: a test name, then the original
  - 1.7 EQ: one band at a time
  - 1.11 Self Voice
  - 1.24 auto play/pause, 1.27 auto answer
  - 31.8 favourites: the current value written back unchanged
  - 1.10 multipoint exploration: SETGET with the current raw value (expect no change), then with only bit 0x02 flipped, read back, restore the original. If the meaning isn't clear after that, multipoint is read-only in v1.
- Not in M2: 31.6 (M4), 4.8 pairing and 7.4 power off (H1 only, never in hands-off runs).
- Bluetooth toggle off/on: service reconnects without the app being opened.
- Notification actions fired via adb: state changes confirmed by read-back.

## M3: Main UI

- Compose, Material 3, dynamic colour, follows system dark mode, edge-to-edge with insets. **Fixes bug 3** (title under status bar).
- Sections:
  - **Header:** name, battery, connection state
  - **Modes:** list, current selected, favourites star, presets marked read-only
  - **Noise control:** strength slider, Wind Block with note, Spatial Off / Still / Motion
  - **Sound:** EQ with named presets (Flat, Podcast/voice, Bass boost, Treble boost, Custom); exact values are a proposal for review
  - **Calls:** Self Voice level, Auto answer
  - **Device:** auto play/pause, multipoint, rename, pairing mode, power off
- One-line plain-English explanation under every setting. **Fixes bug 5** (jargon).
- No truncated buttons at default and largest font size. **Fixes bug 4** (Power Off cut off).
- Bose app warning banner with "open app settings" button.
- About screen: licence, bosectl credit, firmware version.
- Demo mode in debug builds using `FakeBmapDevice`.

Tests: screenshot tests for every section in light, dark and largest font, against the fake.

Hands-off device run: install, drive every control via adb input, screenshot each state, confirm each write by read-back in logcat. Skip power off and pairing mode.

### H1: hands-on session after M3 (Baris, ~45 min)

Claude Code prepares a numbered checklist before asking. Claude Code watches logcat throughout and writes results into FINDINGS.

- **By ear:**
  - noise slider end to end; wind block on/off with the slider; spatial Off / Still / Motion (confirm label mapping)
  - EQ presets
  - perceived lag on the slider vs Quiet Rebellion
- **Hands on the headphones:**
  - press the shortcut button, press-and-hold, swipe volume, change mode with the button
  - take them off and put them back on
  - turn them off and on
  - Does the app follow along, and do unsolicited frames show up in the log? This decides whether polling stays.
- **Power off and pairing mode** from the app.
- **UX opinion:** walk through the whole app; anything confusing, ugly, missing. Becomes a short fix list folded into M4.

## M4: Custom mode editor (+ H1 fixes)

- Fix list from H1.
- Create a mode in a free slot (4–10): name, strength, spatial, wind, ANC on/off.
- Edit, rename, delete custom modes. Presets 0–3 read-only.
- Full slots: clear message, no silent failure.
- Name validation: max 31 UTF-8 bytes, no duplicate of a preset name.

Hands-off device run: create, edit, switch to, delete a test mode; verify via read-back; leave Baris's existing modes untouched (snapshot before, compare after).
- Favourites byte order: create a test mode in slot ≥ 8, favourite it, read back 31.8 to confirm the 16-bit mask byte order, restore favourites, delete the mode.

## M5: Quick Settings tile

- Tile shows current mode; tap cycles favourite modes; long-press opens the app.
- Unavailable state when not connected.
- Works with the app closed (talks to the service).

Hands-off device run: add the tile programmatically if possible (else defer adding to H2), tap via adb with the app force-stopped, confirm mode change by read-back.

## M6: Auto-connect

- First-run companion-device association (CompanionDeviceManager) for the headphones.
- Observe device presence; when the headphones connect, start the service and connect. When they disconnect, stop the service and drop the notification.
- Must replace M2's always-on service before release. M2 keeps the service up as "Not connected" while the headphones are away. Play's policy for the `connectedDevice` foreground service type expects active use with a connected device, so an idle always-on service would likely fail review. After M6 the service runs only while the headphones are present.
- Spike first: verify the presence APIs, permissions and Bluetooth Classic behaviour against current Android docs. If it doesn't work reliably, fall back to an `ACTION_ACL_CONNECTED` receiver and record why in FINDINGS. Reviewer approves the approach before full build.
- The association dialog needs one tap on the phone. If it can't be driven via adb input, it's the one allowed interruption in M6: ask once, batched.

Hands-off device run:
- `adb shell am kill <pkg>` (process death; never force-stop, a force-stopped app isn't woken by the system), then Bluetooth toggle off/on, never open the app: headphones reconnect → service up within ~10 s (logcat).
- Bluetooth toggle off: notification gone. On: back.
- Reboot auto-connect is not hands-off on this phone (USB debugging prompt + PIN after boot, S0 FINDINGS). It moves to H2.

## M7: Release prep

- Name, icon, final package ID (D1).
- Release signing; keystore backed up outside the repo.
- Privacy policy page: collects nothing, no network.
- F-Droid metadata (fastlane folder) + reproducible build check.
- Play: org vs personal account decision is Baris's. Personal accounts need 12 testers opted in for 14 days in closed testing before production.
- README with screenshots (from the screenshot tests), credits, "not affiliated with Bose".

### H2: hands-on session after M7 (Baris, ~30 min, then daily use)

- Install the release build.
- Custom modes by ear; headphone button cycles favourites; QS tile.
- Real auto-connect: headphones off overnight, on in the morning, app never opened.
- Reboot auto-connect (moved from M6): reboot the phone, unlock, never open the app: headphones reconnect → service up within ~10 s.
- Then use it as the daily driver for a week. Note annoyances; they become v1.1.

---

## Bug list from Quiet Rebellion, mapped

| # | Bug | Fixed in |
|---|---|---|
| 1 | First connect doesn't load modes/spatial; Reconnect fixes it | M2 |
| 2 | ANC changes lag (read-then-write + fixed waits) | M2 |
| 3 | Title bar under the status bar | M3 |
| 4 | Power Off button text cut off | M3 |
| 5 | Jargon labels, no explanations | M3 |

## Later, don't build now

- **v2:** Pixel Watch tile (`:wear`), more Bose models via upstream configs, "send device report" button, plain-English error messages.
- **v3:** context automations (mode by activity / Wi-Fi / location).
- **Requested by Baris (2026-10-28), not scheduled:** explicit source switching between two devices (e.g. PC and phone), well featured. Example problem: gaming on the PC, a work email notification on the other device takes the audio for 3-5 s. Needs a design spike before any milestone; it conflicts with the "multipoint switching polish" line below, so the reviewer decides scope. Known so far:
  - Building blocks: paired devices [4.4], device names [4.5], active source [5.1], routing START [4.12] (bosectl: works only while both sources stream).
  - The interruption case happens exactly while both stream, so "switch back to the locked source" may fit inside the window [4.12] allows. Unverified; needs two sources and Baris, so it's an H-session test.
  - Phone-side alternatives: a normal app can't turn off the phone's media audio profile for the headphones (system-only API). It can only send the user to the Bluetooth settings screen.
- **Never:** button remapping (needs cloud auth), firmware updates, multipoint switching polish (protocol only allows a brief window).
