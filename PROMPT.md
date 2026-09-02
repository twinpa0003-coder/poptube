# PopTube — 100점 프롬프트 (구현 명세서)

> 이 문서 하나만 다른 AI/개발자에게 주면 동일한 결과물이 나오도록 작성된 완전 명세입니다.
> 작성일: 2026-09-02 / 대상 사용자: 갤럭시(One UI) 안드로이드 단말 1인 사용

---

## 0. 한 줄 요약

**유튜브를 데스크톱 모드 WebView로 감싼 안드로이드 앱을 만든다. 화면을 꺼도 오디오가 끊기지 않고,
PiP(화면 위 떠 있는 창)로 띄운 채 다른 앱을 쓸 수 있으며, 기본 수준의 광고 차단이 적용된다.
Vercel에는 설치 안내 페이지와 원격 규칙(광고 차단 목록) API를 배포한다.**

---

## 1. 배경과 전제 (Why)

- 사용자는 유튜브 프리미엄을 쓰다가 인도 계정이 차단되어 한국 요금제로는 비용 부담이 있다.
- 삼성 인터넷의 "데스크톱 버전"으로 열면 백그라운드/화면 꺼짐 재생이 되는 것을 이미 확인했다.
- 다만 **떠 있는 창(팝업)으로 놓고 폰에서 다른 작업을 병행**하고 싶다.

### 반드시 알고 시작해야 하는 기술적 사실

| # | 사실 | 설계에 미치는 영향 |
|---|---|---|
| 1 | Vercel은 안드로이드 APK를 호스팅/배포하지 않는다 | 앱은 GitHub Releases로, Vercel은 설치 페이지·규칙 API 담당 |
| 2 | 안드로이드 앱은 다른 브라우저 앱(삼성 인터넷)을 자기 안에 임베드할 수 없다 | 삼성 인터넷을 "감싸는" 대신, **내 앱의 WebView가 같은 동작을 재현**한다 |
| 3 | 모바일 유튜브 웹은 `visibilitychange`로 화면이 꺼지면 재생을 멈춘다 | 데스크톱 UA + **문서 시작 시점 JS 주입으로 가시성 스푸핑** 필요 |
| 4 | 안드로이드는 액티비티 단위로만 PiP에 들어간다 | WebView를 담은 액티비티 자체를 PiP로 전환 |
| 5 | 프로세스가 죽으면 오디오도 끊긴다 | 재생 중에는 **미디어 타입 포그라운드 서비스** 유지 |
| 6 | 구글은 WebView에서의 구글 로그인을 차단할 수 있다("보안되지 않은 브라우저") | 데스크톱 Chrome UA로 우회 시도, 실패 시 대체 경로(§9) 제공 |
| 7 | 광고 차단은 유튜브 이용약관 위반이며 탐지·차단될 수 있다 | 기본 수준(도메인 차단 + 스킵 자동 클릭)만, **원격 규칙으로 갱신 가능**하게 |

---

## 2. 확정된 의사결정

| 항목 | 결정 |
|---|---|
| 결과물 | 안드로이드 APK + Vercel 웹페이지 |
| 빌드 | **GitHub Actions** (로컬에 JDK/Android SDK 불필요) |
| 구글 로그인 | **필요** — 데스크톱 UA 우회 시도, 쿠키 영구 저장 |
| 광고 차단 | **기본 차단만** — 광고 도메인 요청 차단 + "광고 건너뛰기" 자동 클릭 |
| 배포 | APK는 GitHub Releases, 웹은 Vercel |

---

## 3. 결과물 목록 (Deliverables)

```
D:\Project\ytpop\
├─ PROMPT.md                     이 문서
├─ README.md                     설치·빌드·배포 절차
├─ .github/workflows/android.yml GitHub Actions APK 빌드
├─ android/                      안드로이드 앱 (Kotlin, Gradle KTS)
└─ web/                          Vercel 배포용 Next.js 앱
```

---

## 4. 안드로이드 앱 명세

### 4.1 기본 정보
- 패키지: `com.jklee.poptube`
- 앱 이름: **PopTube**
- 언어/도구: Kotlin 2.0.x, AGP 8.7.x, Gradle 8.9+, JDK 17
- `minSdk 26`(PiP 최소 요건) / `targetSdk 35` / `compileSdk 35`
- 서명: CI 디버그 서명(사이드로드 목적). 배포 스토어 없음.

### 4.2 필수 기능 (Functional Requirements)

**FR-1. 데스크톱 모드 WebView**
- 시작 URL `https://www.youtube.com`
- User-Agent를 데스크톱 Chrome으로 고정
  `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36`
- `useWideViewPort=true`, `loadWithOverviewMode=true`, 핀치 줌 허용
- FAB **롱프레스로 모바일/데스크톱 UA 토글** 후 새로고침

