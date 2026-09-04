# PopTube 인수인계 문서

> 다른 AI/개발자가 이 프로젝트를 이어받기 위한 문서다.
> 작성 시점: 2026-09-03 / 갱신: 2026-09-04 / 마지막 빌드: v1.2.0 (versionCode 7)
>
> **읽는 순서: §1 목표 → §3 현재 상태 → §6 이미 밟은 함정 → §7 다음 할 일**
> §6 을 건너뛰면 이미 해결된 문제를 처음부터 다시 겪게 된다.

---

## 1. 목표

유튜브 프리미엄 없이 다음 세 가지를 쓰고 싶다.

1. **화면을 꺼도 오디오가 계속 재생**
2. **떠 있는 창(팝업)으로 영상을 보면서 폰으로 다른 작업**
3. 광고 없이

사용자는 갤럭시(One UI) 사용자이며, 삼성 인터넷의 "데스크톱 버전"으로 열면 1번이 된다는 걸
이미 확인한 상태에서 출발했다. 2번(팝업)까지 자동화하고 싶어서 앱을 만들기로 했다.

**사용자가 명시적으로 밝힌 요구:**
- "동영상이면 화면을 봐야 하는데 소리만 나오는 건 나의 방식에 맞지 않는다"
  → **오디오만 나오는 백그라운드 재생으로는 부족하다. 영상이 보여야 한다.**
- 팝업 창으로 띄워 놓고 폰에서 다른 작업을 병행하고 싶다.

---

## 2. 결정된 사항 (사용자 확인 완료)

| 항목 | 결정 |
|---|---|
| 결과물 | 안드로이드 APK + Vercel 웹페이지 |
| 빌드 | GitHub Actions (로컬에 JDK/Android SDK 없음) |
| 구글 로그인 | 필요하다고 했으나 **현재 차단됨** (§5 참고) |
| 광고 차단 | "기본 차단"만 (도메인 차단 + 건너뛰기 자동 클릭) |

---

## 3. 현재 상태 — 무엇이 되고 무엇이 안 되는가

| 항목 | 상태 | 비고 |
|---|---|---|
| APK 자동 빌드·배포 | ✅ 동작 | GitHub Actions → 릴리스 |
| 서명키 고정 (덮어 설치) | ✅ 동작 | §6.3 |
| 프록시 우회 업로드 | ✅ 동작 | `tools/push.ps1`, §6.1 |
| **화면 꺼짐 오디오 재생** | ✅ **실기기 확인됨** | §6.4 가 핵심 |
| 소프트 키보드 입력 | ✅ 동작 | §6.5 |
| **떠 있는 창 (PiP)** | ❌ **실패** | 3번 시도, §4 |
| 구글 로그인 | ❌ **차단됨** | §5 |
| 광고 차단 | ❓ 미검증 | 차단 건수가 진단 화면에 뜬다 (v1.2.0) |
| JS 주입 (`JsInjection.kt`) | ❓ 미확인 | 성공·실패가 진단 화면에 기록된다 (v1.2.0) |
| **앱 내 진단 화면** | ✅ **추가됨** | v1.2.0, §7.1 해소 — 채팅 FAB 롱프레스 |
| Vercel 배포 | ⏸ 미실행 | 코드는 완성, 배포만 안 함 |

### 마지막 상황 (중요)

v1.1.0 설치 후 사용자가 **"다시 깔았는데 전혀 작동 안 하는데"** 라고 보고했고,
그 "전혀 작동 안 함"이 구체적으로 무엇인지(앱 실행 불가 / PiP 버튼 무반응 / 영상 재생 불가)
**확인되지 않은 채 중단되었다.**

**→ 첫 번째 할 일은 여전히 이 증상을 특정하는 것이다. 다만 이제 수단이 있다.**
v1.2.0 을 깔고 **채팅 FAB 를 길게 눌러** 진단 화면을 연 뒤 "복사" 로 내용을 넘기면 된다(§7.1).
가장 먼저 볼 것: 스냅샷의 **WebView 버전**·**배터리 최적화 제외 여부**, 로그의
`js injection: OK/FAILED`, `render process gone`, `main frame load error`, 그리고 마지막 크래시.

---

## 4. PiP 실패 이력 — 같은 길을 반복하지 말 것

세 번 시도했고 세 번 다 실패했다. 각각 원인이 달랐다.

