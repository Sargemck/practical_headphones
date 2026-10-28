# BMAP fixtures

Real request/reply exchanges captured from a QC Ultra Headphones (2nd Gen) during M0.

- Path: `qc-ultra2/fw-<firmware>/<fblock>.<func>-<op>.txt`
- One exchange per file. `> ` is a frame we sent, `< ` is what came back (for a drain, every frame in arrival order on one line). `#` lines are comments.
- `FixtureParseTest` checks every line parses into whole frames with no skipped or leftover bytes.

## fw-10.12.12

Captured 2026-10-28, firmware `10.12.12+ga927c9a`, Pixel 10 Pro (Android 17), RFCOMM channel 2 via reflection. Headphones on the desk, nothing playing.

### Redactions

The repo is public. Values are replaced with same-length stand-ins so lengths and offsets stay valid. Offsets count from the first byte of the frame.

| File | Line | Offset | Bytes | Was | Now |
|---|---|---|---|---|---|
| `1.2-GET.txt` | `<` | 5 | 18 | headphone name | ASCII `Headphone Name 001` |
| `4.4-GET.txt` | `<` | 5, 11, 17, 23, 29 | 6 each | MACs of the 5 paired devices | `02 00 00 00 00 01` … `02 00 00 00 00 05` |
| `4.5-GET.txt` | `>` and `<` | 4 | 6 | MAC of paired device 1 (the phone) | `02 00 00 00 00 01` |
| `4.5-GET.txt` | `<` | 13 | 12 | phone's device name | ASCII `Paired Dev 1` |

Not redacted: firmware version (0.5), mode names in `31.1-START.txt` (Bose presets plus the user's custom mode names, which don't identify anyone). No serial number was captured: no fblock 0 address other than 0.5 was read.
