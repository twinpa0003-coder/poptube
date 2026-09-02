import { NextResponse } from "next/server";
import { APP_VERSION_CODE, APP_VERSION_NAME, LATEST_APK_URL } from "../../config";

export const dynamic = "force-static";

export function GET() {
  return NextResponse.json(
    {
      versionCode: APP_VERSION_CODE,
      versionName: APP_VERSION_NAME,
      apkUrl: LATEST_APK_URL,
    },
    { headers: { "Cache-Control": "public, s-maxage=600" } }
  );
}
