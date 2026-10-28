package io.github.sargemck.practicalheadphones

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder

/**
 * M0 E0b, debug builds only: plays silence so the A2DP stream stays active, to test
 * whether that keeps the headphones awake. No audio focus is requested.
 * Start it only while SpikeActivity is in front (Android 17 background audio rules).
 */
class SilentAudioService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "M0 silent audio", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("M0 silent audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        running = true
        thread = Thread {
            val rate = 44_100
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val silence = ByteArray(minBuf)
            track.play()
            SpikeLog.log("AUDIO silent playback started (buffer $minBuf bytes)")
            while (running) track.write(silence, 0, silence.size)
            track.stop()
            track.release()
            SpikeLog.log("AUDIO silent playback stopped")
        }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.join(1_000)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "m0-silent-audio"
    }
}
