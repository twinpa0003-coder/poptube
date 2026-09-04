// GitHub 사용자명/리포 이름만 바꾸면 다운로드 링크가 전부 맞춰진다.
export const GITHUB_OWNER = process.env.NEXT_PUBLIC_GITHUB_OWNER ?? "twinpa0003-coder";
export const GITHUB_REPO = process.env.NEXT_PUBLIC_GITHUB_REPO ?? "poptube";

export const REPO_URL = `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}`;
export const LATEST_RELEASE_URL = `${REPO_URL}/releases/latest`;
export const LATEST_APK_URL = `${REPO_URL}/releases/latest/download/poptube.apk`;
export const ACTIONS_URL = `${REPO_URL}/actions`;

export const APP_VERSION_NAME = "1.1.0";
export const APP_VERSION_CODE = 6;
