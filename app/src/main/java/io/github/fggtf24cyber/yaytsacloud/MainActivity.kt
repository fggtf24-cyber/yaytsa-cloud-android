package io.github.fggtf24cyber.yaytsacloud

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/**
 * Яйца Cloud для Android: живой сайт внутри, как в приложении для Windows.
 * Сайт узнаёт приложение по «YaytsaCloudAndroid/» в User-Agent и пишет в мост YaytsaAndroid.post(json):
 * theme (цвет полос), notify (уведомление, пока приложение не на экране), google (вход через браузер), auth (для фоновой проверки).
 * Обратно — событие yaytsa-app: go (клик по уведомлению), google-cred.
 */
class MainActivity : Activity() {
    companion object {
        const val LIVE = "https://fggtf24-cyber.github.io/yaytsa-cloud/"
        /** адрес сайта; в отладочной сборке можно подменить: adb shell am start … --es site http://localhost:4173/ */
        var SITE = LIVE
        const val EXTRA_HREF = "href"
        /** приложение на экране: новое видно на сайте, уведомление не нужно */
        @Volatile var visible = false
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var files: ValueCallback<Array<Uri>>? = null
    private var mic: PermissionRequest? = null
    private val prefs by lazy { getSharedPreferences("yaytsa", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Сайт рисуется под полосами системы, а отступы под них и под клавиатуру даём сами: так полосы в цвет сайта.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION") window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION") window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debug)
        if (debug) intent.getStringExtra("site")?.let { SITE = it }
        web = WebView(this)
        root = FrameLayout(this).apply { addView(web) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        applyTheme(prefs.getString("bg", "#0B0B0B")!!, prefs.getBoolean("light", false))

        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString YaytsaCloudAndroid/${packageManager.getPackageInfo(packageName, 0).versionName}"
        }
        web.addJavascriptInterface(Bridge(), "YaytsaAndroid")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (!req.isForMainFrame) return false
                val url = req.url.toString()
                if (inside(url)) return false
                openExternal(url)
                return true
            }

            // Нет сети: вместо серой страницы браузера — своя, с кнопкой «Повторить».
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) view.loadDataWithBaseURL(SITE, offline(), "text/html", "utf-8", null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                files?.onReceiveValue(null)
                files = cb
                return try { startActivityForResult(params.createIntent(), 1); true }
                catch (e: Exception) { files = null; false }
            }

            override fun onPermissionRequest(req: PermissionRequest) {
                if (!SITE.startsWith(req.origin.toString().removeSuffix("/"))) return req.deny()
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE !in req.resources) return req.deny()
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                else { mic = req; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }
            }
        }
        // Скачивание файлов (программы для Windows, APK) — в обычном браузере.
        web.setDownloadListener { url, _, _, _, _ -> openExternal(url) }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else load(intent.getStringExtra(EXTRA_HREF))
        Updater.check(this)
    }

    /** Свежая главная при каждом запуске: GitHub Pages держит её в кэше до 10 минут. */
    private fun load(href: String?) {
        web.loadUrl(SITE + "?t=" + System.currentTimeMillis() + (href?.takeIf { it.startsWith("#") } ?: ""))
    }

    private fun inside(url: String) = url.startsWith(SITE) || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")

    private fun openExternal(url: String) {
        val u = Uri.parse(url)
        if (u.scheme !in setOf("https", "http", "mailto", "tg")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    @Deprecated("до Android 13 «Назад» приходит сюда")
    override fun onBackPressed() = back()

    /** «Назад» листает разделы сайта, а на первом — прячет приложение, не закрывая его. */
    private fun back() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_HREF)?.let { send(JSONObject().put("type", "go").put("href", it)) }
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() {
        visible = false
        // всё, что пришло до этой минуты, человек видел на сайте — фоновая проверка начнёт с этого момента
        Notifier.touch(this)
        super.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    @Deprecated("выбор файла для сайта")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1) { files?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); files = null }
        else super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode == 2) {
            val req = mic ?: return
            mic = null
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
        }
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }

    /** сообщение сайту: он слушает событие yaytsa-app */
    fun send(msg: JSONObject) {
        web.evaluateJavascript("dispatchEvent(new CustomEvent('yaytsa-app',{detail:$msg}))", null)
    }

    /** полосы системы в цвет шапки сайта, значки на них — тёмные на светлой теме */
    private fun applyTheme(bg: String, light: Boolean) {
        val color = try { Color.parseColor(bg) } catch (_: Exception) { return }
        root.setBackgroundColor(color)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun offline(): String {
        val bg = prefs.getString("bg", "#0B0B0B")
        val fg = if (prefs.getBoolean("light", false)) "#111" else "#F0F0F0"
        return """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:$bg;color:$fg;font:16px system-ui,sans-serif;text-align:center;padding:0 24px;box-sizing:border-box}
button{margin-top:16px;padding:12px 22px;border:0;border-radius:12px;background:#3B82F6;color:#fff;font:inherit}</style>
<div><p>Нет подключения к интернету.</p><button onclick="location.href='$SITE'">Повторить</button></div>"""
    }

    /** Мост для сайта. Вызывается не из главного потока, поэтому всё — через runOnUiThread. */
    inner class Bridge {
        @JavascriptInterface
        fun post(raw: String) = runOnUiThread {
            // писать в мост может только сам сайт, а не чужая страница, открытая по ссылке
            if (web.url?.let(::inside) != true) return@runOnUiThread
            val m = try { JSONObject(raw) } catch (_: Exception) { return@runOnUiThread }
            when (m.optString("type")) {
                "theme" -> {
                    val bg = m.optString("bg")
                    if (!Regex("^#[0-9A-Fa-f]{6}$").matches(bg)) return@runOnUiThread
                    val light = m.optBoolean("light")
                    prefs.edit().putString("bg", bg).putBoolean("light", light).apply()
                    applyTheme(bg, light)
                }
                "notify" -> if (!visible) {
                    Notifier.show(this@MainActivity, m.optString("href").ifEmpty { m.optString("title") }, m.optString("title"), m.optString("body"), m.optString("href"))
                    Notifier.touch(this@MainActivity)
                }
                "google" -> GoogleLogin.start(this@MainActivity, SITE) { id, at ->
                    send(JSONObject().put("type", "google-cred").put("idToken", id).put("accessToken", at))
                }
                "auth" -> {
                    Notifier.setAuth(this@MainActivity, m.optString("uid"), m.optString("refresh"))
                    if (m.optString("uid").isNotEmpty()) askNotifications()
                }
            }
        }
    }

    /** разрешение на уведомления спрашиваем, когда человек вошёл: без аккаунта уведомлять нечем */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("askedNotify", false)) {
            prefs.edit().putBoolean("askedNotify", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
    }
}
