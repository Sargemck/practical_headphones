# Practical Headphones

Practical Headphones is an Android app for the Bose QuietComfort Ultra Headphones (2nd Gen). It switches modes, sets noise control, the equaliser and call settings, and builds your own modes, all directly over Bluetooth, so you don't need the Bose app or an account. A Quick Settings tile and an ongoing notification put the common controls one tap away, and with auto-connect the app is ready as soon as the headphones connect.

Not affiliated with Bose. Bose and QuietComfort are trademarks of Bose Corporation.

| Main screen | All modes | New mode |
|---|---|---|
| ![Main screen](fastlane/metadata/android/en-US/images/phoneScreenshots/1.png) | ![All modes](fastlane/metadata/android/en-US/images/phoneScreenshots/3.png) | ![New mode](fastlane/metadata/android/en-US/images/phoneScreenshots/2.png) |

## Features

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
- **Auto-connect:** the app starts when the headphones connect.
- **While the headphones are away:** the last settings read from them stay on screen, dimmed.

## Install

Download the APK from [GitHub Releases](https://github.com/Sargemck/practical_headphones/releases). F-Droid is planned.

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

## Credits

Protocol knowledge and ported logic come from [bosectl](https://github.com/aaronsb/bosectl) (MIT). Icons are Material Icons by Google (Apache License 2.0). See [NOTICE](NOTICE).

## Licence

MIT. See [LICENSE](LICENSE).
