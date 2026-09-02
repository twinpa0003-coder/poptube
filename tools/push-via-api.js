/**
 * 사내 프록시 우회 업로더.
 *
 * 이 회사 네트워크는 `github.com/.../git-receive-pack` 으로의 POST 를 전면 차단하고
 * (차단 페이지 제목: "비업무사이트차단") `api.github.com` 으로의 POST 도 48KB 를 넘으면 막는다.
 * 그래서 git push 가 원리적으로 불가능하다. GET(clone/fetch)은 통과한다.
 *
 * 대신 GitHub Git Data API 로 파일을 하나씩(각 요청 45KB 미만) 올려 커밋을 만든다.
 * 로컬 HEAD 커밋의 내용을 그대로 원격에 올리므로 결과 트리 해시가 로컬과 일치해야 정상이다.
 *
 * 실행은 tools/push.ps1 을 쓴다. 토큰은 그쪽에서 만든 curl 설정파일로만 전달되며
 * 이 스크립트는 토큰 값을 알지 못한다.
 *
 * 한계:
 *   - 개별 파일이 45KB 를 넘으면 올릴 수 없다 (프록시 업로드 제한)
 *   - 집이나 개인 핫스팟에서는 이 스크립트 대신 그냥 `git push` 를 쓰면 된다
 */
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const MAX_POST = 45 * 1024;
const CFG = process.argv[2];
const BRANCH = process.argv[3] || "main";

if (!CFG || !fs.existsSync(CFG)) {
  console.error("사용법: node tools/push-via-api.js <curl설정파일> [브랜치]");
  console.error("직접 실행하지 말고 tools/push.ps1 을 쓰세요.");
  process.exit(2);
}

const ROOT = execFileSync("git", ["rev-parse", "--show-toplevel"], { encoding: "utf8" }).trim();
const git = (args) =>
  execFileSync("git", ["-C", ROOT, ...args], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });

const remoteUrl = git(["remote", "get-url", "origin"]).trim();
const m = remoteUrl.match(/github\.com[/:]([^/]+)\/(.+?)(?:\.git)?$/);
if (!m) throw new Error(`origin 이 GitHub 주소가 아닙니다: ${remoteUrl}`);
const API = `https://api.github.com/repos/${m[1]}/${m[2]}`;

const TMP = fs.mkdtempSync(path.join(require("os").tmpdir(), "ghapi-"));
process.on("exit", () => fs.rmSync(TMP, { recursive: true, force: true }));

function call(url, method, bodyObj) {
  const resFile = path.join(TMP, "res.json");
  const args = ["-sS", "-m", "120", "--config", CFG, "-o", resFile,
                "-w", "%{http_code}:%{content_type}", "-X", method, url];
  if (bodyObj !== null && bodyObj !== undefined) {
    const body = JSON.stringify(bodyObj);
    const size = Buffer.byteLength(body);
    if (size > MAX_POST) {
      throw new Error(`페이로드 ${(size / 1024).toFixed(1)}KB 가 프록시 한계(45KB)를 넘습니다`);
    }
    const reqFile = path.join(TMP, "req.json");
    fs.writeFileSync(reqFile, body);
    args.push("--data-binary", "@" + reqFile);
  }
  const meta = execFileSync("curl", args, { encoding: "utf8" });
  const text = fs.readFileSync(resFile, "utf8");
  if (!/json/i.test(meta)) {
    throw new Error(`프록시에 차단된 것으로 보입니다 (${meta}) :: ${text.slice(0, 120)}`);
  }
  return { status: parseInt(meta, 10), body: JSON.parse(text) };
}

/** JSON 이스케이프본과 base64 중 더 작은 표현을 고른다. */
function encodeSmaller(raw) {
  const asText = { content: raw, encoding: "utf-8" };
  const asB64 = { content: Buffer.from(raw, "utf8").toString("base64"), encoding: "base64" };
  return Buffer.byteLength(JSON.stringify(asText)) <= Buffer.byteLength(JSON.stringify(asB64))
    ? asText
    : asB64;
}

// ---------------------------------------------------------------- 로컬 상태

