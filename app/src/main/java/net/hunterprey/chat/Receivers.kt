package net.hunterprey.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Reply typed straight into the notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(Notifs.KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val pending = goAsync()
        val c = ctx.applicationContext
        Thread {
            try {
                val role = Prefs.role(c)
                val room = Db.resolveRoom(c)
                if (room != null && role.isNotEmpty()) {
                    val ok = Db.postText(room, role, text)
                    if (ok) {
                        Db.markSeen(room, Notifs.takeUnseenKeys())
                        Notifs.addMine(c, text)
                    } else {
                        Notifs.addMine(c, "\u26A0 Not sent: $text")
                    }
                }
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val c = ctx.applicationContext
        Thread {
            try {
                val keys = Notifs.takeUnseenKeys()
                val room = Db.resolveRoom(c)
                if (room != null && keys.isNotEmpty()) Db.markSeen(room, keys)
                Notifs.clearMessages(c)
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** "Decline" button on the call notification or card. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val c = ctx.applicationContext
        CallUi.dismiss(c)
        Thread {
            try {
                val room = Db.resolveRoom(c)
                if (room != null) Db.delete("rooms/$room/call")
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs.hasSession(ctx)) ListenerService.start(ctx)
    }
}