### 4.1 시도 1 (v1.0.0)
- **증상**: 버튼을 눌러도 아무 반응 없음
- **원인 세 가지**
  1. Android 12+ 에서 `onUserLeaveHint` 를 건너뛰고 `setAutoEnterEnabled` 에만 의존했는데,
     그 플래그가 **JS 브리지의 재생 상태 보고가 있어야만** 갱신됐다. JS 가 죽으면 영영 꺼진 상태.
  2. 실패를 `runCatching` 이 삼켜서 사용자에게 아무 신호가 없었다.
  3. 앱별 PiP 권한(`설정 > 앱 > 특별한 접근 > 픽처 인 픽처`)이 꺼져 있어도 조용히 실패.
- **조치**: 버전 무관 항상 진입 시도 / 실패 시 토스트로 이유 표시 /
  `AudioManager.isMusicActive` 로 재생 판정 / 권한 없으면 설정 화면 열기

### 4.2 시도 2 (v1.0.4)
- **증상**: PiP 진입은 되는데 **페이지 왼쪽 위만 잘려 나옴**. 사실상 소리만 들리는 상태
- **원인**: 데스크톱 UA + `useWideViewPort=true` 라 레이아웃 폭이 1024px 이상 고정.
  PiP 창은 300px 남짓인데 WebView 가 축소하지 않고 잘라서 렌더링.
  주입한 CSS 의 `100vw/100vh` 도 그 1024px 기준이라 무용지물
- **조치**: §4.3

### 4.3 시도 3 (v1.1.0) — 현재 코드
- PiP 진입 **전에** 유튜브 단축키 `f` 를 **실제 `KeyEvent` 로 보내** 플레이어를 전체화면으로 만든다.
  (JS 의 `requestFullscreen()` 은 사용자 제스처를 요구해서 네이티브 호출로는 막힌다.
  키 이벤트는 정상 입력 경로를 타므로 Chromium 이 사용자 조작으로 인정한다)
  전체화면이면 `onShowCustomView` 로 영상 뷰가 액티비티를 채우고, 그 상태로 PiP 에 들어가면
  창 크기에 맞게 정확히 렌더링된다.
- PiP 동안 `useWideViewPort=false` 로 레이아웃 폭을 창 크기에 맞춘다 (폴백)
- `onUserLeaveHint` 에서 전체화면 상태를 제외하던 조건 제거
- **결과: 사용자가 "전혀 작동 안 한다"고 보고. 검증 실패.**

### PiP 자체의 구조적 한계 (사용자에게 이미 고지함)

안드로이드 PiP 창은 **터치 조작이 불가능하다.** 탭하면 `RemoteAction` 버튼만 뜬다.
스크롤하거나 다음 영상을 고르려면 창을 키워 앱으로 돌아가야 한다.
사용자의 원래 요구("팝업으로 띄워 놓고 다른 작업")를 완전히 만족시키지 못한다.

---

## 5. 구글 로그인 — 차단됨

- UA 문자열을 데스크톱 Chrome 으로 바꿨다 → 차단
- `WebSettingsCompat.setUserAgentMetadata()` 로 `Sec-CH-UA` Client Hints 까지
  데스크톱 Chrome 으로 일관되게 맞췄다 → **여전히 차단**

구글이 임베디드 WebView 로그인을 막는 것은 **피싱 방지를 위한 의도적 정책**이며 계속 강화된다.
UA/Client Hints 를 넘는 지문(fingerprint)으로 판별하는 것으로 보인다.

### 사용자에게 제시한 세 가지 대안 (아직 선택 안 함)

1. **비로그인 사용** — 유튜브 공식 앱에서 `공유 → PopTube` 로 링크를 보내는 방식.
   공유 인텐트 수신은 이미 구현되어 있다. 재생·PiP·광고차단 모두 로그인 불필요.
2. **`youtube.com/tv` 페어링** — TV 웹은 코드 페어링으로 로그인된다. 구독/재생목록이 나온다.
   단 UI 가 리모컨 조작용이라 폰 터치로는 불편하다.
3. **삼성 인터넷으로 복귀** — 정식 브라우저라 로그인이 막히지 않는다.

**이 방향을 더 파는 것은 투자 대비 효과가 낮다고 판단된다.**

---

## 6. 이미 밟은 함정 — 반드시 읽을 것

### 6.1 `git push` 가 원리적으로 불가능하다 (회사 네트워크)

SDS 사내 프록시(`70.10.15.10:8080`, 차단 페이지 제목 **`비업무사이트차단`**)가
`github.com/.../git-receive-pack` 으로의 **POST 를 전면 차단**한다.

