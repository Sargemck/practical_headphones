# M8 Plan: Multi-Model & Multi-Brand Headphone Support

> **Safe rollback branch:** `release-1.0-safe` (at commit `d3af979`, right on top of `v1.0.0` + device discovery & LE Audio fixes) is saved locally. `git checkout release-1.0-safe` restores the QC Ultra 2-only build at any time.

## Goal & Scope

Expand **Practical Headphones** from a single-model Bose QC Ultra 2 app into a multi-brand, multi-protocol Bluetooth headphones controller — **completely autonomously (zero human/device intervention needed during implementation)**, keeping QC Ultra 2 wire behavior 100% unchanged, and marking unverified hardware models as **Experimental**.

### Target Lineup

| Brand | Protocol Module | Models Covered | Transport | Verification Basis |
|---|---|---|---|---|
| **Bose** | `:bmap` (existing, expanded) | QC Ultra 2 (`0x4082`, verified), QC 2nd Gen (`0x4083`), QC Ultra Earbuds 2 (`0x4062`), QuietComfort 2023 ("prince", `0x4075`), QC45 (`0x4039`), QC35 / 35 II (`0x400C`, `0x4020`), Ultra Open Earbuds (`0x4068`) | Classic RFCOMM (SPP UUID, fallback ch. 2 / 8 / 9) | `reference/bosectl` (MIT, hardware-verified fixtures) |
| **Sony** | `:sony` (new pure-Kotlin JVM) | WH-1000XM3, WH-1000XM4, WH-1000XM5, WH-1000XM6, WF-1000XM4, WF-1000XM5 | Classic RFCOMM (Sony MDR v2 UUID) | `SonyHeadphonesClient` & `omarchy-headphones` (MIT) |
| **Anker Soundcore** | `:soundcore` (new pure-Kotlin JVM) | Life Q30, Life Q35, Space Q45, Space One, Liberty 4 / 4 NC | Classic RFCOMM (Soundcore SPP UUID) | `soundcorebridge` & `omarchy-headphones` (MIT) |
| **Samsung** | `:samsung` (new pure-Kotlin JVM) | Galaxy Buds2, Buds2 Pro, Buds FE, Buds Live, Buds Pro | Classic RFCOMM (`SPPNew` & standard `SPP` UUIDs) | `omarchy-headphones` (`samsung-bridge`, MIT) |

*(Sennheiser Momentum 4 is intentionally deferred because it uses BLE GATT rather than Classic RFCOMM; the four families above all use Classic RFCOMM sockets, matching the existing foreground service, contention policy, and `SystemLinkMonitor` lifecycle.)*

---

## Core Design Principle: Additive, Zero-Breakage Refactoring

1. **Do NOT break existing `:bmap` public signatures** (`QcUltra2`, `Headphones(connection)`, `FakeBmapDevice(scope)`). Existing `HardwareWriteTest.kt`, `SpikeSession.kt`, and all `:bmap` tests must continue compiling without modification.
2. **Keep protocol modules 100% Android-free** (`java-library`, `java-test-fixtures`, `kotlin.jvm`, JVM 17, JUnit 5 — identical `build.gradle.kts` to `:bmap`).
3. **Keep `HeadphoneDriver` inside `:app`** (`io.github.sargemck.practicalheadphones.driver`), adapting `:bmap`, `:sony`, `:soundcore`, and `:samsung` to `HeadphoneRepository`.
4. **Extend `HeadphoneState` additively with defaults** so existing QC Ultra 2 UI samples and golden screenshots remain unchanged unless intentionally updated.

---

## Guardrails & Common Traps (Read Before Coding!)

1. **MIT Licence Only:** Never clone, read, or copy code from GPL/LGPL repositories (such as `OpenSCQ30`, `GalaxyBudsClient`, or `Gadgetbridge`). Only read from the MIT-licensed repositories cloned in Phase 0, and add their copyright notices to both `NOTICE` and `Licences.kt`.
2. **`LicencesTest` Exact Match:** `app/src/test/.../ui/LicencesTest.kt` asserts that `Licences.LICENSE` and `Licences.NOTICE` match root `LICENSE` and `NOTICE` character-for-character (ignoring `\r\n` vs `\n`). Update `NOTICE` and `Licences.kt` together.
3. **Roborazzi Golden Screenshots:** `./gradlew test` runs `ScreenshotTest` against `app/src/test/screenshots/*.png`.
   - Keep `Samples.connected` (QC Ultra 2) producing the exact same UI so existing goldens don't break unnecessarily.
   - If you intentionally change an existing screen (e.g., `AboutScreen.kt` credits) or add new screenshot tests, run `.\gradlew.bat recordRoborazziDebug`, inspect the PNGs in `app/src/test/screenshots/` (`light`, `dark`, and `font2` 200% font scale) for clipping/overlap, and then run `.\gradlew.bat test lint assembleDebug`.
