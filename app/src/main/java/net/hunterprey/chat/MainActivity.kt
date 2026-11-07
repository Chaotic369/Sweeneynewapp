package net.hunterprey.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    companion object {
        @Volatile var foreground = false
        private const val REQ_PERMS = 41
        private const val REQ_FILE = 42
        private const val START_URL = "https://appassets.androidplatform.net/assets/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    @Volatile private var answerUntil = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.textZoom = 100
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.addJavascriptInterface(Bridge(), "HPNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                openExternal(u)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        openExternal(request.url)
                        v.destroy()
                        return true
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                result.cancel()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                webView.visibility = View.VISIBLE
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean = true
        }

        webView.setDownloadListener { url, _, contentDisposition, mime, _ ->
            handleDownload(url, contentDisposition, mime)
        }

        handleIntent(intent)
        askPermissions()
        webView.loadUrl(START_URL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.getBooleanExtra(Notifs.EXTRA_ANSWER, false)) {
            i.removeExtra(Notifs.EXTRA_ANSWER)
            answerUntil = System.currentTimeMillis() + 45_000L
            Ring.stop()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(Notifs.ID_CALL)
            sendBroadcast(Intent(CallUi.ACTION_DONE).setPackage(packageName))
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        Notifs.clearMessages(this)
        // anything that was waiting in the notification has now been opened
        if (Prefs.hasSession(this) && !ListenerService.running) ListenerService.start(this)
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(false)
        super.onStop()
    }

    override fun onDestroy() {
        foreground = false
        try { webView.removeJavascriptInterface("HPNative") } catch (e: Exception) { }
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
            return
        }
        webView.evaluateJavascript("(function(){try{return window.__hpBack?window.__hpBack():false}catch(e){return false}})()") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    // ---------------------------------------------------------------- permissions
    private fun askPermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** One-time tips so calls and messages keep arriving while the app is closed. */
    private fun showOnboarding() {
        if (Prefs.onboarded(this)) return
        Prefs.setOnboarded(this)
        runOnUiThread {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Keep calls & messages coming")
                .setMessage(
                    "To ring and notify while the app is closed, allow it to run in the background " +
                        "(no battery restriction). You can change notification, ringtone and vibration " +
                        "settings any time from Settings \u2192 Notifications & calls."
                )
                .setPositiveButton("Allow") { _, _ -> requestBatteryExemption() }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    // ---------------------------------------------------------------- links & downloads
    private fun openExternal(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }

    private fun handleDownload(url: String, contentDisposition: String?, mime: String?) {
        if (url.startsWith("blob:")) {
            val js = "(function(){fetch(" + JSONObject.quote(url) + ").then(function(r){return r.blob();}).then(function(b){" +
                "var fr=new FileReader();fr.onloadend=function(){HPNative.saveFile(fr.result,window.__hpDlName||'',b.type||'');};" +
                "fr.readAsDataURL(b);}).catch(function(e){});})();"
            webView.evaluateJavascript(js, null)
            return
        }
        if (url.startsWith("http")) {
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                req.setTitle(name)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading\u2026", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                openExternal(Uri.parse(url))
            }
        }
    }

    private fun saveToDownloads(dataUrl: String, wantedName: String, mimeHint: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return
            val header = dataUrl.substring(0, comma)
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            var mime = mimeHint
            if (mime.isEmpty() && header.startsWith("data:")) mime = header.substring(5).substringBefore(';')
            if (mime.isEmpty()) mime = "application/octet-stream"
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val name = if (wantedName.isNotBlank()) wantedName else "hunterprey_${System.currentTimeMillis()}.$ext"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                FileOutputStream(File(dir, name)).use { it.write(bytes) }
            }
            runOnUiThread { Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Could not save file", Toast.LENGTH_SHORT).show() }
        }
    }

    // ---------------------------------------------------------------- JS bridge
    inner class Bridge {
        @JavascriptInterface
        fun saveSession(role: String, pin: String, allowedRooms: String) {
            val changed = Prefs.saveSession(this@MainActivity, role, pin, allowedRooms)
            if (changed || !ListenerService.running) ListenerService.start(this@MainActivity, changed)
            showOnboarding()
        }

        @JavascriptInterface
        fun getSession(): String {
            val o = JSONObject()
            o.put("role", Prefs.role(this@MainActivity))
            o.put("pin", Prefs.pin(this@MainActivity))
            return o.toString()
        }

        @JavascriptInterface
        fun clearSession() {
            Prefs.clearSession(this@MainActivity)
        }

        @JavascriptInterface
        fun ringStart(video: Boolean) {
            Ring.start(this@MainActivity)
        }

        @JavascriptInterface
        fun ringStop() {
            Ring.stop()
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        @JavascriptInterface
        fun shouldAnswer(): Boolean = answerUntil > System.currentTimeMillis()

        @JavascriptInterface
        fun answerDone() {
            answerUntil = 0L
        }

        @JavascriptInterface
        fun setKeepScreen(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun saveFile(dataUrl: String, name: String, mime: String) {
            Thread { saveToDownloads(dataUrl, name, mime) }.start()
        }
    }
}
