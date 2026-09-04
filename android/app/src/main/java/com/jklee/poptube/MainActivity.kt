package com.jklee.poptube

import android.Manifest
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.media.AudioManager
import android.os.Process
import android.provider.Settings
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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jklee.poptube.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: BackgroundWebView

    private var isPlaying = false
    private var currentTitle = "YouTube"
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var jsInjectionWarned = false
    private lateinit var chatAuth: ChatAuth

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
        lifecycleScope.launch {
            val oldVersion = RulesRepository.current.version
            RulesRepository.refreshIfStale(this@MainActivity)
            if (RulesRepository.current.version != oldVersion) {
                DiagnosticLog.i("rules refreshed: ${RulesRepository.current.version}")
                runOnUiThread {
                    runJs(JsInjection.bootstrap(RulesRepository.current.skipSelectors))
                }
            }
        }

        webView = binding.webView
        chatAuth = ChatAuth(this)
        configureWebView()
        installDocumentStartScript()

        binding.fabPip.setOnClickListener { enterPipSmart() }
        binding.fabPip.setOnLongClickListener { toggleDesktopMode(); true }
        binding.fabChat.setOnClickListener { startChatLogin() }
        // USB 없이 상태를 확인할 수 있는 유일한 통로 (HANDOFF §7.1).
        binding.fabChat.setOnLongClickListener { DiagnosticActivity.open(this); true }

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

        applyDesktopClientHints(this)

        webView.isVerticalScrollBarEnabled = true
        webView.webViewClient = webClient
        webView.webChromeClient = webChrome
        webView.addJavascriptInterface(PlaybackBridge(), "PopTubeNative")
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    /**
     * UA 문자열만 데스크톱으로 바꾸면 구글 로그인이 막힌다.
     * 구글은 Client Hints(Sec-CH-UA) 헤더도 보는데, WebView 는 여기에 자신이 WebView 임을
     * 그대로 실어 보내기 때문이다. 브랜드/플랫폼까지 UA 와 일관되게 맞춰준다.
     *
     * 구글이 임베디드 브라우저 로그인을 막는 건 피싱 방지 목적이라 언제든 다시 막힐 수 있다.
     * 실패하면 FAB 롱프레스로 모바일 모드에서 로그인해 보고, 그래도 안 되면 비로그인으로 쓴다.
     */
    private fun applyDesktopClientHints(settings: WebSettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return

        val desktop = prefs.getBoolean(KEY_DESKTOP, true)
        if (!desktop) {
            // 모바일 모드로 돌아갈 때는 위장을 걷어내야 한다. 남아 있으면 UA 와 어긋나서
            // 오히려 더 눈에 띈다.
            runCatching {
                WebSettingsCompat.setUserAgentMetadata(settings, UserAgentMetadata.Builder().build())
            }
            return
        }

        runCatching {
            fun brand(name: String, major: String) = UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name)
                .setMajorVersion(major)
                .setFullVersion("$major.0.0.0")
                .build()

            val metadata = UserAgentMetadata.Builder()
                .setBrandVersionList(
                    listOf(
                        brand("Chromium", CHROME_MAJOR),
                        brand("Google Chrome", CHROME_MAJOR),
                        brand("Not.A/Brand", "24")
                    )
                )
                .setFullVersion("$CHROME_MAJOR.0.0.0")
                .setPlatform("Windows")
                .setPlatformVersion("15.0.0")
                .setArchitecture("x86")
                .setBitness(64)
                .setModel("")
                .setMobile(false)
                .setWow64(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(settings, metadata)
        }
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
            }.onFailure { DiagnosticLog.w("document-start script install failed", it) }
        } else {
            DiagnosticLog.w("document-start script is not supported; using page callbacks")
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

            // JS 브리지가 어떤 이유로든 동작하지 않아도 프로세스가 살아남도록,
            // 영상 페이지에 들어온 시점에 네이티브 쪽에서 포그라운드 서비스를 띄운다.
            // (백그라운드에서는 서비스 시작이 제한되므로 반드시 화면이 켜져 있을 때 시작해야 한다)
            if (url != null && (url.contains("/watch") || url.contains("youtu.be/"))) {
                PlaybackService.update(this@MainActivity, isPlaying, currentTitle)
            }
            updatePipParams()
            verifyJsInjection()
            DiagnosticLog.i("page finished: $url")
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            // 메인 프레임 실패만 본다. 서브리소스 실패는 광고 차단으로도 흔히 발생해 노이즈가 된다.
            val req = request ?: return
            if (!req.isForMainFrame) return
            DiagnosticLog.w("main frame load error: ${req.url} -> ${error?.errorCode} ${error?.description}")
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            val req = request ?: return
            if (!req.isForMainFrame) return
            DiagnosticLog.w("main frame HTTP ${errorResponse?.statusCode}: ${req.url}")
        }

        /**
         * 렌더러가 죽으면 WebView 는 빈 화면이 되고 앱 프로세스는 살아 있다.
         * 사용자에게 "전혀 작동 안 함" 으로 보이는 대표적인 경우인데 지금까지 아무 기록도 남지 않았다.
         * true 를 돌려주지 않으면 앱까지 함께 죽는다.
         *
         * 죽은 WebView 인스턴스는 다시 쓸 수 없어 액티비티를 새로 만든다.
         * 다만 크래시가 반복되면 무한 재생성이 되므로 횟수를 제한한다.
         */
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean {
            val crashed = detail?.didCrash() == true
            DiagnosticLog.w("render process gone (crashed=$crashed) — 화면이 비면 이것이 원인이다")
            Toast.makeText(this@MainActivity, R.string.render_process_gone, Toast.LENGTH_LONG).show()
            if (renderGoneRecoveries < MAX_RENDER_GONE_RECOVERIES) {
                renderGoneRecoveries++
                DiagnosticLog.i("recreating activity after render process gone ($renderGoneRecoveries)")
                recreate()
            } else {
                DiagnosticLog.w("render process gone repeated; giving up auto-recovery")
            }
            return true
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

    /**
     * 안드로이드에는 앱별 PiP 허용 스위치가 따로 있다
     * (설정 > 앱 > 특별한 접근 > 픽처 인 픽처). 꺼져 있으면 진입이 조용히 실패한다.
     */
    private fun pipAllowedByUser(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return true
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ALLOWED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * JS 브리지가 죽어 있어도 재생 여부를 알아야 한다.
     * 시스템 오디오가 나오고 있으면 재생 중으로 본다.
     */
    private fun isProbablyPlaying(): Boolean {
        if (isPlaying) return true
        return runCatching {
            getSystemService(AudioManager::class.java)?.isMusicActive == true
        }.getOrDefault(false)
    }

    private fun buildPipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(pipToggleAction()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(isProbablyPlaying())
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

    /**
     * @param silent 자동 진입(홈으로 나가기)에서는 실패해도 토스트를 띄우지 않는다.
     *               버튼을 눌러 실패했을 때는 반드시 이유를 보여준다 — 조용히 실패하면
     *               사용자가 원인을 알 방법이 없다.
     */
    private fun enterPip(silent: Boolean = false) {
        if (!pipSupported()) {
            DiagnosticLog.w("PiP unsupported")
            if (!silent) Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        if (!pipAllowedByUser()) {
            DiagnosticLog.w("PiP permission denied by app-op")
            if (!silent) {
                Toast.makeText(this, R.string.pip_permission_needed, Toast.LENGTH_LONG).show()
                openPipSettings()
            }
            return
        }
        runCatching {
            val params = buildPipParams() ?: error("params 생성 실패")
            DiagnosticLog.i("enter PiP requested: playing=${isProbablyPlaying()}, customView=${customView != null}")
            if (!enterPictureInPictureMode(params)) error("시스템이 진입을 거부했습니다")
        }.onFailure { e ->
            DiagnosticLog.w("enter PiP failed", e)
            if (!silent) {
                Toast.makeText(
                    this, getString(R.string.pip_failed, e.message ?: e::class.java.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * PiP 허용 설정 화면을 연다.
     * 전용 액션은 공개 SDK 상수가 아니라서 문자열로 쓰고, 없는 기기를 대비해
     * 앱 정보 화면으로 폴백한다.
     */
    private fun openPipSettings() {
        val candidates = listOf(
            Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS"),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 자동 진입은 외부 링크를 열거나 다른 화면으로 이동할 때도 발동할 수 있다.
        // PiP는 FAB를 누른 경우에만 진입시켜 테스트 결과와 사용자 의도를 일치시킨다.
        DiagnosticLog.i("user left activity; automatic PiP disabled")
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        binding.fabPip.visibility = if (isInPip) View.GONE else View.VISIBLE

        // 데스크톱 UA 는 레이아웃 폭을 1024px 이상으로 잡는다. PiP 창은 300px 남짓이라
        // WebView 가 축소하지 않고 왼쪽 위만 잘라서 보여준다.
        // PiP 동안에는 wide viewport 를 꺼서 레이아웃 폭을 창 크기에 맞춘다.
        webView.settings.useWideViewPort = !isInPip
        webView.post {
            webView.requestLayout()
            runJs("window.__poptubePip && window.__poptubePip($isInPip)")
            DiagnosticLog.i("PiP mode changed: inPip=$isInPip, webView=${webView.width}x${webView.height}")
        }

    }

    /** 수동 요청만 PiP로 보낸다. 진입 후 WebView 크기에 맞춰 플레이어 CSS를 적용한다. */
    private fun enterPipSmart() {
        DiagnosticLog.i("manual PiP button: url=${webView.url}, playing=${isProbablyPlaying()}")
        enterPip()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data?.scheme == "com.jklee.poptube") {
            chatAuth.handleResponse(intent) { ok ->
                runOnUiThread {
                    Toast.makeText(this, if (ok) "채팅 로그인 완료" else "채팅 로그인 실패", Toast.LENGTH_LONG).show()
                    if (ok) openChatPanel()
                }
            }
        }
        resolveStartUrl(intent).let { if (it != DEFAULT_URL) webView.loadUrl(it) }
    }

    private fun startChatLogin() {
        val clientId = getString(R.string.oauth_client_id)
        chatAuth.withFreshToken(clientId) { token ->
            if (token != null) { runOnUiThread { openChatPanel() }; return@withFreshToken }
            if (!chatAuth.start(this, clientId)) {
                Toast.makeText(this, R.string.chat_setup_required, Toast.LENGTH_LONG).show()
                DiagnosticLog.w("chat OAuth not configured")
            }
        }
    }

    private fun openChatPanel() {
        val videoId = currentVideoId() ?: run {
            Toast.makeText(this, R.string.chat_no_live_chat, Toast.LENGTH_LONG).show(); return
        }
        val clientId = getString(R.string.oauth_client_id)
        val lines = TextView(this).apply { setPadding(24, 16, 24, 16); text = "채팅을 불러오는 중…" }
        val input = EditText(this).apply { hint = "메시지 입력"; maxLines = 2 }
        val send = Button(this).apply { text = "보내기"; isEnabled = false }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(ScrollView(this@MainActivity).apply { addView(lines) }, LinearLayout.LayoutParams(-1, 0, 1f)); addView(input); addView(send) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("라이브 채팅").setView(box).setNegativeButton("닫기", null).create()
        val api = LiveChatApi()
        lifecycleScope.launch {
            chatAuth.withFreshToken(clientId) { token ->
                if (token == null) return@withFreshToken
                lifecycleScope.launch(Dispatchers.IO) {
                    val chatId = api.liveChatId(videoId, token)
                    val fetched = chatId?.let { api.list(it, token) }.orEmpty()
                    withContext(Dispatchers.Main) {
                        if (chatId == null) { lines.text = getString(R.string.chat_no_live_chat); return@withContext }
                        lines.text = fetched.joinToString("\n") { "${it.author}: ${it.text}" }.ifBlank { "아직 채팅이 없습니다." }
                        send.isEnabled = true
                        send.setOnClickListener {
                            val message = input.text.toString().trim(); if (message.isEmpty()) return@setOnClickListener
                            lifecycleScope.launch(Dispatchers.IO) {
                                val ok = api.send(chatId, message, token)
                                withContext(Dispatchers.Main) { if (ok) { input.text.clear(); lines.append("\n나: $message") } }
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun currentVideoId(): String? {
        val uri = runCatching { Uri.parse(webView.url ?: return null) }.getOrNull() ?: return null
        return if (uri.host?.endsWith("youtu.be") == true) uri.pathSegments.firstOrNull()
        else uri.getQueryParameter("v")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        // 홈으로 나갈 때 자동 PiP 가 걸리려면 autoEnter 플래그가 미리 켜져 있어야 한다.
        updatePipParams()
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
        chatAuth.close()
        PlaybackBus.listener = null
        runCatching { unregisterReceiver(pipReceiver) }
        PlaybackService.stop(this)
        webView.keepPlayingInBackground = false   // 이제는 정상적으로 정리되어야 한다
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
        applyDesktopClientHints(webView.settings)
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

    /**
     * 주입이 실제로 살아 있는지 스스로 확인한다.
     * 실패를 조용히 넘기면 광고 스킵도 PiP 최적화도 왜 안 되는지 알 수가 없다.
     * 세션당 한 번만 알린다.
     */
    private fun verifyJsInjection() {
        webView.postDelayed({
            runCatching {
                webView.evaluateJavascript("typeof window.__poptube") { result ->
                    val alive = result != null && !result.contains("undefined")
                    // 성공도 남긴다. 실패만 기록하면 "주입이 됐는지" 자체가 계속 미확인으로 남는다.
                    DiagnosticLog.i("js injection: ${if (alive) "OK" else "FAILED"} (typeof=$result)")
                    if (!alive && !jsInjectionWarned) {
                        jsInjectionWarned = true
                        Toast.makeText(this, R.string.js_injection_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, 2500)
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

        private const val MAX_RENDER_GONE_RECOVERIES = 2

        /** recreate() 는 액티비티를 새로 만들므로 인스턴스 필드로는 셀 수 없다. 프로세스 범위로 둔다. */
        private var renderGoneRecoveries = 0

        private const val CHROME_MAJOR = "125"

        /**
         * 삼성 인터넷 "데스크톱 버전"과 같은 효과를 내는 UA.
         * [applyDesktopClientHints] 의 platform("Windows") 과 반드시 일관되어야 한다.
         * 둘이 어긋나면 구글이 위장을 바로 잡아낸다.
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$CHROME_MAJOR.0.0.0 Safari/537.36"

        private val INTERNAL_HOSTS = listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "ytimg.com", "ggpht.com",
            "google.com", "accounts.google.com", "gstatic.com", "googleusercontent.com"
        )
    }
}
