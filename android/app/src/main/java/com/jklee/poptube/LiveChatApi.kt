package com.jklee.poptube

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatLine(val author: String, val text: String)

class LiveChatApi {
    suspend fun liveChatId(videoId: String, token: String): String? = withContext(Dispatchers.IO) {
        val json = request("https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=${Uri.encode(videoId)}", token)
        json?.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("liveStreamingDetails")
            ?.optString("activeLiveChatId")?.takeIf { it.isNotBlank() }
    }

    suspend fun list(chatId: String, token: String): List<ChatLine> = withContext(Dispatchers.IO) {
        val json = request("https://www.googleapis.com/youtube/v3/liveChat/messages?part=snippet,authorDetails&liveChatId=${Uri.encode(chatId)}", token)
        val items = json?.optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null
            val text = snippet.optString("displayMessage").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChatLine(item.optJSONObject("authorDetails")?.optString("displayName").orEmpty(), text)
        }
    }

    suspend fun send(chatId: String, text: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val conn = (URL("https://www.googleapis.com/youtube/v3/liveChat/messages?part=snippet").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8000; readTimeout = 8000; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("snippet", JSONObject()
            .put("liveChatId", chatId).put("type", "textMessageEvent")
            .put("textMessageDetails", JSONObject().put("messageText", text))).toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        try { conn.responseCode in 200..299 } finally { conn.disconnect() }
    }

    private fun request(url: String, token: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) { DiagnosticLog.w("YouTube API HTTP ${conn.responseCode}"); null }
            else JSONObject(conn.inputStream.bufferedReader().readText())
        } finally { conn.disconnect() }
    }
}