**FR-2. 로그인 유지**
- UA 문자열만 바꾸면 구글 로그인이 막힌다. 구글은 Client Hints(`Sec-CH-UA`)도 검사하므로
  `WebSettingsCompat.setUserAgentMetadata()` 로 브랜드/플랫폼까지 UA 와 일관되게 맞출 것
- `CookieManager.setAcceptCookie(true)`, `setAcceptThirdPartyCookies(webView, true)`
- 앱 일시정지/종료 시 `CookieManager.flush()` — 재실행 시 로그인 유지
- `WebSettings.domStorageEnabled=true`, `databaseEnabled=true`

**FR-3. 화면 꺼짐/백그라운드 오디오 재생**
- **WebView 를 상속해 `onWindowVisibilityChanged(GONE)` 을 삼킬 것.** 화면이 꺼지면 안드로이드가
  이 콜백을 보내고 Chromium 이 네이티브 레벨에서 미디어를 정지시킨다. JS 의 가시성 스푸핑보다
  아래층이라 그것만으로는 절대 막히지 않는다. **이 처리가 없으면 앱의 존재 이유가 사라진다.**
- `onStop()`에서 **절대** `webView.onPause()` / `pauseTimers()` 호출 금지
- `androidx.webkit`의 `addDocumentStartJavaScript`로 **모든 스크립트보다 먼저** 주입:
  - `document.hidden = false`, `document.visibilityState = 'visible'` 프로퍼티 재정의
  - `visibilitychange` / `webkitvisibilitychange` / `pagehide` / `blur` 이벤트를 캡처 단계에서 차단
  - 해당 기능 미지원 기기에서는 `onPageStarted` + `evaluateJavascript` 폴백
