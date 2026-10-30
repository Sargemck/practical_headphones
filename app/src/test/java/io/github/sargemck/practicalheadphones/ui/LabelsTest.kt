package io.github.sargemck.practicalheadphones.ui

import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LabelsTest {
    private fun bands(bass: Int, mid: Int, treble: Int) =
        listOf(EqBand(0, -10, 10, bass), EqBand(1, -10, 10, mid), EqBand(2, -10, 10, treble))

    @Test
    fun `Self Voice labels map to the stored byte, both ways`() {
        val expected = mapOf("Off" to 0, "High" to 1, "Medium" to 2, "Low" to 3)
        for ((label, code) in expected) {
            val level = selfVoiceOptions.single { it.first == label }.second
            assertEquals(code, level.code, label)
            assertEquals(label, selfVoiceLabel(SelfVoiceLevel.fromCode(code)!!))
        }
        assertEquals(listOf("Off", "Low", "Medium", "High"), selfVoiceOptions.map { it.first })
    }

    @Test
    fun `noise slider strength is 10 minus cnc, both ways`() {
        for (cnc in 0..10) {
            assertEquals(10 - cnc, strengthFromCnc(cnc))
            assertEquals(cnc, cncFromStrength(strengthFromCnc(cnc)))
        }
        assertEquals("Block noise, 7 of 10", noiseDescription(7))
    }

    @Test
    fun `EQ preset match`() {
        assertEquals(EqPreset.FLAT, EqPreset.match(bands(0, 0, 0)))
        assertEquals(EqPreset.VOICE, EqPreset.match(bands(-2, 3, 2)))
        assertEquals(EqPreset.BASS, EqPreset.match(bands(6, 0, 0)))
        assertEquals(EqPreset.TREBLE, EqPreset.match(bands(0, 0, 6)))
        assertNull(EqPreset.match(bands(1, 0, 0)))
    }
}
