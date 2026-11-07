package net.hunterprey.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationManagerCompat

/** Starts and stops the "phone is ringing" experience: ringtone, vibration, notification, lock-screen card. */
object CallUi {
    const val ACTION_DONE = "net.hunterprey.chat.CALL_DONE"
    @Volatile var avatar: Bitmap? = null

    fun show(ctx: Context, name: String, video: Boolean, avatarBmp: Bitmap?) {
        val c = ctx.applicationContext
        avatar = avatarBmp
        Notifs.ensureChannels(c)
        Ring.start(c)
        Notifs.postCall(c, name, video, avatarBmp)
    }

    fun dismiss(ctx: Context) {
        val c = ctx.applicationContext
        Ring.stop()
        NotificationManagerCompat.from(c).cancel(Notifs.ID_CALL)
        c.sendBroadcast(Intent(ACTION_DONE).setPackage(c.packageName))
    }
}
