package com.jklee.poptube

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import net.openid.appauth.*

/** Google 로그인은 WebView가 아니라 외부 브라우저에서 수행한다. */
class ChatAuth(private val context: Context) {
    companion object {
        const val REQUEST_CODE = 7101
        private const val PREFS = "poptube_chat_auth"
        private const val KEY_STATE = "auth_state"
        private const val AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN = "https://oauth2.googleapis.com/token"
        private const val REDIRECT = "com.jklee.poptube:/oauth2redirect"
        private const val SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var service: AuthorizationService? = null

    fun start(activity: Activity, clientId: String): Boolean {
        if (clientId.isBlank() || clientId.contains("YOUR_")) return false
        val config = AuthorizationServiceConfiguration(Uri.parse(AUTH), Uri.parse(TOKEN))
        val request = AuthorizationRequest.Builder(
            config, clientId, ResponseTypeValues.CODE, Uri.parse(REDIRECT)
        ).setScope(SCOPE).build()
        service = AuthorizationService(context)
        activity.startActivityForResult(service!!.getAuthorizationRequestIntent(request), REQUEST_CODE)
        DiagnosticLog.i("chat OAuth started")
        return true
    }

    fun handleResponse(intent: Intent?, onDone: (Boolean) -> Unit) {
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        if (response == null || error != null) {
            DiagnosticLog.w("chat OAuth response failed", error)
            onDone(false)
            return
        }
        val state = AuthState(response.request.configuration)
        state.update(response, null)
        (service ?: AuthorizationService(context)).performTokenRequest(response.createTokenExchangeRequest()) { token, ex ->
            state.update(token, ex)
            if (ex == null) prefs.edit().putString(KEY_STATE, state.jsonSerializeString()).apply()
            onDone(ex == null)
        }
    }

    fun withFreshToken(clientId: String, callback: (String?) -> Unit) {
        val json = prefs.getString(KEY_STATE, null)
        val state = runCatching { json?.let { AuthState.jsonDeserialize(it) } }.getOrNull()
        if (state == null) { callback(null); return }
        val authService = service ?: AuthorizationService(context).also { service = it }
        state.performActionWithFreshTokens(authService, clientId) { token, _, ex ->
            if (ex == null && !token.isNullOrBlank()) {
                prefs.edit().putString(KEY_STATE, state.jsonSerializeString()).apply()
                callback(token)
            } else callback(null)
        }
    }

    fun close() { service?.dispose(); service = null }
}