실측 결과:

| 요청 | 결과 |
|---|---|
| GET (clone / fetch / ls-remote) | 통과 |
| `github.com/.../git-receive-pack` POST (크기 무관) | **차단** (403 + HTML 차단 페이지) |
| `api.github.com` POST, 48KB 이하 | 통과 |
| `api.github.com` POST, 64KB 이상 | **차단** |

> 진단 팁: 프록시 차단은 `Content-Type: text/html` 로 온다.
> GitHub 의 git/API 엔드포인트는 절대 HTML 을 반환하지 않는다(`text/plain` 또는 JSON).
> 이걸로 "프록시 차단"과 "GitHub 의 정상 거부(401/403)"를 구분할 수 있다.

**해결책**: `tools/push.ps1` — GitHub Git Data API 로 파일을 하나씩(각 45KB 미만) 올려
커밋을 만든다. 변경된 파일만 골라 올리고, 결과 트리 해시가 로컬과 일치하는지 검증한다.

```powershell
.\tools\push.ps1          # HEAD 커밋을 원격 main 에 올린다
git fetch origin; git reset --hard origin/main   # 로컬 동기화
```

한계: 개별 파일이 45KB 를 넘으면 올릴 수 없다 (현재 최대 36KB).
**집이나 개인 핫스팟에서는 그냥 `git push` 가 된다.**

### 6.2 빈 리포에서는 Git Data API 가 409 를 낸다

`Git Repository is empty` — 먼저 Contents API(`PUT /contents/{path}`)로 커밋을 하나 만든 뒤에야
blob/tree/commit API 를 쓸 수 있다. `push.ps1` 에 이미 처리되어 있다.

### 6.3 CI 가 매번 다른 키로 서명한다 → "앱이 설치되지 않음"

GitHub Actions 는 매 실행마다 새 VM 이고, 안드로이드 기본 디버그 키스토어
(`~/.android/debug.keystore`)는 그 VM 에서 **새로 생성**된다. 즉 빌드마다 서명이 달라지고,
안드로이드는 서명이 다른 APK 의 덮어쓰기를 거부한다.

**해결책**: 고정 PKCS12 키스토어.
- GitHub Actions 시크릿 `SIGNING_KEYSTORE_B64` (base64)
- 로컬 백업 `keystore/poptube.p12` (`.gitignore` 처리됨)
- 비밀번호 / 별칭 모두 `poptube`
- 지문(SHA256): `40:9A:C3:1F:0B:74:6F:26:E9:CF:93:1E:96:C2:83:56:35:06:39:71:05:FF:23:BD:70:17:6F:F9:47:04:AA:E4`

**이 파일을 잃으면** 새 키로 서명해야 하고, 폰에서 앱 삭제 후 재설치가 필요하다
(로그인 상태와 앱 데이터가 전부 날아간다).

### 6.4 화면 꺼짐 재생의 진짜 원인 — 절대 되돌리지 말 것

JS 로 `document.hidden` 을 속이는 것만으로는 **절대 안 된다.**
화면이 꺼지면 안드로이드가 WebView 에 `onWindowVisibilityChanged(GONE)` 을 보내고,
**Chromium 이 그 시점에 네이티브 레벨에서 미디어를 정지시킨다.** JS 보다 아래층이다.

`BackgroundWebView` 가 이 콜백에서 `GONE` 을 삼킨다. **이게 화면 꺼짐 재생이 되는 유일한 이유다.**

함께 필요한 것:
- `MainActivity.onPause()` / `onStop()` 에서 **절대** `webView.onPause()` / `pauseTimers()` 호출 금지
- 재생 중 미디어 타입 포그라운드 서비스 유지 (프로세스 종료 방지)
- 재생 중 `PARTIAL_WAKE_LOCK` (One UI 절전 대응)
- 사용자가 `설정 > 배터리 > 앱별 배터리 사용 > PopTube` 를 **제한 없음**으로 둘 것

### 6.5 WebView 에서 소프트 키보드가 안 뜬다

입력란을 탭해도 WebView 가 포커스를 얻지 못하면 키보드가 올라오지 않는다.
화면에 다른 포커스 가능한 뷰(FAB 등)가 있으면 기본 포커스 처리가 샌다.

`BackgroundWebView` 에서 `isFocusable` / `isFocusableInTouchMode` 를 켜고
`onTouchEvent` 의 `ACTION_DOWN` 에서 `requestFocus()` 를 호출해 해결했다.

