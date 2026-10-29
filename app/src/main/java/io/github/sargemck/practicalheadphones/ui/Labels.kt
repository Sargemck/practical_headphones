package io.github.sargemck.practicalheadphones.ui

import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import io.github.sargemck.practicalheadphones.bmap.Spatial

/** Self Voice choices in screen order. The device stores them as Off 0, High 1, Medium 2, Low 3. */
val selfVoiceOptions: List<Pair<String, SelfVoiceLevel>> = listOf(
    "Off" to SelfVoiceLevel.OFF,
    "Low" to SelfVoiceLevel.LOW,
    "Medium" to SelfVoiceLevel.MEDIUM,
    "High" to SelfVoiceLevel.HIGH,
)

fun selfVoiceLabel(level: SelfVoiceLevel): String = selfVoiceOptions.first { it.second == level }.first

/** Bose's names. Still = fixed to room, Motion = fixed to head (inferred, confirmed by ear in H1). */
val spatialOptions: List<Triple<String, Spatial, String>> = listOf(
    Triple("Off", Spatial.OFF, "Normal stereo."),
    Triple("Still", Spatial.FIXED_TO_ROOM, "Sound stays put when you turn your head."),
    Triple("Motion", Spatial.FIXED_TO_HEAD, "Sound moves with you."),
)

/** The noise slider shows strength = 10 - cnc: left lets the world in, right blocks it. */
fun strengthFromCnc(cnc: Int): Int = 10 - cnc

fun cncFromStrength(strength: Int): Int = 10 - strength

fun noiseDescription(strength: Int): String = when (strength) {
    0 -> "Hear surroundings, 0 of 10"
    else -> "Block noise, $strength of 10"
}
