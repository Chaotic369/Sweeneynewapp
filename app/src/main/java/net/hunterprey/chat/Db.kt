package net.hunterprey.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/** Talks to the same Firebase Realtime Database the web app uses (plain REST). */
object Db {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val sse: OkHttpClient by lazy { http.newBuilder().readTimeout(75, TimeUnit.SECONDS).build() }

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    class Partner(
        val name: String,
        val avatarUrl: String?,
        val allowed: Boolean,
        val sound: Boolean,
        val haptics: Boolean
    )

    fun getString(path: String): String? = try {
        http.newCall(Request.Builder().url("${Prefs.DB}/$path.json").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /** Returns a JSONObject / String / Number / Boolean, or null when empty or on error. */
    fun getValue(path: String): Any? {
        val s = getString(path) ?: return null
        return try {
            val v = JSONTokener(s).nextValue()
            if (v == JSONObject.NULL) null else v
        } catch (e: Exception) {
            null
        }
    }

    /** Finds which room the saved PIN belongs to (same rule the web app uses). */
    fun resolveRoom(c: Context): String? {
        val pin = Prefs.pin(c)
        if (pin.isEmpty()) return null
        val cached = Prefs.room(c)
        val rooms = Prefs.allowedRooms(c).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var networkFailed = false
        for (r in rooms) {
            val raw = getString("rooms/$r/settings/pin")
            if (raw == null) { networkFailed = true; continue }
            val valid = try {
                val v = JSONTokener(raw).nextValue()
                if (v == JSONObject.NULL) r else v.toString()
            } catch (e: Exception) { r }
            if (valid == pin) {
                if (cached != r) Prefs.setRoom(c, r)
                return r
            }
        }
        return if (networkFailed && cached.isNotEmpty()) cached else null
    }

    fun partner(room: String, myRole: String): Partner {
        val other = if (myRole == "hunter") "prey" else "hunter"
        val s = getValue("rooms/$room/settings") as? JSONObject
        val names = s?.optJSONObject("names")
        val name = names?.optString(other)?.takeIf { it.isNotBlank() }
            ?: if (other == "hunter") "Hunter" else "Prey"
        val avatar = s?.optJSONObject("avatars")?.optString(other)?.takeIf { it.startsWith("http") }
        val allowed = s?.optJSONObject("notificationsAccess")?.opt("prey") != false
        val prefs = s?.optJSONObject("preyPrefs")
        val sound = prefs?.opt("sound") != false
        val haptics = prefs?.opt("haptics") != false
        return Partner(name, avatar, allowed, sound, haptics)
    }

    fun postText(room: String, role: String, text: String): Boolean {
        val body = JSONObject()
            .put("type", "text")
            .put("text", text)
            .put("sender", role)
            .put("time", JSONObject().put(".sv", "timestamp"))
        return try {
            val req = Request.Builder()
                .url("${Prefs.DB}/rooms/$room/messages.json")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    fun patch(path: String, body: JSONObject): Boolean = try {
        val req = Request.Builder()
            .url("${Prefs.DB}/$path.json")
            .patch(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    fun delete(path: String): Boolean = try {
        val req = Request.Builder().url("${Prefs.DB}/$path.json").delete().build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    fun markDelivered(room: String, key: String) {
        patch("rooms/$room/messages/$key", JSONObject().put("delivered", true))
    }

    fun markSeen(room: String, keys: List<String>) {
        for (k in keys) {
            patch("rooms/$room/messages/$k", JSONObject().put("delivered", true).put("seen", true))
        }
    }

    fun bitmap(url: String, size: Int = 128): Bitmap? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val bytes = r.body?.bytes() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    } catch (e: Throwable) {
        null
    }
}
