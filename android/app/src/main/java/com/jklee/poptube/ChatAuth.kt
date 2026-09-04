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
        // AppAuth 의 fromIntent 는 @NonNull Intent 를 받는다. null 을 그대로 넘기면 컴파일되지 않는다.
        val data = intent ?: run {
            DiagnosticLog.w("chat OAuth response has no intent")
            onDone(false)
            return
        }
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
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
        if (clientId.isBlank() || clientId.contains("YOUR_")) { callback(null); return }
        val json = prefs.getString(KEY_STATE, null)
        val state = runCatching { json?.let { AuthState.jsonDeserialize(it) } }.getOrNull()
        if (state == null) { callback(null); return }
        val authService = service ?: AuthorizationService(context).also { service = it }
        // performActionWithFreshTokens 의 2번째 인자는 ClientAuthentication 또는 Map 이다.
        // clientId(String) 를 넘기면 맞는 오버로드가 없어 컴파일되지 않는다.
        // Google 설치형 앱은 PKCE 라 클라이언트 인증이 없고, client id 는 이미 AuthState 안에 있다.
        state.performActionWithFreshTokens(authService) { token, _, ex ->
            if (ex == null && !token.isNullOrBlank()) {
                prefs.edit().putString(KEY_STATE, state.jsonSerializeString()).apply()
                callback(token)
            } else callback(null)
        }
    }

    fun close() { service?.dispose(); service = null }
}
