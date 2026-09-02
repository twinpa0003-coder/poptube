package com.jklee.poptube

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 광고 차단 규칙. 앱에 기본값이 내장되어 있고, Vercel의 /api/rules 로 갱신할 수 있다.
 * 유튜브가 UI/도메인을 바꿔도 앱을 다시 빌드하지 않고 규칙만 고치면 되도록 분리했다.
 */
data class Rules(
    val version: Int,
    val blockHosts: List<String>,
    val blockPaths: List<String>,
    val allowHosts: List<String>,
    val skipSelectors: List<String>
) {
    companion object {
        /** googlevideo.com 은 실제 영상 스트림이므로 절대 차단 목록에 넣지 않는다. */
        val DEFAULT = Rules(
            version = 1,
            blockHosts = listOf(
                "doubleclick.net",
                "googleadservices.com",
                "googlesyndication.com",
                "google-analytics.com",
                "adservice.google.com",
                "pagead2.googlesyndication.com",
                "static.doubleclick.net"
            ),
            blockPaths = listOf(
                "/pagead/",
                "/ptracking",
                "/api/stats/ads",
                "/get_midroll_",
                "/generate_204?"
            ),
            allowHosts = listOf(
                "googlevideo.com",
                "ytimg.com",
                "ggpht.com"
            ),
            skipSelectors = listOf(
                ".ytp-ad-skip-button",
                ".ytp-ad-skip-button-modern",
                ".ytp-skip-ad-button",
                ".ytp-ad-overlay-close-button",
                "button.ytp-ad-skip-button-container",
                "tp-yt-paper-button#dismiss-button"
            )
        )

        fun parse(json: String): Rules {
            val o = JSONObject(json)
            fun arr(key: String, fallback: List<String>): List<String> {
                val a = o.optJSONArray(key) ?: return fallback
                return (0 until a.length()).map { a.getString(it) }
            }
            return Rules(
                version = o.optInt("version", 1),
                blockHosts = arr("blockHosts", DEFAULT.blockHosts),
                blockPaths = arr("blockPaths", DEFAULT.blockPaths),
                allowHosts = arr("allowHosts", DEFAULT.allowHosts),
                skipSelectors = arr("skipSelectors", DEFAULT.skipSelectors)
            )
        }
    }
}

object RulesRepository {

    private const val PREFS = "poptube_rules"
    private const val KEY_JSON = "json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val TTL_MS = 24L * 60 * 60 * 1000

    @Volatile
    var current: Rules = Rules.DEFAULT
        private set

    /** 캐시에서 즉시 복원한다. 메인 스레드에서 호출해도 될 만큼 가볍다. */
    fun loadCached(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null) ?: return
        current = runCatching { Rules.parse(json) }.getOrDefault(Rules.DEFAULT)
    }

    /** 캐시가 만료됐으면 원격에서 새로 받아온다. 실패해도 조용히 기존 규칙을 유지한다. */
    suspend fun refreshIfStale(context: Context): Unit = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0L)
        if (age < TTL_MS) return@withContext

        runCatching {
            val conn = (URL(BuildConfig.RULES_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            conn.use {
                if (it.responseCode !in 200..299) error("HTTP ${it.responseCode}")
                it.inputStream.bufferedReader().readText()
            }
        }.onSuccess { body ->
            runCatching { Rules.parse(body) }.onSuccess { rules ->
                current = rules
                prefs.edit()
                    .putString(KEY_JSON, body)
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
            }
        }.onFailure {
            Log.i("PopTube", "원격 규칙 갱신 실패, 기존 규칙 사용: ${it.message}")
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }
}
