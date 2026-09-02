import { NextResponse } from "next/server";

/**
 * 앱이 24시간마다 받아가는 광고 차단 규칙.
 * 유튜브가 UI나 도메인을 바꿔서 차단이 깨지면 이 파일만 고치고 재배포하면 된다. (앱 재설치 불필요)
 *
 * 주의: googlevideo.com 은 실제 영상 스트림이므로 blockHosts 에 절대 넣지 말 것. 재생이 안 된다.
 */
const RULES = {
  version: 3,
  updatedAt: "2026-09-02",
  blockHosts: [
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
    "google-analytics.com",
    "adservice.google.com",
    "pagead2.googlesyndication.com",
    "static.doubleclick.net",
    "moatads.com",
    "scorecardresearch.com",
  ],
  blockPaths: [
    "/pagead/",
    "/ptracking",
    "/api/stats/ads",
    "/get_midroll_",
    "/youtubei/v1/log_event",
  ],
  allowHosts: ["googlevideo.com", "ytimg.com", "ggpht.com", "gstatic.com"],
  skipSelectors: [
    ".ytp-ad-skip-button",
    ".ytp-ad-skip-button-modern",
    ".ytp-skip-ad-button",
    ".ytp-ad-overlay-close-button",
    "button.ytp-ad-skip-button-container",
    "tp-yt-paper-button#dismiss-button",
    ".ytp-ad-survey-answer-selector",
  ],
};

export const dynamic = "force-static";

export function GET() {
  return NextResponse.json(RULES, {
    headers: {
      "Cache-Control": "public, s-maxage=3600, stale-while-revalidate=86400",
    },
  });
}
