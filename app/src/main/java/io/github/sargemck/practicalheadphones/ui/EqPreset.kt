package io.github.sargemck.practicalheadphones.ui

import io.github.sargemck.practicalheadphones.bmap.EqBand

/** EQ presets, bass/mid/treble on the device's -10..10 scale. Starting points; H1 tunes them by ear. */
enum class EqPreset(val label: String, val bass: Int, val mid: Int, val treble: Int) {
    FLAT("Flat", 0, 0, 0),
    VOICE("Voice", -2, 3, 2),
    BASS("Bass boost", 6, 0, 0),
    TREBLE("Treble boost", 0, 0, 6);

    companion object {
        /** The preset the bands match exactly, or null for custom settings. */
        fun match(bands: List<EqBand>): EqPreset? {
            val values = bands.associate { it.bandId to it.current }
            return entries.firstOrNull { values[0] == it.bass && values[1] == it.mid && values[2] == it.treble }
        }
    }
}