### 6.6 광고 차단 목록에 `googlevideo.com` 을 넣으면 안 된다

실제 영상 스트림이다. 넣으면 재생이 통째로 막힌다. `AdBlocker` 의 `allowHosts` 가 항상 우선한다.

### 6.7 Windows / PowerShell 5.1 함정 (BOM 이 하나는 필수, 둘은 금지)

| 상황 | 문제 | 해결 |
|---|---|---|
| `.ps1` 파일 | BOM 이 **없으면** ANSI 로 읽혀 한글이 깨진다 | **UTF-8 BOM 으로 저장** |
| `git credential fill` 의 stdin | BOM 이 **있으면** `refusing to work with credential missing protocol field` | BOM 없는 임시 파일 + `cmd /c "git credential fill < file"` |
| curl `--config` 파일 | BOM 이 **있으면** `config file option 'header' is unknown` | `[IO.File]::WriteAllText` + `UTF8Encoding($false)` |
| PowerShell 5.1 | `&&` 파이프라인 체인 미지원 (파서 에러) | 명령을 한 줄에 하나씩 |
| PowerShell → 네이티브 exe | 큰따옴표가 든 문자열이 인자 단위로 쪼개진다 | `git commit -F <파일>` |
| Git Bash → 네이티브 exe | `-subj "/CN=..."` 가 `D:/Project/Git/CN=...` 로 변환된다 | `MSYS_NO_PATHCONV=1` + Windows 경로(`D:/...`) 사용 |

### 6.8 `Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS` 는 공개 SDK 상수가 아니다

컴파일 에러가 난다. 문자열 `"android.settings.PICTURE_IN_PICTURE_SETTINGS"` 를 쓰고
`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 로 폴백한다.

### 6.9 `net.openid:appauth` 은 매니페스트 플레이스홀더를 요구한다

라이브러리의 `RedirectUriReceiverActivity` 가 `${appAuthRedirectScheme}` 치환을 요구한다.
값을 주지 않으면 **manifest merger 단계에서 빌드가 죽는다** (컴파일은 멀쩡히 통과한 뒤라 헷갈린다).

```
Attribute data@scheme requires a placeholder substitution
but no value for <appAuthRedirectScheme> is provided.
```

`app/build.gradle.kts` 의 `defaultConfig` 에:
```kotlin
manifestPlaceholders["appAuthRedirectScheme"] = "com.jklee.poptube"
```
`AndroidManifest.xml` 의 OAuth 콜백 intent-filter 스킴과 반드시 같아야 한다.

> 커밋 `e135f43`(appauth 도입)이 푸시된 적이 없어 CI 를 한 번도 안 탔고,
> 그래서 이 실패가 v1.2.0 을 올릴 때에야 드러났다.
> **로컬에 JDK/Android SDK 가 없으므로 커밋은 반드시 푸시해서 CI 로 검증할 것.**

---

## 7. 다음 할 일 (권장 순서)

### 7.1 ✅ 완료 — 앱 내 진단 화면 (v1.2.0)

이 프로젝트가 실패한 근본 원인은 실기기 로그를 한 번도 못 봤다는 것이었다.
증상 보고 → 추측 수정 → 빌드 4분 → 재설치 → 다시 실패의 사이클을 6번 반복했다.

**해결: 채팅 FAB 를 길게 누르면 진단 화면이 열린다.**
(PiP FAB 롱프레스는 데스크톱 모드 토글이 이미 쓰고 있어 채팅 FAB 에 붙였다)

- `DiagnosticLog` — logcat 과 함께 메모리 링버퍼(300줄)에 시각과 함께 쌓는다.
  `i()`/`w()` 시그니처를 유지해 기존 호출부는 그대로다.
- `DiagnosticActivity` — 상태 스냅샷 + 마지막 크래시 + 로그. **복사 / 공유** 버튼이 있어
  스크린샷을 찍지 않고 텍스트로 그대로 넘길 수 있다.
- 스냅샷 항목: 앱·기기·안드로이드 버전, **WebView 패키지 버전**, PiP 지원·권한,
  알림 권한, **배터리 최적화 제외 여부**, 광고 차단 건수, 규칙 버전.
- 크래시 기록 — 액티비티가 뜨기 전에 죽어도 `PopTubeApp` 에서 건 전역 핸들러가
  디스크에 남기고 다음 실행 때 진단 화면 맨 위에 보여준다.

함께 메운 계측 구멍:
- **`onRenderProcessGone`** — 렌더러가 죽으면 빈 화면만 남고 아무 기록이 없었다.
  "전혀 작동 안 함" 의 유력 후보다. 이제 기록 + 안내 + 액티비티 재생성(프로세스당 2회)을 한다.
- `onReceivedError` / `onReceivedHttpError` — 메인 프레임 실패만 기록(서브리소스는 광고차단 노이즈)
- `verifyJsInjection` — 실패만 알리던 것을 성공도 기록하게 바꿔 §4.2 의 "미확인" 을 없앴다.

남은 진단 수단(필요해지면):
- **`adb logcat`** — `platform-tools` 만 받으면 된다(JDK 불필요). 가장 확실하다.
- **`Chrome DevTools` 원격 디버깅** — `setWebContentsDebuggingEnabled(true)` 는 이미 켜져 있다.
  USB + PC 크롬 `chrome://inspect`.