- 재생 중에는 포그라운드 서비스(`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) 유지 → 프로세스 종료 방지

**FR-4. PiP(떠 있는 창)**
- `android:supportsPictureInPicture="true"`,
  `configChanges="screenLayout|smallestScreenSize|screenSize|orientation|keyboard|keyboardHidden"`
- 진입 경로 3가지:
  1. 화면 우하단 반투명 FAB 탭
  2. `onUserLeaveHint()` — 홈 버튼/제스처로 나갈 때 재생 중이면 자동 PiP
  3. Android 12+ `setAutoEnterEnabled(true)`
- PiP 종횡비 16:9, PiP 진입 시 FAB 숨김
- PiP 컨트롤: 재생/일시정지 RemoteAction (JS로 `video.play()/pause()` 호출)

**FR-5. 미디어 알림**
- 포그라운드 서비스가 알림 채널 `playback`에 상시 알림 게시
- 액션: 재생/일시정지, 앱 열기, 종료
- Android 13+ `POST_NOTIFICATIONS` 런타임 권한 요청

**FR-6. 광고 기본 차단**
- `shouldInterceptRequest`에서 차단 도메인/경로 매칭 시 빈 응답 반환
- 기본 차단 목록(번들 내장):
  `doubleclick.net`, `googleadservices.com`, `googlesyndication.com`,
  `google-analytics.com`, `/pagead/`, `/ptracking`, `/api/stats/ads`, `/get_midroll_`
- **`googlevideo.com`은 절대 차단 금지** (실제 영상 스트림)
- 주입 JS가 1초 간격으로 `.ytp-ad-skip-button*`, `.ytp-skip-ad-button` 클릭 및
  광고 재생 중이면 `video.currentTime = video.duration`으로 스킵
- 차단 규칙은 **Vercel `/api/rules`에서 원격 갱신**, 24시간 캐시, 실패 시 내장값 사용

**FR-7. 링크 처리**
- `youtube.com`, `youtu.be`, `google.com`, `accounts.google.com`, `ggpht/ytimg` → 앱 내부에서 처리
- 그 외 도메인 → 시스템 브라우저로 인텐트 전달
- 하드웨어/제스처 뒤로가기 → `webView.canGoBack()`이면 뒤로, 아니면 홈 화면으로 이동(앱 종료 X)

**FR-8. 전체화면 영상**
- `WebChromeClient.onShowCustomView/onHideCustomView` 구현, 몰입 모드 전환

**FR-9. 외부 공유 수신**
- `ACTION_SEND`(text/plain) / `ACTION_VIEW`(youtube 링크) 인텐트 필터 → 해당 URL로 바로 로드
- 다른 앱에서 "PopTube로 공유"가 가능해야 함

### 4.3 비기능 요구사항
- 인터넷 권한 외 불필요한 권한 요청 금지 (위치·연락처·저장소 X)
- 사용자 데이터 외부 전송 없음. Vercel로 나가는 요청은 **규칙 JSON 다운로드뿐**
- 앱 크기 10MB 이하, 콜드 스타트 2초 이내
- 다크 테마 대응(Material3 DayNight)

---

## 5. Vercel 웹 명세

- Next.js(App Router) + TypeScript, 외부 DB 없음
- 페이지 `/`
  - PopTube 소개, **최신 APK 다운로드 버튼**(GitHub Releases 링크)
  - 설치 방법(출처를 알 수 없는 앱 허용), 사용법(PiP 진입 3가지, UA 토글)
  - 구글 로그인이 막혔을 때의 대체 경로 안내
  - 앱 없이 쓰는 방법(삼성 인터넷 팝업 플레이어 세팅) 병기
- API `GET /api/rules` → 광고 차단 규칙 JSON. `s-maxage=3600` 캐시 헤더
  ```json
  { "version": 1, "updatedAt": "...", "blockHosts": [...], "blockPaths": [...],
    "allowHosts": ["googlevideo.com"], "skipSelectors": [...] }
  ```
- API `GET /api/version` → 최신 앱 버전코드/APK URL (앱 내 업데이트 확인용)

---

## 6. CI/CD 명세

`.github/workflows/android.yml`
- 트리거: `push`(main), `workflow_dispatch`, `v*` 태그
- `actions/setup-java@v4` (temurin 17) → `android-actions/setup-android@v3`
  → `gradle/actions/setup-gradle@v4` (`gradle-version: 8.11`)
- `gradle assembleDebug` (Gradle Wrapper 바이너리 없이 동작해야 함)
- 산출물 `app-debug.apk`를 artifact 업로드, 태그 푸시 시 Release에 첨부

---

## 7. 수용 기준 (Acceptance Criteria)

1. 앱 실행 → 유튜브 데스크톱 화면이 뜬다.
2. 구글 로그인 후 앱을 완전히 종료했다가 다시 켜도 로그인이 유지된다.
3. 영상 재생 중 홈으로 나가면 **자동으로 떠 있는 창(PiP)** 이 되고, 다른 앱을 조작해도 계속 재생된다.
4. 전원 버튼으로 **화면을 꺼도 오디오가 최소 10분 이상 끊기지 않는다.**
5. 알림창에서 재생/일시정지가 동작한다.
6. 영상 앞 광고에서 "건너뛰기"가 자동 클릭되고, 배너성 광고 요청이 차단된다.
7. 유튜브 링크를 다른 앱에서 공유하면 PopTube에서 열린다.
8. GitHub Actions에서 APK가 자동 빌드되어 다운로드 가능하다.
9. Vercel 페이지에서 APK 다운로드와 설치 안내가 보인다.

---

## 8. 명시적 비목표 (Out of Scope)

- 영상 다운로드/저장, DRM 우회 — 하지 않는다
- 유튜브 계정 자동화, 시청 기록 조작 — 하지 않는다
- 구글 플레이 스토어 출시 — 정책 위반 소지로 하지 않는다 (개인 사이드로드 전용)
- 시스템 오버레이(SYSTEM_ALERT_WINDOW) 방식 플로팅 창 — v1에서는 PiP로 충분, 향후 옵션

---

## 9. 리스크와 대응

| 리스크 | 대응 |
|---|---|
| WebView에서 구글 로그인 차단 | ① 데스크톱 UA로 우회 시도 ② 실패 시 삼성 인터넷에서 로그인 후 PopTube를 비로그인으로 사용 ③ 최후: 앱 내 즐겨찾기로 대체 |
| 유튜브 UI 변경으로 스킵 셀렉터 무효화 | 셀렉터를 Vercel 원격 규칙으로 분리 → 앱 재빌드 없이 갱신 |
| 광고 차단 탐지("광고 차단기를 사용 중")| 차단 수준을 낮추거나 규칙에서 해당 항목 제거 (원격 즉시 반영) |
| 제조사 배터리 최적화로 백그라운드 종료 | 설정에서 PopTube를 "제한 없음"으로 지정하도록 앱 첫 실행 시 안내 |
| 이용약관 위반 소지 | 개인 단말 사용 전제. 계정 정지 가능성은 사용자가 감수 |

---

## 10. 작업 순서

1. `android/` Gradle 프로젝트 골격 + Manifest
2. `MainActivity` (WebView/UA/쿠키/링크 처리)
3. `AdBlocker` + `RulesRepository`(원격 규칙)
4. 문서 시작 JS 주입(가시성 스푸핑 + 광고 스킵)
5. PiP + FAB + 전체화면
6. `PlaybackService`(포그라운드 서비스 + 알림)
7. `.github/workflows/android.yml`
8. `web/` Next.js + `/api/rules` + `/api/version`
9. README(빌드·배포·설치 절차)
