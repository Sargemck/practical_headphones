package io.github.sargemck.practicalheadphones

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.RingtoneManager
import io.github.sargemck.practicalheadphones.SpikeLog.log
import kotlin.math.PI
import kotlin.math.sin

/** S1 (debug only): makes the phone a streaming source, or posts a notification with sound. */
object SpikeAudio {
    private const val RATE = 44_100
    private const val CHANNEL = "s1-test"

    @Volatile private var track: AudioTrack? = null

    /** A quiet 440 Hz tone as media, looped for [seconds]. */
    fun play(seconds: Int) {
        stop()
        val samples = ShortArray(RATE) { i -> (sin(2 * PI * 440 * i / RATE) * Short.MAX_VALUE * 0.05).toInt().toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size)
        t.setLoopPoints(0, samples.size, seconds.coerceAtLeast(1) - 1)
        t.play()
        track = t
        log("AUDIO phone playing ${seconds}s")
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
            log("AUDIO phone stopped")
        }
        track = null
    }

    /** A notification with the default sound, like the work email in Baris's report. */
    fun notify(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "S1 test", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
            },
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("S1 test notification")
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % 100_000).toInt(), n)
        log("AUDIO notification posted")
    }
}
