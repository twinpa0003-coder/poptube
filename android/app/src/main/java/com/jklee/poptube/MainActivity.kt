package com.jklee.poptube

import android.Manifest
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jklee.poptube.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    private var isPlaying = false
    private var currentTitle = "YouTube"
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val prefs by lazy { getSharedPreferences("poptube", Context.MODE_PRIVATE) }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 거부해도 재생은 된다 */ }

    /** PiP 창의 재생/일시정지 버튼 */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE) runJs("window.__poptube && window.__poptube.toggle()")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        RulesRepository.loadCached(this)
        lifecycleScope.launch { RulesRepository.refreshIfStale(this@MainActivity) }

        webView = binding.webView
        configureWebView()
        installDocumentStartScript()

        binding.fabPip.setOnClickListener { enterPip() }
        binding.fabPip.setOnLongClickListener { toggleDesktopMode(); true }

        registerPipReceiver()
        PlaybackBus.listener = { command ->
            runOnUiThread {
                when (command) {
                    PlaybackBus.Command.TOGGLE -> runJs("window.__poptube && window.__poptube.toggle()")
                    PlaybackBus.Command.PAUSE -> runJs("window.__poptube && window.__poptube.pause()")
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> webChrome.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    // 앱을 죽이지 않고 홈으로 보낸다. 재생이 끊기지 않도록.
                    else -> moveTaskToBack(true)
                }
            }
        })

        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            webView.loadUrl(resolveStartUrl(intent))
        }

        if (!prefs.getBoolean("battery_hint_shown", false)) {
            Toast.makeText(this, R.string.battery_hint, Toast.LENGTH_LONG).show()
            prefs.edit().putBoolean("battery_hint_shown", true).apply()
        }
    }

    // ---------------------------------------------------------------- WebView

    private fun configureWebView() = with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        // 자동재생과 백그라운드 재생의 핵심 스위치
        mediaPlaybackRequiresUserGesture = false
        userAgentString = currentUserAgent()

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)   // 구글 로그인에 필요
        }

        webView.isVerticalScrollBarEnabled = true
        webView.webViewClient = webClient
        webView.webChromeClient = webChrome
        webView.addJavascriptInterface(PlaybackBridge(), "PopTubeNative")
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    /**
     * 페이지 스크립트보다 먼저 실행되는 주입. 유튜브가 visibilitychange 리스너를 등록하기 전에
     * document.hidden 을 고정해야 화면 꺼짐 재생이 확실히 동작한다.
     */
    private fun installDocumentStartScript() {
        val script = JsInjection.bootstrap(RulesRepository.current.skipSelectors)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView, script, setOf("https://*.youtube.com", "https://*.youtu.be")
                )
            }
        }
        // 미지원 기기용 폴백은 onPageStarted / onPageFinished 에서 처리한다.
    }

    private val webClient = object : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            return AdBlocker.intercept(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            if (uri.scheme != "http" && uri.scheme != "https") return true   // intent:// 등은 무시
            if (isInternal(uri)) return false
            return runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            }.getOrDefault(false)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runJs(JsInjection.bootstrap(RulesRepository.current.skipSelectors))
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            runJs(JsInjection.bootstrap(RulesRepository.current.skipSelectors))
            CookieManager.getInstance().flush()
        }
    }

    private val webChrome = object : WebChromeClient() {

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            binding.fullscreenContainer.addView(view)
            binding.fullscreenContainer.visibility = View.VISIBLE
            binding.webView.visibility = View.GONE
            binding.fabPip.hide()
            setSystemBarsVisible(false)
        }

        override fun onHideCustomView() {
            customView?.let { binding.fullscreenContainer.removeView(it) }
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            binding.fullscreenContainer.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            binding.fabPip.show()
            setSystemBarsVisible(true)
        }
    }

    /** JS → 네이티브. 재생 상태가 바뀔 때만 불린다. */
    inner class PlaybackBridge {
        @JavascriptInterface
        fun onPlaybackState(playing: Boolean, title: String) {
            runOnUiThread {
                isPlaying = playing
                currentTitle = title.ifBlank { "YouTube" }
                if (playing) {
                    PlaybackService.update(this@MainActivity, true, currentTitle)
                } else {
                    // 일시정지 상태에서도 알림은 남겨서 바로 다시 재생할 수 있게 한다.
                    PlaybackService.update(this@MainActivity, false, currentTitle)
                }
                updatePipParams()
            }
        }
    }

    // -------------------------------------------------------------------- PiP

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun buildPipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(pipToggleAction()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun pipToggleAction(): RemoteAction {
        val icon = Icon.createWithResource(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val label = getString(if (isPlaying) R.string.action_pause else R.string.action_play)
        val pending = android.app.PendingIntent.getBroadcast(
            this, 10,
            Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(icon, label, label, pending)
    }

    private fun updatePipParams() {
        if (!pipSupported()) return
        runCatching { buildPipParams()?.let { setPictureInPictureParams(it) } }
    }

    private fun enterPip() {
        if (!pipSupported()) {
            Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { buildPipParams()?.let { enterPictureInPictureMode(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 홈으로 나갈 때 재생 중이면 자동으로 떠 있는 창이 된다. (Android 12+ 는 autoEnter 로 처리)
        if (isPlaying && customView == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        binding.fabPip.visibility = if (isInPip) View.GONE else View.VISIBLE
        if (isInPip) {
            // PiP 창에서는 유튜브 UI를 최대한 걷어내고 영상만 크게 보이도록 시도한다.
            runJs(
                """
                (function(){
                  var p = document.querySelector('.html5-video-player');
                  if (p && p.requestFullscreen) { try { p.requestFullscreen(); } catch(e){} }
                })();
                """.trimIndent()
            )
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveStartUrl(intent).let { if (it != DEFAULT_URL) webView.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    /**
     * 의도적으로 webView.onPause() / pauseTimers() 를 호출하지 않는다.
     * 여기서 멈추면 화면 꺼짐 재생이 죽는다. 이 앱의 존재 이유가 사라지므로 절대 추가하지 말 것.
     */
    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        PlaybackBus.listener = null
        runCatching { unregisterReceiver(pipReceiver) }
        PlaybackService.stop(this)
        webView.removeJavascriptInterface("PopTubeNative")
        webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun registerPipReceiver() {
        val filter = IntentFilter(ACTION_PIP_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipReceiver, filter)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun currentUserAgent(): String =
        if (prefs.getBoolean(KEY_DESKTOP, true)) DESKTOP_UA else webView.settings.userAgentString

    private fun toggleDesktopMode() {
        val next = !prefs.getBoolean(KEY_DESKTOP, true)
        prefs.edit().putBoolean(KEY_DESKTOP, next).apply()
        webView.settings.userAgentString = if (next) DESKTOP_UA else null
        Toast.makeText(this, if (next) R.string.ua_desktop_on else R.string.ua_desktop_off, Toast.LENGTH_SHORT).show()
        webView.reload()
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun runJs(js: String) {
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** 공유/링크로 들어온 유튜브 URL을 찾아낸다. 없으면 홈. */
    private fun resolveStartUrl(intent: Intent?): String {
        val candidate = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return DEFAULT_URL

        val match = Regex("""https?://\S+""").find(candidate)?.value ?: return DEFAULT_URL
        val uri = runCatching { Uri.parse(match) }.getOrNull() ?: return DEFAULT_URL
        return if (isInternal(uri)) match else DEFAULT_URL
    }

    companion object {
        private const val DEFAULT_URL = "https://www.youtube.com"
        private const val KEY_DESKTOP = "desktop_mode"
        private const val ACTION_PIP_TOGGLE = "com.jklee.poptube.PIP_TOGGLE"

        /** 삼성 인터넷 "데스크톱 버전"과 같은 효과를 내는 UA. 구글 로그인 우회에도 이게 유리하다. */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Safari/537.36"

        private val INTERNAL_HOSTS = listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com", "ggpht.com",
            "google.com", "accounts.google.com", "gstatic.com", "googleusercontent.com"
        )
    }
}
