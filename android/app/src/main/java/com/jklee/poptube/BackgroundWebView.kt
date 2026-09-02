package com.jklee.poptube

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * 화면이 꺼져도 재생을 유지하는 WebView.
 *
 * 화면이 꺼지거나 앱이 백그라운드로 가면 안드로이드가 `onWindowVisibilityChanged(GONE)` 을 보내고,
 * Chromium 은 이걸 받는 즉시 네이티브 레벨에서 미디어를 정지시킨다.
 * 이건 JS 의 document.hidden 스푸핑보다 아래층이라 [JsInjection] 만으로는 절대 막을 수 없다.
 *
 * 그래서 GONE 만 상위로 전달하지 않는다. Chromium 입장에서는 창이 계속 보이는 상태이므로
 * 오디오가 끊기지 않는다. 실제 화면 표시에는 영향이 없다 — 안드로이드가 이미 화면을 껐기 때문이다.
 */
class BackgroundWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /** 재생 유지가 필요 없을 때(예: 앱 종료 중)는 정상 동작으로 돌린다. */
    var keepPlayingInBackground = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepPlayingInBackground && visibility == GONE) return
        super.onWindowVisibilityChanged(visibility)
    }
}
