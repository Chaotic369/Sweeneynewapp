package net.hunterprey.chat

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Notification and call customisation. Opened from the app's own Settings → "Notifications & calls". */
class SettingsActivity : Activity() {

    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_MSG_SOUND = 51
    private val REQ_RINGTONE = 52
    private val REQ_RING_FILE = 53

    private val BLUE = Color.parseColor("#3797F0")
    private val PANEL = Color.parseColor("#121212")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        scroll.isFillViewport = true
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(16), dp(20), dp(16), dp(40))
        scroll.addView(list)
        setContentView(scroll)
        build()
    }

    private fun rebuild() {
        list.removeAllViews()
        build()
    }

    // ------------------------------------------------------------ row helpers
    private fun addTitle(text: String) {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        t.setPadding(dp(4), 0, 0, dp(8))
        list.addView(t)
    }

    private fun addHeader(text: String) {
        val t = TextView(this)
        t.text = text.uppercase()
        t.setTextColor(Color.parseColor("#80FFFFFF"))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.1f
        t.setPadding(dp(6), dp(22), 0, dp(8))
        list.addView(t)
    }

    private fun card(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(PANEL)
        bg.cornerRadius = dp(16).toFloat()
        row.background = bg
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        row.layoutParams = lp
        return row
    }

    private fun textBlock(title: String, sub: String?): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        col.addView(t)
        if (!sub.isNullOrEmpty()) {
            val s = TextView(this)
            s.text = sub
            s.setTextColor(Color.parseColor("#99FFFFFF"))
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            col.addView(s)
        }
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return col
    }

    private fun addSwitch(title: String, sub: String?, key: String, def: Boolean, onChange: ((Boolean) -> Unit)? = null) {
        val row = card()
        row.addView(textBlock(title, sub))
        val sw = Switch(this)
        sw.isChecked = Prefs.bool(this, key, def)
        sw.setOnCheckedChangeListener { _, checked ->
            Prefs.putBool(this, key, checked)
            onChange?.invoke(checked)
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        list.addView(row)
    }

    private fun addValue(title: String, value: String, onClick: () -> Unit) {
        val row = card()
        row.addView(textBlock(title, null))
        val v = TextView(this)
        v.text = value
        v.setTextColor(BLUE)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        v.gravity = Gravity.END
        row.addView(v)
        row.setOnClickListener { onClick() }
        list.addView(row)
    }

    private fun addAction(title: String, sub: String?, onClick: () -> Unit) {
        val row = card()
        row.addView(textBlock(title, sub))
        row.setOnClickListener { onClick() }
        list.addView(row)
    }

    private fun choose(title: String, labels: Array<String>, values: Array<String>, current: String, onPick: (String) -> Unit) {
        var idx = values.indexOf(current)
        if (idx < 0) idx = 0
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(labels, idx) { d, which ->
                onPick(values[which])
                d.dismiss()
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun label(labels: Array<String>, values: Array<String>, current: String): String {
        val i = values.indexOf(current)
        return if (i >= 0) labels[i] else labels[0]
    }

    private fun soundName(saved: String, type: Int): String {
        if (saved == "silent") return "Silent"
        if (saved.isEmpty()) return "Default"
        return try {
            RingtoneManager.getRingtone(this, Uri.parse(saved))?.getTitle(this) ?: "Custom sound"
        } catch (e: Exception) {
            "Custom sound"
        }
    }

    private fun clock(minutes: Int): String = String.format("%02d:%02d", minutes / 60, minutes % 60)

    // ------------------------------------------------------------ screen
    private fun build() {
        addTitle("Notifications & calls")

        // ---------- messages ----------
        addHeader("Messages")
        addSwitch("Message notifications", "Show new messages in the notification shade", Prefs.MSG_ON, true)

        val pLabels = arrayOf("Full message", "Sender only", "Hidden (just •)")
        val pValues = arrayOf("full", "sender", "hidden")
        addValue("Preview", label(pLabels, pValues, Prefs.preview(this))) {
            choose("Notification preview", pLabels, pValues, Prefs.preview(this)) { Prefs.putStr(this, Prefs.PREVIEW, it) }
        }

        addValue("Message sound", soundName(Prefs.msgSound(this), RingtoneManager.TYPE_NOTIFICATION)) {
            pickSystemSound(RingtoneManager.TYPE_NOTIFICATION, Prefs.msgSound(this), REQ_MSG_SOUND)
        }

        val vLabels = arrayOf("Off", "Short", "Double", "Long")
        val vValues = arrayOf("off", "short", "double", "long")
        addValue("Message vibration", label(vLabels, vValues, Prefs.msgVibe(this))) {
            choose("Message vibration", vLabels, vValues, Prefs.msgVibe(this)) {
                Prefs.putStr(this, Prefs.MSG_VIBE, it)
                Prefs.bumpChannels(this)
                val p = Notifs.vibePattern(it)
                if (p != null) vibratePreview(p)
            }
        }

        addSwitch("Reply box", "Type a reply right inside the notification", Prefs.REPLY_BOX, true)
        addSwitch("Pop up on screen", "Show messages as a banner at the top", Prefs.HEADS_UP, true) { Prefs.bumpChannels(this) }

        val lLabels = arrayOf("Show message", "Hide content", "Hide notification")
        val lValues = arrayOf("show", "private", "hide")
        addValue("On the lock screen", label(lLabels, lValues, Prefs.lock(this))) {
            choose("On the lock screen", lLabels, lValues, Prefs.lock(this)) { Prefs.putStr(this, Prefs.LOCK, it) }
        }

        addSwitch("Quiet hours", "Notifications still arrive, but silently", Prefs.QUIET_ON, false) { list.post { rebuild() } }
        if (Prefs.quietOn(this)) {
            addValue("From", clock(Prefs.quietStart(this))) {
                pickTime(Prefs.quietStart(this)) { Prefs.putInt(this, Prefs.QUIET_START, it); rebuild() }
            }
            addValue("Until", clock(Prefs.quietEnd(this))) {
                pickTime(Prefs.quietEnd(this)) { Prefs.putInt(this, Prefs.QUIET_END, it); rebuild() }
            }
        }
        addAction("Send a test message", "See exactly how a message will look") { Notifs.test(this) }

        // ---------- calls ----------
        addHeader("Calls")
        addSwitch("Ring for incoming calls", "Ring and show the accept card even when the app is closed", Prefs.CALL_ON, true)
        addValue("Ringtone", soundName(Prefs.ringtone(this), RingtoneManager.TYPE_RINGTONE)) {
            pickSystemSound(RingtoneManager.TYPE_RINGTONE, Prefs.ringtone(this), REQ_RINGTONE)
        }
        addAction("Use my own audio file", "Pick any song or sound from your phone") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "audio/*"
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            try { startActivityForResult(i, REQ_RING_FILE) } catch (e: Exception) { toast("No file picker found") }
        }
        val cLabels = arrayOf("Classic", "Pulse", "Long", "Off")
        val cValues = arrayOf("classic", "pulse", "long", "off")
        addValue("Call vibration", label(cLabels, cValues, Prefs.callVibe(this))) {
            choose("Call vibration", cLabels, cValues, Prefs.callVibe(this)) {
                Prefs.putStr(this, Prefs.CALL_VIBE, it)
                val p = Ring.pattern(it)
                if (p != null) vibratePreview(p.copyOfRange(0, minOf(p.size, 3)))
            }
        }
        val rLabels = arrayOf("20 seconds", "30 seconds", "45 seconds", "60 seconds")
        val rValues = arrayOf("20", "30", "45", "60")
        addValue("Ring for", label(rLabels, rValues, Prefs.ringSecs(this).toString())) {
            choose("Ring for", rLabels, rValues, Prefs.ringSecs(this).toString()) { Prefs.putInt(this, Prefs.RING_SECS, it.toInt()) }
        }
        addSwitch("Full-screen call card", "Take over the screen when the phone is locked", Prefs.FULL_SCREEN, true)
        addAction("Test an incoming call", "Rings for a few seconds with your settings") { testCall() }

        // ---------- reliability ----------
        addHeader("Reliability")
        addSwitch("Keep CPU awake", "Most reliable for calls when the phone sleeps, uses more battery", Prefs.KEEP_AWAKE, true) {
            ListenerService.start(this)
        }
        addAction("Allow background activity", "Remove the battery restriction for this app") { requestBatteryExemption() }
        if (Build.VERSION.SDK_INT >= 34) {
            addAction("Allow full-screen calls", "Android 14+: lets the call card appear on the lock screen") {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                } catch (e: Exception) { openAppNotificationSettings() }
            }
        }
        addAction("Android notification settings", "Channels, badges, do-not-disturb exceptions") { openAppNotificationSettings() }

        // ---------- account ----------
        addHeader("Security")
        addAction("Forget saved PIN", "You will have to type the PIN again next time") {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Forget the saved PIN? Notifications and calls stop until you sign in again.")
                .setPositiveButton("Forget") { _, _ ->
                    Prefs.clearSession(this)
                    ListenerService.stop(this)
                    Notifs.clearMessages(this)
                    toast("PIN forgotten")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ------------------------------------------------------------ actions
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    @Suppress("DEPRECATION")
    private fun vibratePreview(pattern: LongArray) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
        }
    }

    private fun pickTime(current: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(
            this, android.R.style.Theme_DeviceDefault_Dialog,
            { _, h, m -> onPick(h * 60 + m) }, current / 60, current % 60, true
        ).show()
    }

    private fun pickSystemSound(type: Int, current: String, req: Int) {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, type == RingtoneManager.TYPE_NOTIFICATION)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(type))
        if (current.isNotEmpty() && current != "silent") {
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
        }
        try { startActivityForResult(i, req) } catch (e: Exception) { toast("No sound picker found") }
    }

    private fun testCall() {
        CallUi.show(this, if (Prefs.role(this) == "hunter") "Prey" else "Hunter", true, null)
        handler.postDelayed({ CallUi.dismiss(this) }, 8000)
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("Already allowed")
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun pickedUri(data: Intent?): Uri? {
        if (data == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as? Uri
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_MSG_SOUND -> {
                val uri = pickedUri(data)
                val def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val v = when {
                    uri == null -> "silent"
                    uri == def -> ""
                    else -> uri.toString()
                }
                Prefs.putStr(this, Prefs.MSG_SOUND, v)
                Prefs.bumpChannels(this)
                rebuild()
            }
            REQ_RINGTONE -> {
                val uri = pickedUri(data)
                val def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val v = if (uri == null || uri == def) "" else uri.toString()
                Prefs.putStr(this, Prefs.RINGTONE, v)
                rebuild()
            }
            REQ_RING_FILE -> {
                val uri = data?.data
                if (uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                    }
                    Prefs.putStr(this, Prefs.RINGTONE, uri.toString())
                    rebuild()
                }
            }
        }
    }
}
