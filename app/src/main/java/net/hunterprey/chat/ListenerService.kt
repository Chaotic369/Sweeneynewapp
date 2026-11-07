package net.hunterprey.chat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject

/**
 * Always-on connection to the same Firebase database the web app uses.
 * Watches the room for new messages and for incoming calls, so both work with the app closed.
 */
class ListenerService : Service() {

    companion object {
        @Volatile var running = false

        fun start(ctx: Context, restart: Boolean = false) {
            if (!Prefs.hasSession(ctx)) return
            try {
                val i = Intent(ctx, ListenerService::class.java).putExtra("restart", restart)
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenerService::class.java))
        }
    }

    @Volatile private var gen = 0
    @Volatile private var msgCall: Call? = null
    @Volatile private var callCall: Call? = null
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastNetRestart = 0L
    private var lastRungTs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, Notifs.ID_SERVICE, Notifs.serviceNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Prefs.hasSession(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val wasRunning = running
        running = true
        applyWakeLock()
        registerNetwork()
        if (!wasRunning || intent?.getBooleanExtra("restart", false) == true) restartStreams()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        gen++
        msgCall?.cancel()
        callCall?.cancel()
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        try {
            netCb?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        } catch (e: Exception) { }
        netCb = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup
    private fun applyWakeLock() {
        try {
            if (Prefs.keepAwake(this)) {
                if (wake == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hunterprey:listener")
                    wake?.setReferenceCounted(false)
                }
                if (wake?.isHeld == false) wake?.acquire()
            } else {
                wake?.let { if (it.isHeld) it.release() }
            }
        } catch (e: Exception) {
        }
    }

    private fun registerNetwork() {
        if (netCb != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val now = System.currentTimeMillis()
                    if (now - lastNetRestart > 5000) {
                        lastNetRestart = now
                        restartStreams()
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
        }
    }

    @Synchronized
    private fun restartStreams() {
        gen++
        val myGen = gen
        msgCall?.cancel()
        callCall?.cancel()
        Thread({ messageLoop(myGen) }, "hp-messages").start()
        Thread({ callLoop(myGen) }, "hp-calls").start()
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    // ------------------------------------------------------------------ messages
    private fun messageLoop(myGen: Int) {
        var backoff = 2000L
        while (myGen == gen) {
            val room = Db.resolveRoom(this)
            if (room == null) {
                sleepMs(backoff)
                backoff = (backoff * 2).coerceAtMost(30000L)
                continue
            }
            var first = true
            try {
                val url = "${Prefs.DB}/rooms/$room/messages.json?orderBy=%22%24key%22&limitToLast=25"
                stream(url, myGen, { msgCall = it }) { path, data ->
                    val wasFirst = first
                    first = false
                    if (wasFirst) backoff = 2000L
                    onMessageEvent(room, path, data, wasFirst)
                }
            } catch (e: Exception) {
            }
            if (myGen != gen) break
            sleepMs(backoff)
            backoff = (backoff * 2).coerceAtMost(30000L)
        }
    }

    private fun onMessageEvent(room: String, path: String, data: Any?, wasFirst: Boolean) {
        val segs = path.split("/").filter { it.isNotEmpty() }
        if (segs.isEmpty()) {
            val o = data as? JSONObject
            if (wasFirst) {
                handleSnapshot(room, o)
            } else if (o != null) {
                for (k in o.keys().asSequence().toList().sorted()) consider(room, k, o.optJSONObject(k))
            }
        } else if (segs.size == 1) {
            consider(room, segs[0], data as? JSONObject)
        }
    }

    private fun handleSnapshot(room: String, obj: JSONObject?) {
        val keys = obj?.keys()?.asSequence()?.toList()?.sorted() ?: emptyList()
        val last = Prefs.lastKey(this, room)
        if (last.isEmpty()) {
            // first run: start from "now" so old messages never notify
            Prefs.setLastKey(this, room, if (keys.isNotEmpty()) keys.last() else "-")
            return
        }
        for (k in keys) consider(room, k, obj?.optJSONObject(k))
    }

    private fun consider(room: String, key: String, m: JSONObject?) {
        if (m == null) return
        val last = Prefs.lastKey(this, room)
        if (last.isNotEmpty() && key <= last) return
        Prefs.setLastKey(this, room, key)
        val role = Prefs.role(this)
        val sender = m.optString("sender")
        if (sender.isEmpty() || sender == role) return
        if (m.optString("type") == "system") return
        if (m.optBoolean("seen", false)) return
        Notifs.onIncomingMessage(this, room, key, m)
    }

    // ------------------------------------------------------------------ calls
    private fun callLoop(myGen: Int) {
        var backoff = 2000L
        while (myGen == gen) {
            val room = Db.resolveRoom(this)
            if (room == null) {
                sleepMs(backoff)
                backoff = (backoff * 2).coerceAtMost(30000L)
                continue
            }
            try {
                val url = "${Prefs.DB}/rooms/$room/call/status.json"
                stream(url, myGen, { callCall = it }) { path, data ->
                    backoff = 2000L
                    if (path == "/" || path.isEmpty()) {
                        if (data == "ringing") onRinging(room) else onCallEnded()
                    }
                }
            } catch (e: Exception) {
            }
            if (myGen != gen) break
            sleepMs(backoff)
            backoff = (backoff * 2).coerceAtMost(30000L)
        }
    }

    private fun onRinging(room: String) {
        if (!Prefs.callOn(this)) return
        val role = Prefs.role(this)
        val c = Db.getValue("rooms/$room/call") as? JSONObject ?: return
        if (c.optString("status") != "ringing") return
        val caller = c.optString("caller")
        if (caller.isEmpty() || caller == role) return
        val ts = c.optLong("ts", 0L)
        if (System.currentTimeMillis() - ts > 90_000L) return
        if (ts != 0L && ts == lastRungTs) return
        lastRungTs = ts
        if (MainActivity.foreground) return   // the app itself is showing the call card
        val video = c.optBoolean("video", true)
        val partner = Db.partner(room, role)
        val avatar = Notifs.avatarFor(partner.avatarUrl)
        main.post {
            CallUi.show(this, partner.name, video, avatar)
            main.removeCallbacks(stopRinging)
            main.postDelayed(stopRinging, Prefs.ringSecs(this) * 1000L)
        }
    }

    private val stopRinging = Runnable { CallUi.dismiss(this) }

    private fun onCallEnded() {
        main.post {
            main.removeCallbacks(stopRinging)
            if (!MainActivity.foreground) CallUi.dismiss(this)
        }
    }

    // ------------------------------------------------------------------ server-sent events
    private fun stream(url: String, myGen: Int, assign: (Call) -> Unit, onEvent: (String, Any?) -> Unit) {
        val req = Request.Builder().url(url).header("Accept", "text/event-stream").build()
        val call = Db.sse.newCall(req)
        assign(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) return
            val src = resp.body?.source() ?: return
            var event = ""
            val data = StringBuilder()
            while (myGen == gen) {
                val line = src.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trim())
                    }
                    line.isEmpty() -> {
                        if (event == "cancel" || event == "auth_revoked") return
                        if ((event == "put" || event == "patch") && data.isNotEmpty()) {
                            try {
                                val j = JSONObject(data.toString())
                                val path = j.optString("path", "/")
                                val v = j.opt("data")
                                onEvent(path, if (v == null || v == JSONObject.NULL) null else v)
                            } catch (e: Exception) {
                            }
                        }
                        event = ""
                        data.setLength(0)
                    }
                }
            }
        }
    }
}
