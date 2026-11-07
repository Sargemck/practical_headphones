# Practical Headphones

An Android app for the Bose QuietComfort Ultra Headphones (2nd Gen). It talks to the headphones directly over Bluetooth. You don't need the Bose app or an account.

Not affiliated with Bose. Bose and QuietComfort are trademarks of Bose Corporation.

| Main screen | All modes | New mode |
|---|---|---|
| ![Main screen](app/src/test/screenshots/main-light.png) | ![All modes](app/src/test/screenshots/all-modes-sheet-light.png) | ![New mode](app/src/test/screenshots/mode-editor-new-light.png) |

## What it does

- **Modes:**
  - switch modes
  - choose which ones the headphones' button cycles through
  - create, edit and delete custom modes (a new mode starts from what you're hearing)
- **Noise control:** how much outside sound gets through, Wind Block, noise cancelling, and spatial audio (Off, Still, Motion).
- **Equaliser:** presets, or bass, mid and treble.
- **Calls:** Self Voice and auto answer.
- **Headphones:** auto play/pause, rename, pairing mode and power off. Shows battery and multipoint.
- **Quick Settings tile:** shows the current mode. A tap goes to the next starred mode.
- **Notification:** battery, current mode, next mode, noise cancelling on/off.
- **Auto-connect:** with it set up, the app starts when the headphones connect, so the tile and notification work without opening it first.
- **While the headphones are away:** the last settings read from them stay on screen, dimmed.

## Requirements

- Android 12 or newer. Auto-connect needs Android 16 or newer.
- QC Ultra Headphones (2nd Gen), paired with the phone. Tested on firmware 10.12.12.
- Only one app can control the headphones at a time. If the Bose app connects, this app shows "In use by another app" and offers to reconnect.

## Privacy

The app has no internet permission. It reads and changes settings on your headphones over Bluetooth, and keeps the last values it read on the phone. None of it is backed up or copied to a new phone. See [PRIVACY.md](PRIVACY.md).

## Building

- JDK 21, and the Android SDK with platform 37.
- `./gradlew test lint assembleDebug` runs the unit and screenshot tests, lint, and the debug build.
- `./gradlew assembleRelease` builds an unsigned release.
- To sign the release, put a `keystore.properties` at the repo root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. It's gitignored.

`docs/FINDINGS.md` records what was learned from the real headphones, with dates and firmware versions.

## Credits

Protocol knowledge and ported logic come from [bosectl](https://github.com/aaronsb/bosectl) (MIT). Icons are Material Icons by Google (Apache License 2.0). See [NOTICE](NOTICE).

## Licence

MIT. See [LICENSE](LICENSE).
