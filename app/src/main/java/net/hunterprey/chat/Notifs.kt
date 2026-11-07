package net.hunterprey.chat

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONObject
import java.util.Calendar

/** Everything that builds notifications: messages (with reply box), incoming calls, the service notice. */
object Notifs {
    const val ID_MSG = 1001
    const val ID_CALL = 2001
    const val ID_SERVICE = 3001
    const val CH_SERVICE = "service"
    const val CH_CALL = "calls"
    const val KEY_REPLY = "hp_reply_text"
    const val ACTION_REPLY = "net.hunterprey.chat.REPLY"
    const val ACTION_MARK_READ = "net.hunterprey.chat.MARK_READ"
    const val ACTION_DECLINE = "net.hunterprey.chat.DECLINE"
    const val EXTRA_ANSWER = "answer_call"

    private class Entry(val text: String, val time: Long, val mine: Boolean)

    private val history = ArrayList<Entry>()
    private val unseenKeys = ArrayList<String>()
    private var lastName = "Hunter"
    private var lastAvatar: Bitmap? = null
    private var lastRoom = ""
    private var avatarUrlCached: String? = null

    private fun immutable(): Int = PendingIntent.FLAG_IMMUTABLE
    private fun mutable(): Int = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    // ---------------------------------------------------------------- channels
    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(CH_SERVICE) == null) {
            val ch = NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps messages and calls arriving while the app is closed"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_CALL) == null) {
            val ch = NotificationChannel(CH_CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Ringing for incoming voice and video calls"
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    /** Message channel for the current settings; one per sound/vibration combination. */
    private fun msgChannel(ctx: Context, sound: Boolean, vibe: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ver = Prefs.chVer(ctx)
        val prefix = "msg_$ver"
        val id = prefix + "_" + (if (sound) "s" else "x") + (if (vibe) "v" else "x")
        if (nm.getNotificationChannel(id) != null) return id

        // clean out channels from older settings versions
        for (old in nm.notificationChannels) {
            if (old.id.startsWith("msg_") && !old.id.startsWith(prefix + "_")) nm.deleteNotificationChannel(old.id)
        }

        val alerting = sound || vibe
        val importance = when {
            !alerting -> NotificationManager.IMPORTANCE_LOW
            Prefs.headsUp(ctx) -> NotificationManager.IMPORTANCE_HIGH
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
        val ch = NotificationChannel(id, "Messages", importance)
        ch.description = "New messages"
        if (sound) {
            val saved = Prefs.msgSound(ctx)
            when {
                saved == "silent" -> ch.setSound(null, null)
                saved.isNotEmpty() -> ch.setSound(Uri.parse(saved), notifAttrs())
                else -> ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), notifAttrs())
            }
        } else {
            ch.setSound(null, null)
        }
        val pat = vibePattern(Prefs.msgVibe(ctx))
        if (vibe && pat != null) {
            ch.enableVibration(true)
            ch.vibrationPattern = pat
        } else {
            ch.enableVibration(false)
        }
        ch.enableLights(true)
        ch.setShowBadge(true)
        nm.createNotificationChannel(ch)
        return id
    }

    private fun notifAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun vibePattern(kind: String): LongArray? = when (kind) {
        "short" -> longArrayOf(0, 200)
        "double" -> longArrayOf(0, 150, 100, 150)
        "long" -> longArrayOf(0, 600)
        else -> null
    }

    // ---------------------------------------------------------------- service notice
    fun serviceNotification(ctx: Context): android.app.Notification {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            immutable() or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Hunter & Prey")
            .setContentText("Connected – messages and calls will arrive")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    // ---------------------------------------------------------------- helpers
    private fun inQuietHours(ctx: Context): Boolean {
        if (!Prefs.quietOn(ctx)) return false
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val s = Prefs.quietStart(ctx)
        val e = Prefs.quietEnd(ctx)
        return if (s == e) false else if (s < e) now in s until e else (now >= s || now < e)
    }

    private fun circle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (src.width - size) / 2
        val top = (src.height - size) / 2
        canvas.drawBitmap(src, Rect(left, top, left + size, top + size), Rect(0, 0, size, size), paint)
        return out
    }

    fun avatarFor(url: String?): Bitmap? {
        if (url == null) return null
        if (url == avatarUrlCached && lastAvatar != null) return lastAvatar
        val b = Db.bitmap(url) ?: return null
        val c = circle(b)
        avatarUrlCached = url
        lastAvatar = c
        return c
    }

    /** Turns a raw chat message into the line shown in the notification, or null to stay silent. */
    fun describe(m: JSONObject): String? {
        return when (m.optString("type")) {
            "text" -> {
                val t = m.optString("text")
                when {
                    t.isEmpty() -> null
                    t.startsWith("GIF::") -> "GIF"
                    t.startsWith("CALL::") -> {
                        val p = t.removePrefix("CALL::").split(":")
                        val status = p.getOrNull(0) ?: ""
                        val kind = if (p.getOrNull(1) == "video") "video" else "voice"
                        if (status == "missed" || status == "cancelled") "Missed $kind call" else null
                    }
                    else -> t
                }
            }
            "voice" -> {
                val d = m.optInt("duration", 0)
                if (d > 0) "\uD83C\uDFA4 Voice message (${d / 60}:${(d % 60).toString().padStart(2, '0')})" else "\uD83C\uDFA4 Voice message"
            }
            "media" -> {
                val once = m.optBoolean("viewOnce", false)
                when (m.optString("mediaType")) {
                    "image" -> if (once) "\uD83D\uDD12 View-once photo" else "\uD83D\uDCF7 Photo"
                    "video" -> if (once) "\uD83D\uDD12 View-once video" else "\uD83C\uDFA5 Video"
                    "audio" -> "\uD83C\uDFB5 Audio"
                    else -> "\uD83D\uDCCE " + m.optString("fileName", "Document")
                }
            }
            else -> null
        }
    }

    // ---------------------------------------------------------------- messages
    /** Called by the listener for every new message from the other person. */
    fun onIncomingMessage(ctx: Context, room: String, key: String, m: JSONObject) {
        val c = ctx.applicationContext
        val role = Prefs.role(c)
        val text = describe(m) ?: return
        // let the sender see "delivered" like the web app does
        Db.markDelivered(room, key)
        if (MainActivity.foreground) return
        if (!Prefs.msgOn(c)) return
        val partner = Db.partner(room, role)
        if (role == "prey" && !partner.allowed) return

        var sound = true
        var vibe = true
        if (role == "prey") { sound = partner.sound; vibe = partner.haptics }
        if (inQuietHours(c)) { sound = false; vibe = false }

        synchronized(this) {
            if (room != lastRoom) { history.clear(); unseenKeys.clear() }
            lastRoom = room
            lastName = partner.name
            history.add(Entry(text, System.currentTimeMillis(), false))
            while (history.size > 8) history.removeAt(0)
            unseenKeys.add(key)
        }
        avatarFor(partner.avatarUrl)
        post(c, alert = true, sound = sound, vibe = vibe)
    }

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, alert: Boolean, sound: Boolean = true, vibe: Boolean = true) {
        val c = ctx.applicationContext
        ensureChannels(c)
        val preview = Prefs.preview(c)
        val snap = synchronized(this) { Triple(ArrayList(history), lastName, lastAvatar) }
        val entries: List<Entry> = snap.first
        val name: String = snap.second
        val avatar: Bitmap? = snap.third
        if (entries.isEmpty()) return

        val open = PendingIntent.getActivity(
            c, 11, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            immutable() or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val channel = msgChannel(c, sound && alert, vibe && alert)
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (Prefs.headsUp(c)) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(!alert)
            .setWhen(entries.last().time)
            .setShowWhen(true)

        when (Prefs.lock(c)) {
            "hide" -> b.setVisibility(NotificationCompat.VISIBILITY_SECRET)
            "private" -> {
                b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                b.setPublicVersion(
                    NotificationCompat.Builder(c, channel)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle(if (preview == "hidden") "•" else "New message")
                        .build()
                )
            }
            else -> b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        val theirCount = entries.count { !it.mine }
        when (preview) {
            "hidden" -> {
                b.setContentTitle("•").setContentText("•")
            }
            "sender" -> {
                b.setContentTitle(name)
                b.setContentText(if (theirCount <= 1) "New message" else "$theirCount new messages")
                if (avatar != null) b.setLargeIcon(avatar)
            }
            else -> {
                val me = Person.Builder().setName("You").setKey("me").build()
                val themB = Person.Builder().setName(name).setKey("partner")
                if (avatar != null) themB.setIcon(IconCompat.createWithBitmap(avatar))
                val them = themB.build()
                val style = NotificationCompat.MessagingStyle(me)
                for (e in entries) {
                    style.addMessage(NotificationCompat.MessagingStyle.Message(e.text, e.time, if (e.mine) me else them))
                }
                b.setStyle(style)
                b.setContentTitle(name)
                b.setContentText(entries.last().text)
                if (avatar != null) b.setLargeIcon(avatar)
            }
        }

        if (preview != "hidden" && Prefs.replyBox(c)) {
            val remote = RemoteInput.Builder(KEY_REPLY).setLabel("Reply…").build()
            val replyPi = PendingIntent.getBroadcast(
                c, 12, Intent(ACTION_REPLY).setClass(c, ReplyReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable()
            )
            val reply = NotificationCompat.Action.Builder(R.drawable.ic_reply, "Reply", replyPi)
                .addRemoteInput(remote)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(true)
                .build()
            b.addAction(reply)
        }
        if (preview != "hidden") {
            val readPi = PendingIntent.getBroadcast(
                c, 13, Intent(ACTION_MARK_READ).setClass(c, MarkReadReceiver::class.java),
                immutable() or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_read, "Mark as read", readPi)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build()
            )
        }

        try {
            NotificationManagerCompat.from(c).notify(ID_MSG, b.build())
        } catch (e: SecurityException) {
        }
    }

    /** After the user answers from the notification: show their reply and stop the spinner. */
    fun addMine(ctx: Context, text: String) {
        synchronized(this) {
            history.add(Entry(text, System.currentTimeMillis(), true))
            while (history.size > 8) history.removeAt(0)
        }
        post(ctx, alert = false)
    }

    fun takeUnseenKeys(): List<String> = synchronized(this) {
        val l = ArrayList(unseenKeys)
        unseenKeys.clear()
        l
    }

    fun lastRoom(): String = synchronized(this) { lastRoom }

    fun clearMessages(ctx: Context) {
        synchronized(this) { history.clear(); unseenKeys.clear() }
        NotificationManagerCompat.from(ctx).cancel(ID_MSG)
    }

    /** Test notification from the settings screen. */
    fun test(ctx: Context) {
        val c = ctx.applicationContext
        val role = Prefs.role(c)
        synchronized(this) {
            history.clear()
            lastName = if (role == "hunter") "Prey" else "Hunter"
            lastRoom = "test"
            history.add(Entry("This is how your messages will look \uD83D\uDC4B", System.currentTimeMillis(), false))
        }
        post(c, alert = true, sound = !inQuietHours(c), vibe = !inQuietHours(c))
    }

    // ---------------------------------------------------------------- calls
    @SuppressLint("MissingPermission")
    fun postCall(ctx: Context, name: String, video: Boolean, avatar: Bitmap?) {
        val c = ctx.applicationContext
        ensureChannels(c)

        val cardIntent = Intent(c, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("name", name)
            .putExtra("video", video)
        val cardPi = PendingIntent.getActivity(c, 20, cardIntent, immutable() or PendingIntent.FLAG_UPDATE_CURRENT)

        val answerIntent = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ANSWER, true)
        val answerPi = PendingIntent.getActivity(c, 21, answerIntent, immutable() or PendingIntent.FLAG_UPDATE_CURRENT)

        val declinePi = PendingIntent.getBroadcast(
            c, 22, Intent(ACTION_DECLINE).setClass(c, CallActionReceiver::class.java),
            immutable() or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pB = Person.Builder().setName(name).setImportant(true)
        if (avatar != null) pB.setIcon(IconCompat.createWithBitmap(avatar))
        val caller = pB.build()

        val b = NotificationCompat.Builder(c, CH_CALL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText(if (video) "Incoming video call" else "Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(cardPi)
            .setTimeoutAfter(Prefs.ringSecs(c) * 1000L)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, answerPi))
        if (avatar != null) b.setLargeIcon(avatar)
        if (Prefs.fullScreen(c)) b.setFullScreenIntent(cardPi, true)

        try {
            NotificationManagerCompat.from(c).notify(ID_CALL, b.build())
        } catch (e: SecurityException) {
        }
    }
}
