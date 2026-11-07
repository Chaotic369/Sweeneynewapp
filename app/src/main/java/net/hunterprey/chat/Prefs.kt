package net.hunterprey.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Saved login (encrypted) and every user-customisable setting. */
object Prefs {
    const val DB = "https://hunter-prey-default-rtdb.firebaseio.com"

    // setting keys
    const val MSG_ON = "msg_on"
    const val PREVIEW = "preview"          // full | sender | hidden
    const val MSG_SOUND = "msg_sound"      // "" = default, "silent", or a uri
    const val MSG_VIBE = "msg_vibe"        // off | short | double | long
    const val REPLY_BOX = "reply_box"
    const val HEADS_UP = "heads_up"
    const val LOCK = "lock"                // show | private | hide
    const val QUIET_ON = "quiet_on"
    const val QUIET_START = "quiet_start"  // minutes after midnight
    const val QUIET_END = "quiet_end"
    const val CALL_ON = "call_on"
    const val RINGTONE = "ringtone"        // "" = default or a uri
    const val CALL_VIBE = "call_vibe"      // classic | pulse | long | off
    const val RING_SECS = "ring_secs"
    const val FULL_SCREEN = "full_screen"
    const val KEEP_AWAKE = "keep_awake"
    const val CH_VER = "ch_ver"

    @Volatile private var secureCache: SharedPreferences? = null

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("hp_settings", Context.MODE_PRIVATE)

    private fun secure(c: Context): SharedPreferences {
        secureCache?.let { return it }
        synchronized(this) {
            secureCache?.let { return it }
            val ctx = c.applicationContext
            val made: SharedPreferences = try {
                val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    ctx, "hp_session_secure", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                ctx.getSharedPreferences("hp_session_plain", Context.MODE_PRIVATE)
            }
            secureCache = made
            return made
        }
    }

    // ---------- session ----------
    fun role(c: Context): String = secure(c).getString("role", "") ?: ""
    fun pin(c: Context): String = secure(c).getString("pin", "") ?: ""
    fun allowedRooms(c: Context): String = secure(c).getString("allowed", "2007") ?: "2007"
    fun room(c: Context): String = secure(c).getString("room", "") ?: ""
    fun hasSession(c: Context): Boolean = role(c).isNotEmpty() && pin(c).isNotEmpty()

    /** Returns true when something changed. */
    fun saveSession(c: Context, role: String, pin: String, allowed: String): Boolean {
        val s = secure(c)
        val changed = s.getString("role", "") != role || s.getString("pin", "") != pin ||
            s.getString("allowed", "2007") != allowed
        if (changed) {
            s.edit().putString("role", role).putString("pin", pin).putString("allowed", allowed).remove("room").apply()
        }
        return changed
    }

    fun clearSession(c: Context) {
        secure(c).edit().remove("role").remove("pin").remove("room").remove("lastKey").remove("lastKeyRoom").apply()
    }

    fun setRoom(c: Context, room: String) { secure(c).edit().putString("room", room).apply() }

    fun lastKey(c: Context, room: String): String {
        val s = secure(c)
        return if (s.getString("lastKeyRoom", "") == room) s.getString("lastKey", "") ?: "" else ""
    }

    fun setLastKey(c: Context, room: String, key: String) {
        secure(c).edit().putString("lastKeyRoom", room).putString("lastKey", key).apply()
    }

    fun onboarded(c: Context): Boolean = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context) { sp(c).edit().putBoolean("onboarded", true).apply() }

    // ---------- settings ----------
    fun str(c: Context, k: String, d: String): String = sp(c).getString(k, d) ?: d
    fun bool(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun int(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun putStr(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
    fun putBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }
    fun putInt(c: Context, k: String, v: Int) { sp(c).edit().putInt(k, v).apply() }

    fun msgOn(c: Context) = bool(c, MSG_ON, true)
    fun preview(c: Context) = str(c, PREVIEW, "full")
    fun msgSound(c: Context) = str(c, MSG_SOUND, "")
    fun msgVibe(c: Context) = str(c, MSG_VIBE, "short")
    fun replyBox(c: Context) = bool(c, REPLY_BOX, true)
    fun headsUp(c: Context) = bool(c, HEADS_UP, true)
    fun lock(c: Context) = str(c, LOCK, "show")
    fun quietOn(c: Context) = bool(c, QUIET_ON, false)
    fun quietStart(c: Context) = int(c, QUIET_START, 23 * 60)
    fun quietEnd(c: Context) = int(c, QUIET_END, 7 * 60)
    fun callOn(c: Context) = bool(c, CALL_ON, true)
    fun ringtone(c: Context) = str(c, RINGTONE, "")
    fun callVibe(c: Context) = str(c, CALL_VIBE, "classic")
    fun ringSecs(c: Context) = int(c, RING_SECS, 45)
    fun fullScreen(c: Context) = bool(c, FULL_SCREEN, true)
    fun keepAwake(c: Context) = bool(c, KEEP_AWAKE, true)
    fun chVer(c: Context) = int(c, CH_VER, 1)
    fun bumpChannels(c: Context) { putInt(c, CH_VER, chVer(c) + 1) }
}