### 7.2 "전혀 작동 안 함" 의 정체 파악

v1.1.0 에서 무엇이 안 되는지 특정한다. 후보:
- 앱이 실행 자체가 안 됨 (크래시) → logcat 필요
- 영상 재생이 안 됨
- PiP 버튼이 무반응 (토스트도 안 뜸)
- PiP 는 되는데 화면이 검거나 여전히 잘림

v1.1.0 에서 추가된 것 중 의심 지점:
- `requestPageFullscreen()` 이 보내는 `KEYCODE_F` — 포커스가 검색창에 있으면 엉뚱하게 입력될 수 있다
- `onUserLeaveHint` 에서 전체화면 제외 조건을 없앤 것 — 의도치 않은 PiP 진입 가능
- PiP 중 `useWideViewPort=false` 전환 — 유튜브 레이아웃이 깨질 수 있다

**직전 정상 동작 버전은 v1.0.3 (커밋 `7450968`) 이다.** 화면 꺼짐 재생은 여기서 확인됐다.
문제가 크면 이 지점으로 되돌린 뒤 다시 쌓아 올리는 것도 방법이다.

### 7.3 【핵심 제안】 PiP 대신 오버레이 창으로 전환

PiP 는 세 번 실패했고, 성공하더라도 **터치 조작이 안 되어 사용자 요구를 만족시키지 못한다.**

`SYSTEM_ALERT_WINDOW`(다른 앱 위에 표시) 권한으로 **떠 있는 창 안에 WebView 를 올리는** 방식이
원래 요구사항에 정확히 맞는다.

| | PiP (현재) | 오버레이 창 |
|---|---|---|
| 영상 보기 | ○ | ○ |
| 창 안에서 스크롤·클릭 | ✗ | **○** |
| 다음 영상 선택 | ✗ | **○** |
| 창 크기 조절·이동 | 제한적 | **자유롭게** |
| 권한 | 기본 허용 | 사용자가 수동 허용 필요 |

구현 요점:
- WebView 를 **액티비티가 아니라 서비스가 소유**하게 만든다.
  `Application` 컨텍스트로 WebView 를 생성해 두고, 액티비티와 오버레이 창 사이를
  `addView`/`removeView` 로 옮긴다. 이렇게 해야 창을 옮겨도 재생이 끊기지 않는다.
- `WindowManager.LayoutParams` 에 `TYPE_APPLICATION_OVERLAY`,
  `FLAG_NOT_FOCUSABLE` 은 **주지 말 것** (주면 창 안에서 입력이 안 된다).
- 드래그 이동 / 모서리 드래그 리사이즈 / 닫기 버튼을 직접 구현해야 한다.
- 권한: `Settings.canDrawOverlays()` 확인 후 `ACTION_MANAGE_OVERLAY_PERMISSION` 으로 유도.

**이게 이 프로젝트에서 남은 가장 가치 있는 작업이라고 판단한다.**

### 7.4 남은 작업

- 광고 차단 실동작 검증 (JS 주입 여부부터)
- Vercel 배포 (`web/` 는 완성됨, `npx vercel --prod` 만 하면 됨).
  배포 후 `android/app/build.gradle.kts` 의 `RULES_URL` 을 실제 도메인으로 교체
- 구글 로그인 방향 결정 (§5)

---

## 8. 프로젝트 구조