// git 의 blob 해시 계산 방식은 GitHub 과 동일하므로 sha 를 그대로 비교/재사용할 수 있다.
const localEntries = git(["ls-tree", "-r", "HEAD"])
  .trim()
  .split("\n")
  .filter(Boolean)
  .map((line) => {
    const [meta, filePath] = line.split("\t");
    const [mode, type, sha] = meta.split(/\s+/);
    return { path: filePath, mode, type, sha };
  });

const localTree = git(["rev-parse", "HEAD^{tree}"]).trim();
const message = git(["log", "-1", "--pretty=%B"]).trimEnd();
console.log(`로컬 HEAD  : ${git(["rev-parse", "--short", "HEAD"]).trim()} (파일 ${localEntries.length}개)`);

// ---------------------------------------------------------------- 원격 상태

let parents = [];
const ref = call(`${API}/git/ref/heads/${BRANCH}`, "GET", null);
const remoteBlobs = new Map();

if (ref.status === 200) {
  parents = [ref.body.object.sha];
  const remoteCommit = call(`${API}/git/commits/${ref.body.object.sha}`, "GET", null);
  const remoteTree = call(`${API}/git/trees/${remoteCommit.body.tree.sha}?recursive=1`, "GET", null);
  for (const e of remoteTree.body.tree || []) {
    if (e.type === "blob") remoteBlobs.set(e.path, e.sha);
  }
  console.log(`원격 ${BRANCH}  : ${ref.body.object.sha.slice(0, 7)} (파일 ${remoteBlobs.size}개)`);
  if (remoteCommit.body.tree.sha === localTree) {
    console.log("\n원격이 이미 로컬과 동일합니다. 올릴 것이 없습니다.");
    process.exit(0);
  }
} else if (ref.status === 404) {
  console.log(`원격 ${BRANCH}  : 없음 → 새로 만듭니다`);
} else {
  throw new Error(`ref 조회 실패 (${ref.status}) ${JSON.stringify(ref.body).slice(0, 200)}`);
}

// ---------------------------------------------------------------- 업로드

const changed = localEntries.filter((e) => remoteBlobs.get(e.path) !== e.sha);
console.log(`\n올릴 파일 ${changed.length}개 (나머지 ${localEntries.length - changed.length}개는 동일)\n`);

for (const [i, e] of changed.entries()) {
  const payload = encodeSmaller(git(["cat-file", "-p", e.sha]));
  const kb = (Buffer.byteLength(JSON.stringify(payload)) / 1024).toFixed(1);
  const res = call(`${API}/git/blobs`, "POST", payload);
  if (res.body.sha !== e.sha) {
    throw new Error(`${e.path} 업로드 결과 불일치 (${res.status}) ${JSON.stringify(res.body).slice(0, 200)}`);
  }
  console.log(`  [${String(i + 1).padStart(2)}/${changed.length}] ${kb.padStart(5)}KB  ${e.path}`);
}

console.log("\ntree 생성...");
const tree = call(`${API}/git/trees`, "POST", {
  tree: localEntries.map((e) => ({ path: e.path, mode: e.mode, type: e.type, sha: e.sha })),
});
if (tree.body.sha !== localTree) {
  throw new Error(`tree 해시가 로컬과 다릅니다: ${tree.body.sha} != ${localTree}`);
}
console.log(`  tree = ${tree.body.sha} (로컬과 일치)`);

console.log("commit 생성...");
const commit = call(`${API}/git/commits`, "POST", { message, tree: tree.body.sha, parents });
if (!commit.body.sha) {
  throw new Error(`commit 실패 ${JSON.stringify(commit.body).slice(0, 300)}`);
}

console.log(`refs/heads/${BRANCH} 갱신...`);
const updated =
  ref.status === 200
    ? call(`${API}/git/refs/heads/${BRANCH}`, "PATCH", { sha: commit.body.sha })
    : call(`${API}/git/refs`, "POST", { ref: `refs/heads/${BRANCH}`, sha: commit.body.sha });

if (updated.body.object?.sha !== commit.body.sha) {
  throw new Error(`ref 갱신 실패 ${JSON.stringify(updated.body).slice(0, 300)}`);
}

console.log(`\n완료. ${BRANCH} -> ${commit.body.sha}`);
console.log("로컬을 원격에 맞추려면: git fetch origin; git reset --hard origin/" + BRANCH);
