package com.jklee.poptube

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * 요청 단위 광고 차단. "기본 차단" 수준 — 광고/트래킹 도메인과 경로만 막고
 * 영상 스트림(googlevideo.com)이나 유튜브 본체 API는 건드리지 않는다.
 */
object AdBlocker {

    private val EMPTY_RESPONSE: WebResourceResponse
        get() = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )

    fun intercept(url: String): WebResourceResponse? =
        if (shouldBlock(url)) EMPTY_RESPONSE else null

    fun shouldBlock(url: String): Boolean {
        val rules = RulesRepository.current
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false

        // 허용 목록이 항상 우선한다. 여기에 걸리면 무조건 통과.
        if (rules.allowHosts.any { host == it || host.endsWith(".$it") }) return false

        if (rules.blockHosts.any { host == it || host.endsWith(".$it") }) return true

        val pathAndQuery = (uri.path ?: "") + (uri.query?.let { "?$it" } ?: "")
        return rules.blockPaths.any { pathAndQuery.contains(it, ignoreCase = true) }
    }
}