```
D:\Project\ytpop\
├─ PROMPT.md                     최초 구현 명세 (100점 프롬프트)
├─ HANDOFF.md                    이 문서
├─ README.md                     빌드·배포·설치 절차
├─ .github/workflows/android.yml GitHub Actions (APK 빌드 → 릴리스 자동 첨부)
├─ tools/
│  ├─ push.ps1                   프록시 우회 업로더 실행 (§6.1)
│  └─ push-via-api.js            실제 업로드 로직
├─ keystore/poptube.p12          서명키 (gitignore, 백업 필수)
├─ android/
│  └─ app/src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/jklee/poptube/
│     │  ├─ MainActivity.kt      WebView 설정, PiP, 전체화면, 링크 처리
│     │  ├─ BackgroundWebView.kt 화면 꺼짐 재생 + 포커스 (§6.4, §6.5)
│     │  ├─ PlaybackService.kt   포그라운드 서비스 + 알림 + WakeLock
│     │  ├─ JsInjection.kt       가시성 스푸핑, 광고 스킵, PiP CSS
│     │  ├─ AdBlocker.kt         요청 차단
│     │  ├─ Rules.kt             차단 규칙 (원격 갱신)
│     │  └─ PopTubeApp.kt        알림 채널
│     └─ res/
└─ web/                          Vercel 배포용 Next.js (미배포)
   └─ app/api/rules/route.ts     원격 광고차단 규칙
```

### 기술 스택
Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.11 / JDK 17 / minSdk 26 / targetSdk 35
패키지 `com.jklee.poptube` · Gradle Wrapper 바이너리 없음(CI 가 Gradle 직접 설치)

### 리포 / 배포
- `https://github.com/twinpa0003-coder/poptube` (Public — Private 전환 권장)
- APK: `https://github.com/twinpa0003-coder/poptube/releases/latest/download/poptube.apk`
- `main` 에 푸시하면 자동 빌드되어 `latest` 태그 릴리스에 APK 가 첨부된다 (약 4분)

---

## 9. 개발 환경

| 항목 | 상태 |
|---|---|
| OS | Windows 11, 기본 셸 **Windows PowerShell 5.1** |
| 작업 폴더 | `D:\Project\ytpop` |
| git | O (`D:\Project\Git`, 포터블) |
| node / npm | O |
| **JDK / Android SDK** | **없음** → 로컬 빌드 불가, CI 필수 |
| **gh CLI** | **없음** → GitHub 조작은 REST API 로 |
| vercel CLI | O |
| 네트워크 | 회사 프록시 (§6.1) |

---

## 10. 절대 금지 사항

1. `MainActivity.onPause()` / `onStop()` 에 `webView.onPause()` / `pauseTimers()` 추가 금지
2. `BackgroundWebView.onWindowVisibilityChanged` 의 `GONE` 차단 처리 제거 금지
   → 1, 2 는 **화면 꺼짐 재생을 죽인다. 이 앱의 존재 이유가 사라진다.**
3. 광고 차단 목록에 `googlevideo.com` 추가 금지 → 재생 자체가 막힌다
4. `keystore/poptube.p12` 를 리포에 커밋 금지 (공개 리포다)
5. 회사 네트워크에서 `git push` 시도 금지 → 안 된다. `tools/push.ps1` 을 쓸 것

---

## 11. 수용 기준 (실기기 검증 필요)

1. 앱 실행 → 유튜브 화면이 뜬다
2. 영상 재생 중 화면을 꺼도 **10분 이상 오디오가 끊기지 않는다** ← *v1.0.3 에서 확인됨*
3. 떠 있는 창에 **영상이 창을 채워 보인다** (페이지가 잘려 보이면 실패) ← *미달성*
4. 떠 있는 창 상태에서 다른 앱을 조작해도 재생이 유지된다
5. 알림창에서 재생/일시정지가 동작한다
6. 영상 앞 광고의 건너뛰기가 자동으로 눌린다 ← *미검증*
7. 다른 앱에서 유튜브 링크 공유 → PopTube 에서 열린다 ← *미검증*
8. 앱을 껐다 켜도 상태가 유지된다

---

## 12. 알아둘 것

- Google Play 배포용이 아니다. 개인 사이드로드 전용.
- 광고 차단은 YouTube 이용약관에 어긋날 수 있고, 유튜브 업데이트로 언제든 깨진다.
  그래서 차단 규칙을 앱이 아니라 `web/app/api/rules/route.ts`(Vercel)로 분리해 뒀다.
  깨지면 앱 재빌드 없이 규칙만 고치면 된다.
- 구글 로그인 우회는 피싱 방지 장치를 우회하는 것이라 본질적으로 불안정하다.
