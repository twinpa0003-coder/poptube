# 사내 프록시가 git push 를 막을 때 쓰는 업로더 실행 스크립트.
#
#   PS> .\tools\push.ps1
#
# GitHub 토큰은 Windows 자격 증명 저장소에서 읽어 curl 설정파일로만 전달한다.
# 화면에 출력되지 않고, 끝나면 설정파일을 지운다.
#
# 자세한 배경은 tools/push-via-api.js 주석 참고.
#
# 주의: 이 파일은 UTF-8 BOM 으로 저장해야 한다. Windows PowerShell 5.1 은
#       BOM 이 없으면 .ps1 을 ANSI 로 읽어서 한글이 깨진다.

$ErrorActionPreference = "Stop"

$root = git rev-parse --show-toplevel
if (-not $root) { throw "git 저장소 안에서 실행하세요." }

# 커밋되지 않은 변경이 있으면 알려준다. 이 스크립트는 HEAD 커밋만 올린다.
$dirty = git -C $root status --porcelain
if ($dirty) {
    Write-Host "커밋되지 않은 변경이 있습니다. 이 스크립트는 HEAD 커밋만 올립니다:" -ForegroundColor Yellow
    $dirty | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
    Write-Host ""
}

# git credential fill 에 stdin 을 넘기는 게 까다롭다. PowerShell 파이프나 .NET
# StandardInput 을 쓰면 UTF-8 BOM 이 앞에 붙어서 git 이 첫 줄을 인식하지 못한다.
#   -> "fatal: refusing to work with credential missing protocol field"
# BOM 없는 임시 파일을 만들고 cmd 의 입력 리다이렉션으로 넘기면 확실하다.
function Get-GitCredential {
    $inFile = Join-Path ([System.IO.Path]::GetTempPath()) ("gitcred-" + [guid]::NewGuid().ToString("N") + ".txt")
    try {
        $bytes = [System.Text.Encoding]::ASCII.GetBytes("protocol=https`nhost=github.com`n`n")
        [System.IO.File]::WriteAllBytes($inFile, $bytes)

        $out = & cmd /c "git credential fill < `"$inFile`""
        if ($LASTEXITCODE -ne 0) { throw "git credential fill 실패 (exit $LASTEXITCODE)" }
        return $out
    }
    finally {
        if (Test-Path $inFile) { Remove-Item $inFile -Force }
    }
}

$token = (Get-GitCredential | Where-Object { $_ -like "password=*" }) -replace "^password=", ""
$token = $token.Trim()
if (-not $token) { throw "GitHub 자격증명을 읽지 못했습니다. 먼저 git 으로 한 번 인증하세요." }

$cfg = Join-Path ([System.IO.Path]::GetTempPath()) ("ghapi-" + [guid]::NewGuid().ToString("N") + ".conf")
try {
    # curl 설정파일에는 BOM 이 있으면 안 된다. Set-Content -Encoding utf8 은 PS 5.1 에서
    # BOM 을 붙이므로("config file option '﻿header' is unknown") 직접 쓴다.
    $lines = @(
        "header = `"Authorization: Bearer $token`""
        'header = "Accept: application/vnd.github+json"'
        'header = "X-GitHub-Api-Version: 2022-11-28"'
    ) -join "`n"
    [System.IO.File]::WriteAllText($cfg, $lines + "`n", (New-Object System.Text.UTF8Encoding($false)))

    Remove-Variable token

    $branch = if ($args.Count -gt 0) { $args[0] } else { "main" }
    node (Join-Path $root "tools/push-via-api.js") $cfg $branch
    exit $LASTEXITCODE
}
finally {
    if (Test-Path $cfg) { Remove-Item $cfg -Force }
}