4. **Zero Android Imports in Protocol Modules:** `:bmap`, `:sony`, `:soundcore`, and `:samsung` must never import `android.*` or `androidx.*`.
5. **No `INTERNET` Permission:** Never add `android.permission.INTERNET` or any analytics/network SDK.
6. **Connect & Sleep Rule:** Never connect an RFCOMM socket unless `SystemLinkMonitor` reports `connected = true` (A2DP, HFP, or LE Audio). Never add retry/polling loops on connect failures (wakes sleeping headphones in a bag).
7. **BMAP Safety Rules:** Never send BMAP `SET (0)`, never touch `fblock 18` (Authentication), and never send `[4.3]`, `[4.7]`, `[4.10]`, or `[4.11]`.
8. **No Fixed `delay()` for Protocol Replies:** Always await replies by frame matching via `Channel`/`SharedFlow` + `withTimeoutOrNull`, just like `BmapConnection`.
9. **`LastKnownCodec` Versioning:** Whenever new fields are added to `HeadphoneState` that should persist offline, bump `VERSION` in `LastKnownCodec` (`LastKnown.kt`), serialize/deserialize the new fields, and update `LastKnownCodecTest.kt`.
10. **Windows PowerShell Execution:** Run Gradle as `.\gradlew.bat ...` on Windows PowerShell. Keep any elevated command standalone.

---

## Phase-by-Phase Implementation Steps

### Phase 0: Clone MIT Reference Specs into `reference/`
`reference/` is already `.gitignore`d. Clone the three MIT-licensed reference repos alongside `reference/bosectl`:
- `git clone --depth 1 https://github.com/Plutoberth/SonyHeadphonesClient reference/sony-client`
- `git clone --depth 1 https://github.com/mervin008/soundcorebridge reference/soundcorebridge`
- `git clone --depth 1 https://github.com/ncr/omarchy-headphones reference/omarchy-headphones`

Verify `LICENSE` in each cloned directory confirms **MIT License** before reading any source files.

---

### Phase 1: Multi-Bose Support in `:bmap`

#### 1.1 Protocol Differences Across All 6 Bose Configs (from `reference/bosectl`)

| Config ID | Models (PID) | RFCOMM Ch | Init Packet | ModeConfig `[31.6]` | Presets / Editable Slots | Live `[31.10]` | Special Notes |
|---|---|---|---|---|---|---|---|
| `qc_ultra2` | QC Ultra 2 (`0x4082`), QC 2nd Gen (`0x4083`) | 2 (SPP) | None | 48B STATUS / 40B SETGET (`wind` at `[46]`, `anc` at `[47]`) | Presets `0..3`, Editable `4..10` | Yes | Verified baseline. Keep 100% identical. |
| `qc_ultra2_earbuds` | QC Ultra Earbuds 2 (`0x4062`) | 2 (SPP) | None | Same 48B / 40B as `qc_ultra2` | Presets `0..3`, Editable `4..10` | Yes | `[2.2]` returns `4 * N` bytes: `[level, rsv, rsv, id]` where `1=Right, 2=Left, 3=Case, 4=Combined`. Aggregate = ID 4 if `<=100`, else `min(ID 1, ID 2)`. |
| `qc_prince` | QuietComfort Headphones (`0x4075`) | 8 (SPP) | None | 47B STATUS / 39B SETGET (no `anc` byte; `wind` at `[46]` in STATUS, `[38]` in SETGET) | Presets `0..1` (Quiet, Aware), Editable `2..3` | **No** (`FuncNotSupp`) | `START [31.3]` may ack with `PROCESSING` followed by `STATUS [31.3]` instead of `RESULT`. |
| `qc45` | QuietComfort 45 (`0x4039`) | 8 | `GET [0.1]` | Same 47B STATUS / 39B SETGET as `qc_prince` | Presets `0..1` (Quiet, Aware), Editable `2..3` | **No** (`FuncNotSupp`) | Requires `GET [0.1]` before answering. Supports EQ `[1.7]`, Self Voice `[1.11]`, Multipoint `[1.10]` GET, Favourites `[31.8]`. When `wind=true`, firmware forces `cnc=0`. |
| `qc35` | QC35 (`0x400C`), QC35 II (`0x4020`) | 8 | `GET [0.1]` | **None** (no block 31) | None (uses ANR `[1.6]`) | **No** | Requires `GET [0.1]`. Uses ANR `[1.6]` GET/SETGET: `0=Off, 1=High, 2=Wind, 3=Low`. Name write `[1.2]` is raw UTF-8 without leading `0x00` flag byte (`qc35.py` line 44). Supports `[1.11]` Self Voice, `[4.8]` pairing. No EQ, no `[7.4]` power off. |
| `ultra_open` | Ultra Open Earbuds (`0x4068`) | 2 (SPP) | None | 48B STATUS read-only (no builder) | Read/switch modes via `[31.1]` / `[31.3]` only | **No** | Open-ear: no ANC/CNC. Supports EQ `[1.7]`, Multipoint `[1.10]` GET, `[4.8]` pairing, `[7.4]` power off. |

