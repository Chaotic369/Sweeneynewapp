package net.hunterprey.chat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Full-screen "incoming call" card. Shows over the lock screen, even when the app is closed. */
class IncomingCallActivity : Activity() {

    private val doneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun circleButton(color: Int, icon: Int, label: String, onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val frame = FrameLayout(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color)
        frame.background = bg
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.setColorFilter(Color.WHITE)
        val lp = FrameLayout.LayoutParams(dp(30), dp(30))
        lp.gravity = Gravity.CENTER
        frame.addView(iv, lp)
        frame.setOnClickListener { onClick() }
        col.addView(frame, LinearLayout.LayoutParams(dp(72), dp(72)))

        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(10), 0, 0)
        col.addView(t)
        return col
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val name = intent.getStringExtra("name") ?: "Someone"
        val video = intent.getBooleanExtra("video", true)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setBackgroundColor(Color.parseColor("#000000"))
        root.setPadding(dp(24), dp(80), dp(24), dp(64))

        val label = TextView(this)
        label.text = if (video) "Incoming video call" else "Incoming voice call"
        label.setTextColor(Color.parseColor("#99FFFFFF"))
        label.textSize = 15f
        label.gravity = Gravity.CENTER
        root.addView(label)

        val nameView = TextView(this)
        nameView.text = name
        nameView.setTextColor(Color.WHITE)
        nameView.textSize = 30f
        nameView.typeface = Typeface.DEFAULT_BOLD
        nameView.gravity = Gravity.CENTER
        nameView.setPadding(0, dp(10), 0, dp(36))
        root.addView(nameView)

        val avatarSize = dp(140)
        val avatarFrame = FrameLayout(this)
        val avBg = GradientDrawable()
        avBg.shape = GradientDrawable.OVAL
        avBg.setColor(Color.parseColor("#262626"))
        avatarFrame.background = avBg
        val bmp = CallUi.avatar
        if (bmp != null) {
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            avatarFrame.addView(iv, FrameLayout.LayoutParams(avatarSize, avatarSize))
        } else {
            val letter = TextView(this)
            letter.text = name.take(1).uppercase()
            letter.setTextColor(Color.WHITE)
            letter.textSize = 56f
            letter.gravity = Gravity.CENTER
            avatarFrame.addView(letter, FrameLayout.LayoutParams(avatarSize, avatarSize))
        }
        root.addView(avatarFrame, LinearLayout.LayoutParams(avatarSize, avatarSize))

        val spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER
        val decline = circleButton(Color.parseColor("#E5383B"), R.drawable.ic_decline, "Decline") { declineCall() }
        val accept = circleButton(Color.parseColor("#2EB872"), R.drawable.ic_call, "Accept") { acceptCall() }
        val bl = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        buttons.addView(decline, bl)
        buttons.addView(accept, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        ContextCompat.registerReceiver(
            this, doneReceiver, IntentFilter(CallUi.ACTION_DONE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun acceptCall() {
        Ring.stop()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Notifs.EXTRA_ANSWER, true)
        startActivity(i)
        CallUi.dismiss(this)
        finish()
    }

    private fun declineCall() {
        sendBroadcast(Intent(Notifs.ACTION_DECLINE).setClass(this, CallActionReceiver::class.java))
        finish()
    }

    @Deprecated("Back = decline would be surprising; just hide the card, the notification stays")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(doneReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }
}
