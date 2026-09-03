package com.jklee.poptube

import org.json.JSONArray

/**
 * 페이지의 어떤 스크립트보다 먼저 주입되는 코드.
 *
 * 핵심은 가시성 스푸핑이다. 유튜브는 `visibilitychange` 를 듣고 화면이 꺼지면 재생을 멈추는데,
 * document.hidden 을 항상 false 로 고정하고 관련 이벤트를 캡처 단계에서 삼켜버리면
 * 화면을 꺼도 재생이 유지된다. 삼성 인터넷 "데스크톱 버전"이 되는 이유와 같은 원리다.
 */
object JsInjection {

    fun bootstrap(skipSelectors: List<String>): String {
        val selectors = JSONArray(skipSelectors).toString()
        return """
(function () {
  if (window.__poptubeInstalled) return;
  window.__poptubeInstalled = true;

  var SKIP_SELECTORS = $selectors;

  /* ---------- 1. 가시성 스푸핑 : 화면이 꺼져도 페이지는 "보이는" 상태 ---------- */
  function forceVisible() {
    try {
      Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
      Object.defineProperty(document, 'webkitHidden', { get: function () { return false; }, configurable: true });
      Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
      Object.defineProperty(document, 'webkitVisibilityState', { get: function () { return 'visible'; }, configurable: true });
    } catch (e) {}
  }
  forceVisible();

  var SWALLOW = ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze', 'blur'];
  SWALLOW.forEach(function (type) {
    var handler = function (e) { e.stopImmediatePropagation(); };
    window.addEventListener(type, handler, true);
    document.addEventListener(type, handler, true);
  });

  /* onvisibilitychange 프로퍼티로 직접 붙는 경우도 막는다 */
  try {
    Object.defineProperty(document, 'onvisibilitychange', {
      get: function () { return null; }, set: function () {}, configurable: true
    });
  } catch (e) {}

  /* ---------- 2. 유틸 ---------- */
  function getVideo() {
    return document.querySelector('video.html5-main-video') || document.querySelector('video');
  }
  function getPlayer() {
    return document.querySelector('.html5-video-player');
  }
  function isAdShowing() {
    var p = getPlayer();
    return !!(p && /ad-showing|ad-interrupting/.test(p.className));
  }

  /* PiP 창은 아주 작다. 그냥 들어가면 유튜브 페이지 전체가 축소돼 보여서 쓸모가 없다.
     영상만 창을 꽉 채우도록 CSS 를 덮어씌운다. requestFullscreen 은 사용자 제스처를
     요구해서 네이티브에서 호출하면 막히기 때문에 CSS 로 처리한다. */
  window.__poptubePip = function (on) {
    var id = '__poptube_pip_style';
    var existing = document.getElementById(id);
    if (!on) { if (existing) existing.remove(); return; }
    if (existing) return;
    var s = document.createElement('style');
    s.id = id;
    s.textContent =
      'html,body{overflow:hidden !important;background:#000 !important}' +
      '#movie_player,.html5-video-player{' +
        'position:fixed !important;top:0 !important;left:0 !important;' +
        'width:100vw !important;height:100vh !important;' +
        'z-index:2147483647 !important;background:#000 !important}' +
      '#movie_player video,.html5-video-player video{' +
        'width:100% !important;height:100% !important;object-fit:contain !important}' +
      '.ytp-chrome-bottom,.ytp-chrome-top,.ytp-gradient-bottom,.ytp-gradient-top,' +
      '.ytp-ce-element,.ytp-watermark{display:none !important}';
    document.documentElement.appendChild(s);
  };

  /* 네이티브(알림 / PiP 버튼)에서 호출하는 조작 함수 */
  window.__poptube = {
    play:   function () { var v = getVideo(); if (v) v.play(); },
    pause:  function () { var v = getVideo(); if (v) v.pause(); },
    toggle: function () { var v = getVideo(); if (!v) return; if (v.paused) v.play(); else v.pause(); },
    isPlaying: function () { var v = getVideo(); return !!(v && !v.paused && !v.ended); }
  };

  /* ---------- 3. 광고 스킵 + 재생 상태 보고 ---------- */
  var lastState = null;

  function tick() {
    try {
      /* 건너뛰기 버튼 자동 클릭 */
      for (var i = 0; i < SKIP_SELECTORS.length; i++) {
        var nodes = document.querySelectorAll(SKIP_SELECTORS[i]);
        for (var j = 0; j < nodes.length; j++) {
          var b = nodes[j];
          if (b && b.offsetParent !== null) { try { b.click(); } catch (e) {} }
        }
      }
      /* 건너뛸 수 없는 광고는 끝으로 보낸다 */
      if (isAdShowing()) {
        var av = getVideo();
        if (av && isFinite(av.duration) && av.duration > 0) {
          try { av.currentTime = av.duration; } catch (e) {}
        }
      }

      /* 재생 상태가 바뀔 때만 네이티브에 알린다 */
      var v = getVideo();
      var playing = !!(v && !v.paused && !v.ended && v.readyState > 2);
      var titleEl = document.querySelector('h1.ytd-watch-metadata, h1.title, #title h1');
      var title = (titleEl && titleEl.innerText) || document.title || 'YouTube';
      var suffix = ' - YouTube';
      if (title.slice(-suffix.length) === suffix) title = title.slice(0, -suffix.length);
      title = title.trim().slice(0, 120);

      var key = playing + '|' + title;
      if (key !== lastState) {
        lastState = key;
        if (window.PopTubeNative && window.PopTubeNative.onPlaybackState) {
          window.PopTubeNative.onPlaybackState(playing, title);
        }
      }
    } catch (e) {}
  }

  setInterval(tick, 1000);
  document.addEventListener('DOMContentLoaded', tick);
})();
""".trimIndent()
    }
}