#### 1.2 Exact Code Changes in `:bmap`
1. **`Models.kt`**:
   - Add `enum class AnrMode(val code: Int) { OFF(0), HIGH(1), WIND(2), LOW(3) }`.
   - Add `data class BatteryComponent(val label: String, val percent: Int)` and `data class BatteryStatus(val percent: Int, val components: List<BatteryComponent> = emptyList())`.
   - Add `enum class ModeLayout { V48_40, V47_39, READ_ONLY_48, NONE }`.
2. **`BoseCatalog.kt`**:
   - Define `data class BoseProfile(...)` for all 6 profiles (`QC_ULTRA_2` with `experimental = false`; the other 5 with `experimental = true`).
   - Add `BoseCatalog.inferFromName(btName: String?): BoseProfile?` and `BoseCatalog.refineFromWire(...)` using `[2.2]` length (`>= 8` → `QC_ULTRA_2_EARBUDS`), `[31.1]` support (unsupported + `[1.6]` → `QC_35`), `[31.6]` frame size (`47` → `QC_45`/`QC_PRINCE`, `48` → `ULTRA_OPEN`/`QC_ULTRA_2`).
3. **`Parsers.kt` & `Builders.kt`**:
   - Add `Parsers.batteryStatus`, `Parsers.modeConfig47`, `Builders.modeConfig39`, `Parsers.anr`, `Builders.anr`, and `Builders.productNameRaw`.
4. **`BmapConnection.kt` / `Headphones.kt` / `Profiles.kt`**:
   - Support `qc_prince` `START [31.3]` `PROCESSING` acks when switching modes on non-`QC_ULTRA_2` profiles.
   - Parameterize `Profiles.kt` slot ranges with defaults `QcUltra2.editableSlots` / `QcUltra2.presetSlots`.
5. **`FakeBmapDevice.kt`**:
   - Parameterize `FakeBmapDevice(scope, profile = BoseCatalog.QC_ULTRA_2)` to simulate all 6 Bose models (including `INIT_PACKET`, 47B mode configs, multi-component battery from `reference/bosectl/fixtures/packets/qc-ultra2-earbuds/battery-status.hex`, and ANR `[1.6]`).
6. **Gate:** `.\gradlew.bat :bmap:test` green.

---

### Phase 2: Brand-Agnostic Driver Layer & Extended UI State in `:app`

1. **`HeadphoneState.kt`**:
   - Add `DeviceBadge`, `BatteryPart`, `DiscreteAncOption`, `DiscreteAncState`, `PairedHeadphoneChoice`, `EqPresetChoice`, and `Capabilities` (all defaulting to exact QC Ultra 2 values so `Samples.connected` and existing screenshot tests stay identical).
2. **`HeadphoneDriver.kt` & `BoseDriver.kt`**:
   - Define `HeadphoneDriver` interface in `app/.../driver/` and adapt `HeadphoneRepository` to drive any `HeadphoneDriver`.
   - Update Bose RFCOMM connection path: try `SPP_UUID` → ch `2` → ch `8` → ch `9`, sending `GET [0.1]` init packet if initial `GET [2.2]`/`[0.5]` times out on ch 8 / SPP for older Bose models.
3. **UI (`Sections.kt`, `MainScreen.kt`) & `LastKnownCodec` (`LastKnown.kt`)**:
   - Render Experimental note when `deviceBadge?.experimental == true`.
   - Render `L / R / Case` battery breakdown when `batteryParts.isNotEmpty()`.
   - Render `DiscreteNoiseCard` when `discreteAnc !is Section.Unsupported` (chips/radios + optional Ambient Level slider + Focus on Voice toggle).
   - Render N-band EQ using `eqBandLabels` and custom `eqPresets`.
   - Render `Speak-to-Chat` in Calls card when supported, hide unsupported Device card actions via `Capabilities`, and show `"Switch headphones"` when `availableDevices.size > 1`.
   - Bump `LastKnownCodec` to `VERSION = "2"` and update `LastKnownCodecTest.kt`.
4. **Gate:** `.\gradlew.bat test lint assembleDebug` green.

---

### Phase 3: `:sony` Module (Sony MDR Protocol v2 — WH-1000XM3/4/5/6 & WF-1000XM4/5)

Cross-check all constants against `reference/sony-client/Client/Constants.h` and `reference/omarchy-headphones` (`sony-bridge`).

