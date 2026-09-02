import { ACTIONS_URL, LATEST_APK_URL, LATEST_RELEASE_URL, REPO_URL } from "./config";

export default function Home() {
  return (
    <main>
      <h1>PopTube</h1>
      <p className="lede">
        유튜브를 데스크톱 모드 WebView로 감싼 안드로이드 앱. <strong>떠 있는 창(PiP)</strong>으로 놓고 다른 앱을
        쓸 수 있고, <strong>화면을 꺼도 소리가 이어집니다.</strong> 기본 수준의 광고 차단이 들어 있습니다.
      </p>

      <a className="cta" href={LATEST_APK_URL}>
        APK 내려받기
      </a>
      <a className="cta secondary" href={LATEST_RELEASE_URL}>
        릴리스 목록
      </a>
      <a className="cta secondary" href={ACTIONS_URL}>
        빌드 상태
      </a>

      <h2>설치</h2>
      <ol>
        <li>폰에서 위 <strong>APK 내려받기</strong>를 누릅니다.</li>
        <li>
          경고가 뜨면 <code>설정 &gt; 앱 &gt; 특별한 접근 &gt; 출처를 알 수 없는 앱 설치</code>에서 브라우저에
          권한을 줍니다.
        </li>
        <li>설치 후 첫 실행에서 알림 권한을 허용합니다. (재생 컨트롤 알림용)</li>
        <li>
          <code>설정 &gt; 배터리 &gt; 앱별 배터리 사용 &gt; PopTube</code>를 <strong>제한 없음</strong>으로
          바꿔주세요. 이걸 안 하면 백그라운드 재생이 중간에 끊길 수 있습니다.
        </li>
      </ol>

      <h2>사용법</h2>

      <div className="card">
        <h3>떠 있는 창으로 만들기 (3가지)</h3>
        <ul>
          <li>화면 우하단의 반투명 버튼을 탭</li>
          <li>재생 중에 홈 버튼/제스처로 나가기 → 자동으로 팝업 전환</li>
          <li>Android 12 이상은 자동 진입이 기본 동작</li>
        </ul>
      </div>

      <div className="card">
        <h3>화면 꺼짐 재생</h3>
        <p>
          그냥 전원 버튼을 누르면 됩니다. 앱이 페이지의 가시성 상태를 &quot;보임&quot;으로 고정하기 때문에
          유튜브가 재생을 멈추지 않습니다. 알림창에서 재생/일시정지를 조작할 수 있습니다.
        </p>
      </div>

      <div className="card">
        <h3>모바일 / 데스크톱 모드 전환</h3>
        <p>
          우하단 버튼을 <strong>길게 누르면</strong> UA가 전환되고 새로고침됩니다. 기본값은 데스크톱입니다.
        </p>
      </div>

      <div className="card">
        <h3>다른 앱에서 보내기</h3>
        <p>유튜브 앱이나 카톡에서 링크 공유 → PopTube를 고르면 바로 그 영상이 열립니다.</p>
      </div>

      <h2>구글 로그인이 막힌다면</h2>
      <p>
        구글은 WebView에서의 계정 로그인을 차단하는 경우가 있습니다(&quot;보안되지 않은 브라우저&quot;). 이 앱은
        데스크톱 Chrome UA로 우회를 시도하지만 100% 보장되지는 않습니다. 막혔을 때 순서대로 시도해 보세요.
      </p>
      <ol>
        <li>우하단 버튼을 길게 눌러 모바일 모드로 바꾼 뒤 로그인 → 다시 데스크톱으로 전환</li>
        <li>2단계 인증을 쓰고 있다면 백업 코드/보안 키 대신 휴대폰 알림 방식으로 시도</li>
        <li>그래도 막히면 비로그인으로 사용하고, 볼 영상은 링크 공유로 보내는 방식으로 씁니다</li>
      </ol>

      <h2>앱 없이 쓰는 방법</h2>
      <p className="note">
        갤럭시라면 사실 앱 없이도 상당 부분 됩니다. 비교해 보고 편한 쪽을 쓰세요.
      </p>
      <ul>
        <li>
          <strong>삼성 인터넷 팝업 플레이어</strong> —{" "}
          <code>설정 &gt; 유용한 기능 &gt; 영상 어시스턴트 &gt; 팝업 플레이어</code> 켜기
        </li>
        <li>
          <strong>데스크톱 모드</strong> — 주소창 옆 메뉴 &gt; <code>데스크톱 버전 보기</code>
        </li>
        <li>
          <strong>브라우저 자체를 팝업으로</strong> — 최근 앱 목록에서 앱 아이콘 탭 &gt;{" "}
          <code>팝업 화면으로 열기</code>
        </li>
      </ul>
      <p className="note">
        PopTube가 여기서 더 해주는 것: 매번 수동으로 설정할 필요 없음, 자동 PiP, 알림 컨트롤, 광고 요청 차단,
        공유 인텐트 수신.
      </p>

      <h2>개발자용</h2>
      <ul>
        <li>
          <code>GET /api/rules</code> — 앱이 24시간마다 받아가는 광고 차단 규칙. 유튜브 변경으로 차단이 깨지면 이
          엔드포인트만 고치고 재배포하면 앱은 그대로 둬도 됩니다.
        </li>
        <li>
          <code>GET /api/version</code> — 최신 버전/APK URL
        </li>
        <li>
          소스: <a href={REPO_URL}>{REPO_URL}</a>
        </li>
      </ul>

      <footer>
        개인 단말 사용을 전제로 한 사이드로드 전용 앱입니다. 광고 차단은 YouTube 이용약관에 어긋날 수 있고,
        유튜브 업데이트로 언제든 동작이 깨질 수 있습니다. Google Play에는 올리지 않습니다.
      </footer>
    </main>
  );
}
