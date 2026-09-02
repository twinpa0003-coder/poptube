import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "PopTube — 유튜브 떠 있는 창 + 화면 꺼짐 재생",
  description:
    "유튜브를 데스크톱 모드 WebView로 감싼 안드로이드 앱. PiP 팝업 재생, 화면을 꺼도 이어지는 오디오, 기본 광고 차단.",
};

export const viewport: Viewport = {
  themeColor: "#0f0f0f",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
