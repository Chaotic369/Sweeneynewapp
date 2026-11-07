package net.hunterprey.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator

/** Rings like a normal phone call: your ringtone, your vibration pattern, respects silent/vibrate mode. */
object Ring {
    @Volatile var active = false
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private val ATTRS: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .build()

    fun pattern(kind: String): LongArray? = when (kind) {
        "classic" -> longArrayOf(0, 1000, 1000)
        "pulse" -> longArrayOf(0, 300, 200, 300, 200, 300, 1200)
        "long" -> longArrayOf(0, 2000, 800)
        else -> null
    }

    @Synchronized
    fun start(ctx: Context) {
        if (active) return
        active = true
        val c = ctx.applicationContext
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = am.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) playSound(c)
        if (mode != AudioManager.RINGER_MODE_SILENT) vibrate(c)
    }

    private fun playSound(c: Context) {
        val saved = Prefs.ringtone(c)
        val wanted: Uri = if (saved.isNotEmpty()) Uri.parse(saved) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = tryPlayer(c, wanted) ?: tryPlayer(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
    }

    private fun tryPlayer(c: Context, uri: Uri): MediaPlayer? {
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(ATTRS)
            mp.setDataSource(c, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(c: Context) {
        val pat = pattern(Prefs.callVibe(c)) ?: return
        try {
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator = v
            v.vibrate(VibrationEffect.createWaveform(pat, 0), ATTRS)
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        active = false
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (e: Exception) { }
        vibrator = null
    }
}