- **Service UUIDs:** `956c7b26-d49a-4ba8-b03f-b17d393cb6e2` (v2), `96cc203e-5068-46ad-b32d-e316f5e069ba` (v1 fallback).
- **Framing (`SonyFrameCodec.kt`):**
  - Start `0x3E`, End `0x3C`, Escape `0x3D` (`0x3C` ↔ `0x3D 0x2C`, `0x3D` ↔ `0x3D 0x2D`, `0x3E` ↔ `0x3D 0x2E`).
  - Inner layout: `[dataType: 1B] [seq: 1B] [len: 4B BE] [payload: N] [checksum: 1B]` where checksum = 8-bit sum of unescaped `[dataType..payload]`.
  - Immediate `ACK` (`0x01`) handling and sequence tracking.
- **Commands:** Protocol/Device Info handshake, Battery (single + L/R/Case), `NCASM` (`0x66..0x69`: NC, Ambient `0..20` + Focus on Voice, Wind, Off), Speak-to-Chat, `EQEBB` (`0x56..0x59`: presets + 5 bands + Clear Bass), Power Off.
- **Tests:** `FakeSonyDevice` + codec/escaping/ACK/feature unit tests + `SonyDriver` in `:app`.

---

### Phase 4: `:soundcore` Module (Anker Soundcore — Q30, Q35, Q45, Space One, Liberty 4)

Cross-check all packet layouts against `reference/soundcorebridge` (`docs/protocol-map.md`) and `reference/omarchy-headphones` (`soundcore-bridge`).

- **Framing (`SoundcoreFrameCodec.kt`):**
  - TX prefix `08 EE 00 00 00`, RX prefix `09 FF 00 00 01`, followed by `[cmdGroup: 1B] [cmdId: 1B] [totalLen: 2B LE] [payload...] [checksum: 1B]` (8-bit wrapping sum of bytes `0 until totalLen - 1`).
- **Commands:** State request (`01 01`), ANC/Transparency/Normal mode control (`06 81` / `06 01`, including Transport/Indoor/Outdoor sub-modes), Presets + 8-band EQ (`02 81` / `02 83`), single & dual/case battery parsing.
- **Tests:** `FakeSoundcoreDevice` + stream framing/checksum/feature unit tests + `SoundcoreDriver` in `:app`.

---

### Phase 5: `:samsung` Module (Galaxy Buds2, Buds2 Pro, Buds FE, Buds Live, Buds Pro)

Cross-check all message IDs and payload offsets against `reference/omarchy-headphones` (`samsung-bridge`).

- **Service UUIDs:** `2e73a4ad-332d-41fc-90e2-16bef06523f2` (`SPPNew`), `00001101-0000-1000-8000-00805f9b34fb` (`SPP` when name matches `"Galaxy Buds"`).
- **Framing (`SamsungFrameCodec.kt`):**
  - `[0xFD] [header: 2B LE] [msgId: 1B] [payload...] [crc16: 2B LE] [0xDD]` with CRC16-CCITT/XMODEM over `[msgId] + payload`.
- **Messages:** `MANAGER_INFO` (`0x88`), `STATUS_UPDATED` (`0x60`), `EXTENDED_STATUS_UPDATED` (`0x61`, L/R/Case battery + current ANC + EQ), `NOISE_CONTROLS` (`0x78` / `0x77`: Off / ANC / Ambient), `EQUALIZER` (`0x86`: Normal / Bass boost / Soft / Dynamic / Clear / Treble boost).
- **Tests:** `FakeSamsungDevice` + CRC16/status/feature unit tests + `SamsungDriver` in `:app`.

---

### Phase 6: Multi-Brand Discovery, Demo Mode, Credits & Final Verification

1. **Multi-Brand `HeadphonesLocator` & `MultiBrandConnector`**: Route to Bose, Sony, Soundcore, or Samsung driver based on bonded UUID + name; expose `availableDevices` switcher; detect official companion apps (`com.bose.bosemusic`, `com.sony.songpal.mdr`, `com.oceanwing.soundcore`, `com.samsung.accessory.atticmgr`).
2. **Debug Demo Mode (`DebugEntry.kt`)**: Support `--es demoDevice qc_ultra2|qc_ultra2_earbuds|qc45|qc35|sony_xm5|soundcore_q45|galaxy_buds2_pro`.
3. **Credits & Docs**: Update `NOTICE` and `Licences.kt` identically (`LicencesTest`), `AboutScreen.kt`, and `README.md`.
4. **Final Gate**:
   - Add UI behaviour + Roborazzi screenshot tests for earbuds battery + Experimental note, discrete ANC + 5-band EQ, and multi-device switcher.
   - Run `.\gradlew.bat recordRoborazziDebug` and visually inspect all light/dark/font2 PNGs.
   - Run `.\gradlew.bat test lint assembleDebug` — 0 errors, 0 failed tests.
