package io.github.sargemck.practicalheadphones

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sargemck.practicalheadphones.bmap.BmapException
import io.github.sargemck.practicalheadphones.bmap.EqBandSetting
import io.github.sargemck.practicalheadphones.bmap.HardwareTestOnly
import io.github.sargemck.practicalheadphones.bmap.Headphones
import io.github.sargemck.practicalheadphones.bmap.QcUltra2
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import io.github.sargemck.practicalheadphones.bmap.WriteReply
import io.github.sargemck.practicalheadphones.link.BmapLog
import io.github.sargemck.practicalheadphones.link.HeadphonesLocator
import io.github.sargemck.practicalheadphones.link.RfcommConnector
import io.github.sargemck.practicalheadphones.service.HeadphoneService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * M2 hardware test against the real headphones (PLAN M2). Each write: read, write, read back,
 * restore, with reply frames and timings logged under `BMAP HW`. Never sends 31.6, 4.8 or 7.4.
 * Multipoint runs last: flipping it could drop a connected device.
 */
@RunWith(AndroidJUnit4::class)
class HardwareWriteTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun hw(message: String) = Log.i("BMAP", "HW $message")

    private fun ms(start: Long) = String.format(Locale.ROOT, "%.1f", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)

    @OptIn(HardwareTestOnly::class)
    @Test
    fun writesReadBackAndRestore() = runBlocking {
        context.stopService(Intent(context, HeadphoneService::class.java))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = RfcommConnector(context, HeadphonesLocator(context), BmapLog.frames) { hw(it) }.connect(scope)
            val h = Headphones(connection)
            readAll(h)

            val name = h.read(QcUltra2.productName)
            roundTrip("1.2 name", { h.read(QcUltra2.productName) }, { h.write(QcUltra2.Write.productName, it) }, "PH Test", name)

            for (band in 0..2) {
                val current = h.read(QcUltra2.eq).single { it.bandId == band }.current
                val test = if (current == 3) -3 else 3
                roundTrip(
                    "1.7 EQ band $band",
                    { h.read(QcUltra2.eq).single { it.bandId == band }.current },
                    { h.write(QcUltra2.Write.eqBand, EqBandSetting(band, it)) },
                    test,
                    current,
                )
            }

            val voice = h.read(QcUltra2.selfVoice).level
            roundTrip(
                "1.11 Self Voice",
                { h.read(QcUltra2.selfVoice).level },
                { h.write(QcUltra2.Write.selfVoice, it) },
                if (voice == SelfVoiceLevel.OFF) SelfVoiceLevel.LOW else SelfVoiceLevel.OFF,
                voice,
            )

            val playPause = h.read(QcUltra2.autoPlayPause)
            roundTrip("1.24 auto play/pause", { h.read(QcUltra2.autoPlayPause) }, { h.write(QcUltra2.Write.autoPlayPause, it) }, !playPause, playPause)

            val answer = h.read(QcUltra2.autoAnswer)
            roundTrip("1.27 auto answer", { h.read(QcUltra2.autoAnswer) }, { h.write(QcUltra2.Write.autoAnswer, it) }, !answer, answer)

            val favourites = h.read(QcUltra2.favourites)
            val start = SystemClock.elapsedRealtimeNanos()
            reply("31.8 favourites unchanged", h.write(QcUltra2.Write.favourites, favourites))
            assertEquals(favourites, h.read(QcUltra2.favourites))
            hw("31.8 favourites unchanged: read back OK in ${ms(start)} ms")

            val multipoint = h.read(QcUltra2.multipoint).raw
            hw("1.10 multipoint start raw=0x%02x".format(multipoint))
            roundTrip(
                "1.10 multipoint same value",
                { h.read(QcUltra2.multipoint).raw },
                { h.write(QcUltra2.Experimental.multipointRaw, it) },
                multipoint,
                multipoint,
            )
            try {
                roundTrip(
                    "1.10 multipoint bit 0x02 flipped",
                    { h.read(QcUltra2.multipoint).raw },
                    { h.write(QcUltra2.Experimental.multipointRaw, it) },
                    multipoint xor 0x02,
                    multipoint,
                    exact = false,
                )
            } catch (e: BmapException.Disconnected) {
                hw("1.10 LINK DROPPED during the flip: ${e.message}. Restore by spike: SETGET 1.10 0x%02x".format(multipoint))
                throw e
            }
            hw("DONE all restored")
            connection.close()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun readAll(h: Headphones) {
        val features = listOf(
            QcUltra2.firmware, QcUltra2.eq, QcUltra2.buttons, QcUltra2.multipoint, QcUltra2.selfVoice,
            QcUltra2.autoPlayPause, QcUltra2.autoAnswer, QcUltra2.battery, QcUltra2.source, QcUltra2.currentMode,
            QcUltra2.favourites, QcUltra2.audioSettings,
        )
        for (feature in features) hw("read ${feature.address} = ${h.read(feature)}")
        hw("read 31.1 = ${h.allModes().modes.size} modes")
    }

    /**
     * Writes [test], reads it back, then restores [original] and reads that back. The restore
     * runs even if the test write fails. With [exact] false the read-back is only logged
     * (multipoint: the device may set other bits).
     */
    private suspend fun <T> roundTrip(
        label: String,
        read: suspend () -> T,
        write: suspend (T) -> WriteReply<*>,
        test: T,
        original: T,
        exact: Boolean = true,
    ) {
        try {
            val start = SystemClock.elapsedRealtimeNanos()
            reply("$label write $test", write(test))
            val back = read()
            hw("$label read back $back in ${ms(start)} ms")
            if (exact) assertEquals("$label read back", test, back)
        } finally {
            val start = SystemClock.elapsedRealtimeNanos()
            reply("$label restore $original", write(original))
            val restored = read()
            hw("$label restored $restored in ${ms(start)} ms")
            assertEquals("$label RESTORE FAILED", original, restored)
        }
    }

    private fun reply(label: String, reply: WriteReply<*>) {
        hw("$label reply: $reply")
    }
}
