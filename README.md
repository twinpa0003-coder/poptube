# PopTube

유튜브를 데스크톱 모드 WebView로 감싼 안드로이드 앱.
**떠 있는 창(PiP) 재생 + 화면 꺼짐 오디오 유지 + 기본 광고 차단.**

구현 명세는 [PROMPT.md](PROMPT.md)에 전부 정리돼 있다.

```
ytpop/
├─ PROMPT.md                       100점 프롬프트(전체 명세)
├─ android/                        안드로이드 앱 (Kotlin)
├─ web/                            Vercel 배포용 Next.js
└─ .github/workflows/android.yml   APK 자동 빌드
```

---

## 1. GitHub에 올리고 APK 받기

이 PC에는 JDK/Android SDK가 없으므로 **빌드는 GitHub Actions가 한다.**

```bash
cd D:/Project/ytpop && git init -b main && git add -A && git commit -m "PopTube: 초기 구현"
```

GitHub에서 빈 리포지토리 `poptube`를 만든 뒤(README 체크 해제):

```bash
cd D:/Project/ytpop && git remote add origin https://github.com/<내아이디>/poptube.git && git push -u origin main
```

푸시하면 Actions가 자동으로 돌아간다.

- 빌드 결과: 리포 → **Actions** 탭 → 최신 실행 → Artifacts의 `poptube-apk`
- 폰에서 바로 받고 싶으면 릴리스를 만든다:

```bash
cd D:/Project/ytpop && git tag v1.0.0 && git push origin v1.0.0
```

그러면 `https://github.com/<내아이디>/poptube/releases/latest/download/poptube.apk` 로 고정 링크가 생긴다.

### 로컬에 Android Studio가 생겼다면
`android/` 폴더를 열고 Sync → Run. (Wrapper 바이너리는 없으니 Studio가 만들게 두거나 `gradle wrapper` 실행)

---

## 2. Vercel 배포

```bash
cd D:/Project/ytpop/web && npm install && npx vercel --prod
```

배포 후 두 가지를 자기 값으로 맞춘다.

1. Vercel 프로젝트 환경변수
   - `NEXT_PUBLIC_GITHUB_OWNER` = 내 GitHub 아이디
   - `NEXT_PUBLIC_GITHUB_REPO` = `poptube`
2. `android/app/build.gradle.kts`의 `RULES_URL`을 실제 배포 도메인으로 변경
   ```kotlin
   buildConfigField("String", "RULES_URL", "\"https://<내프로젝트>.vercel.app/api/rules\"")
   ```
   바꾼 뒤 다시 푸시하면 새 APK가 빌드된다. (안 바꿔도 앱은 내장 기본 규칙으로 정상 동작한다)

> 회사 프록시 환경이면 `npx vercel` 로그인 단계에서 막힐 수 있다. 그럴 땐 Vercel 웹 대시보드에서
> GitHub 리포를 Import 하고 **Root Directory 를 `web`** 으로 지정하면 CLI 없이 배포된다.

---

## 3. 폰에 설치

1. APK 다운로드 → 설치 (출처를 알 수 없는 앱 허용 필요)
2. 첫 실행 시 알림 권한 허용
3. `설정 > 배터리 > 앱별 배터리 사용 > PopTube` → **제한 없음**
   (이걸 빼먹으면 백그라운드 재생이 몇 분 뒤 끊긴다)

### 라이브 채팅 로그인 설정

라이브 채팅은 Google OAuth를 외부 브라우저에서 진행하고 YouTube Live Chat API를 사용한다.
Android 앱을 빌드하기 전에 Google Cloud에서 YouTube Data API v3를 활성화하고 OAuth Client ID를 만든 뒤
`android/app/src/main/res/values/strings.xml`의 `oauth_client_id`를 교체해야 한다.
Google 로그인 화면은 PopTube WebView 안에서 열지 않는다.

---

## 4. 동작 확인 체크리스트

- [ ] 앱 실행 시 유튜브 데스크톱 화면이 뜬다
- [ ] 구글 로그인 후 앱을 껐다 켜도 로그인이 유지된다
- [ ] 재생 중 우하단 PiP 버튼을 누르면 떠 있는 창이 되고 다른 앱을 써도 계속 재생된다
- [ ] 화면을 꺼도 10분 이상 소리가 유지된다
- [ ] 알림창의 재생/일시정지가 동작한다
- [ ] 영상 앞 광고의 "건너뛰기"가 자동으로 눌린다
- [ ] 다른 앱에서 유튜브 링크 공유 → PopTube로 열린다

---

## 5. 고장났을 때

| 증상 | 확인할 것 |
|---|---|
| 화면 끄면 소리가 멈춤 | 배터리 최적화 해제 여부. `MainActivity.onPause()`에 `webView.onPause()`가 들어가지 않았는지 |
| 영상이 아예 재생 안 됨 | 차단 규칙에 `googlevideo.com`이 들어갔는지 (**절대 금지**) |
| 광고 스킵이 안 됨 | `web/app/api/rules/route.ts`의 `skipSelectors` 갱신 후 재배포 → 앱은 최대 24시간 내 반영 |
| 구글 로그인 차단 | WebView 로그인은 지원하지 않음. YouTube 앱에서 영상 링크를 PopTube로 공유 |
| "광고 차단기 사용 중" 경고 | `blockHosts`/`blockPaths`를 줄여서 재배포 |

---

## 6. 서명키 (중요)

APK 는 고정 키스토어로 서명한다. **이게 바뀌면 기존 앱 위에 업데이트를 설치할 수 없다**
("앱이 설치되지 않음" — 안드로이드가 서명이 다른 APK 의 덮어쓰기를 막는다).

- 키스토어는 GitHub Actions 시크릿 `SIGNING_KEYSTORE_B64` 에 base64 로 들어 있다
- 로컬 백업: `keystore/poptube.p12` (`.gitignore` 처리됨, 리포에 올라가지 않는다)
- 비밀번호/별칭 모두 `poptube`
- 인증서 지문(SHA256): `40:9A:C3:1F:0B:74:6F:26:E9:CF:93:1E:96:C2:83:56:35:06:39:71:05:FF:23:BD:70:17:6F:F9:47:04:AA:E4`

**이 파일을 잃어버리면** 새 키로 서명해야 하고, 폰에서 앱을 삭제 후 재설치해야 한다
(로그인 상태와 앱 데이터가 전부 날아간다). `keystore/poptube.p12` 를 개인 저장소에 백업해 둘 것.

시크릿을 다시 등록해야 하면 GitHub 리포 → Settings → Secrets and variables → Actions 에서
`SIGNING_KEYSTORE_B64` 를 `keystore/poptube.p12.b64` 의 내용으로 갱신하면 된다.

---

## 7. 알아둘 것

- Google Play 배포용이 아니다. 개인 사이드로드 전용.
- 광고 차단은 YouTube 이용약관에 어긋날 수 있으며, 계정 제재 가능성은 사용자가 감수한다.
- 유튜브 프런트엔드가 바뀌면 스킵 셀렉터가 깨진다. 그래서 규칙을 원격(`/api/rules`)으로 분리해 뒀다.
