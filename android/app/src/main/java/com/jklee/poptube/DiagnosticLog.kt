package com.jklee.poptube

import android.app.AppOpsManager
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.webkit.WebViewCompat
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 실기기에서 PiP/WebView 문제를 추측하지 않도록 남기는 진단 로그.
 *
 * logcat 에만 쓰면 USB 를 연결해야 볼 수 있다. 이 프로젝트가 오래 헤맨 이유가 정확히 그것이라
 * (HANDOFF §7.1) 같은 내용을 메모리 링버퍼에도 쌓아 두고 [DiagnosticActivity] 에서 보여준다.
 * 사용자가 USB 없이 스크린샷 한 장으로 상황을 보고할 수 있게 하는 것이 목적이다.
 */
object DiagnosticLog {
    private const val TAG = "PopTube"
    private const val CAPACITY = 300

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<String>(CAPACITY)

    fun i(message: String) {
        Log.i(TAG, message)
        record("I", message, null)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        record("W", message, error)
    }

    private fun record(level: String, message: String, error: Throwable?) {
        val suffix = if (error == null) "" else "  <- ${error.javaClass.simpleName}: ${error.message}"
        // SimpleDateFormat 은 스레드 안전하지 않다. 이 함수는 메인 스레드뿐 아니라 코루틴,
        // JS 브리지, 크래시 핸들러에서도 불리므로 포맷까지 같은 잠금 안에서 처리한다.
        synchronized(entries) {
            val line = "${stamp.format(Date())} $level $message$suffix"
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(line)
        }
    }

    /** 오래된 것부터 순서대로. */
    fun dump(): List<String> = synchronized(entries) { entries.toList() }

    fun clear() = synchronized(entries) { entries.clear() }

    // ------------------------------------------------------------------ 스냅샷

    /**
     * 로그만으로는 "무엇이 안 되는지" 를 좁히기 어렵다. 화면 맨 위에 고정으로 보여줄 현재 상태.
     * 어느 항목이든 실패해도 스냅샷 전체가 죽지 않도록 항목마다 따로 감싼다.
     */
    fun snapshot(context: Context): String {
        fun safe(label: String, block: () -> String) =
            "$label: " + runCatching(block).getOrElse { "?(${it.javaClass.simpleName})" }

        val lines = mutableListOf<String>()
        lines += safe("앱") {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
                       else pkg.versionCode.toLong()
            "${pkg.versionName} (build $code)"
        }
        lines += safe("기기") { "${Build.MANUFACTURER} ${Build.MODEL}" }
        lines += safe("안드로이드") { "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" }
        // WebView 버전은 PiP·JS 주입 문제의 단골 원인이라 반드시 본다.
        lines += safe("WebView") {
            val info = WebViewCompat.getCurrentWebViewPackage(context)
            if (info == null) "확인 불가" else "${info.packageName} ${info.versionName}"
        }
        lines += safe("PiP 지원") {
            val has = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            if (has) "예" else "아니오"
        }
        lines += safe("PiP 권한") { pipPermission(context) }
        lines += safe("알림 권한") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) "해당 없음"
            else if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) "허용" else "거부"
        }
        // 배터리 최적화가 켜져 있으면 One UI 가 화면 꺼짐 재생을 끊는다 (HANDOFF §6.4).
        lines += safe("배터리 최적화 제외") {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) "예"
            else "아니오 (재생이 끊길 수 있음)"
        }
        lines += safe("저사양 모드") {
            val am = context.getSystemService(ActivityManager::class.java)
            if (am?.isLowRamDevice == true) "예" else "아니오"
        }
        lines += safe("광고 차단") { "${AdBlocker.blockedCount}건 차단" }
        lines += safe("차단 규칙") { "v${RulesRepository.current.version}" }
        return lines.joinToString("\n")
    }

    private fun pipPermission(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "해당 없음"
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return "확인 불가"
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(), context.packageName
            )
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> "허용"
            AppOpsManager.MODE_DEFAULT -> "기본값(허용으로 간주)"
            else -> "차단됨 → 설정 > 앱 > PopTube > 특별한 접근 > 픽처 인 픽처"
        }
    }

    // ------------------------------------------------------------------ 크래시

    /**
     * 앱이 아예 뜨지 않으면 앱 안 디버그 화면도 무용지물이다.
     * 마지막 크래시를 디스크에 남겨 다음 실행 때 보여준다.
     */
    fun installCrashHandler(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                prefs.edit()
                    .putString(
                        KEY_CRASH,
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                            "  thread=${thread.name}\n" + trace.take(4000)
                    )
                    .commit()   // 프로세스가 곧 죽으므로 apply() 가 아니라 commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CRASH, null)

    fun clearCrash(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CRASH).apply()
    }

    private const val PREFS = "poptube_diag"
    private const val KEY_CRASH = "last_crash"
}
