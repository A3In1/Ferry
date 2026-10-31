package dev.ferry.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/** Find my phone: loud alarm-stream sound (rings through silent mode) + vibration for up to 60 s. Main thread only. */
object Ringer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private const val TIMEOUT = "ring-timeout"

    private val alarm = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(ctx: Context, from: String) {
        val app = ctx.applicationContext
        main.removeCallbacksAndMessages(TIMEOUT)
        main.postDelayed({ stop(app) }, TIMEOUT, 60_000)
        Notifs.ringing(app, from)
        if (player != null) return
        val audio = app.getSystemService(AudioManager::class.java)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(alarm)
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't play the alarm sound", e)
            null
        }
        @Suppress("DEPRECATION") // vibrate(effect, AudioAttributes) keeps alarm usage on API 29–32 too
        app.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0), alarm)
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        main.removeCallbacksAndMessages(TIMEOUT)
        Notifs.cancel(app, Notifs.ID_RING)
        app.getSystemService(Vibrator::class.java)?.cancel()
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        if (savedVolume >= 0) {
            runCatching { app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
        }
    }
}
