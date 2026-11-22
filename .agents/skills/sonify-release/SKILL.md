---
name: sonify-release
description: >-
  End-to-end release workflow for Sonify: inspect uncommitted workspace changes,
  gather full changelog since the previous release (including preview versions),
  bump APP_VERSION_CODE/APP_VERSION_NAME in gradle.properties, build and sign
  the release APK, install via wireless ADB to target devices, commit/tag/push
  to GitHub, and create the GitHub Release with the APK asset uploaded.
---

# Sonify Release Workflow

Use this skill whenever the user asks to release or upgrade to a new version of Sonify (e.g. "发布新版本 X.Y.Z", "升级版本并打包安装/发版到 GitHub").

## 1. Inspect Workspace & Gather Full History

1. Check uncommitted changes:
   ```powershell
   git status --short
   ```
   Review modified source files so they are included in the release commit and changelog. Ignore temporary root debug artifacts (e.g. `_*.png`, `_*.txt`, `_*.xml`, `*.pftrace`) unless explicitly requested.
2. Check all commits and string additions since the previous formal release tag (including intermediate preview releases such as `v0.2.5-v2`):
   ```powershell
   git tag --sort=-v:refname | Select-Object -First 5
   git log <prev_tag>..HEAD --oneline
   git diff <prev_tag>..HEAD -- app/src/main/res/values-zh-rCN/strings.xml | Select-String "^\+"
   ```
   For large rollup commits (e.g. `chore: 提交工作区近期功能完善与修复...`), inspect `git show --stat <sha>` and key setting/feature files so no user-facing feature (such as custom accent colors, Crossfade modes, decoder policies, etc.) is omitted.

## 2. Bump Version (`gradle.properties`)

Edit `gradle.properties` (single source of truth for `app/build.gradle` and the in-app About page):
- Increment `APP_VERSION_CODE` strictly by `+1`.
- Set `APP_VERSION_NAME=<version>` (e.g. `0.2.6`).

## 3. Write Changelog (`docs/CHANGELOG_v<version>.md`)

Create `docs/CHANGELOG_v<version>.md` covering all changes from `<prev_tag>` through the current workspace changes, organized into:
- **新增功能** (grouped by area: 个性化与主题外观 / 播放与音频能力 / 音乐画廊、播放页与艺人页 / 云更新与通道等)
- **性能与体验优化**
- **功能修复与测试**

## 4. Build & Sign Release APK

1. Build the release APK using JDK 17:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.11"
   $env:Path = "$env:JAVA_HOME\bin;$env:Path"
   .\gradlew.bat :app:assembleRelease --console=plain
   ```
2. Sign `app\build\outputs\apk\release\app-release-unsigned.apk` with `debug.keystore` (preserving overwrite-install signature continuity) into `app\build\outputs\apk\release\Sonify-v<version>.apk`:
   ```powershell
   $apksigner = (Get-ChildItem "D:\Android\Sdk\build-tools" -Recurse -Filter apksigner.bat | Sort-Object FullName -Descending | Select-Object -First 1).FullName
   $ks = Join-Path $env:USERPROFILE ".android\debug.keystore"
   $src = "app\build\outputs\apk\release\app-release-unsigned.apk"
   $out = "app\build\outputs\apk\release\Sonify-v<version>.apk"
   & $apksigner sign --ks $ks --ks-pass pass:android --out $out $src
   & $apksigner verify --print-certs $out | Select-Object -First 3
   ```

## 5. Wireless ADB Discovery & Selective Device Install

1. Follow the workspace ADB rule in `AGENTS.md`:
   ```powershell
   adb start-server; Start-Sleep -Seconds 3; adb mdns services
   ```
2. Connect to discovered `_adb-tls-connect._tcp` endpoints and inspect `adb devices -l` to identify each device (e.g. phone `25053RT47C` vs tablet `OPD2405`).
3. **Respect user exclusions** (e.g. if the user asks not to install on the tablet, install only to the phone).
4. Install and verify in a single command:
   ```powershell
   adb -s <ip:port> install -r -d app\build\outputs\apk\release\Sonify-v<version>.apk
   adb -s <ip:port> shell "dumpsys package com.sonify.music | grep -E 'lastUpdateTime|versionName|versionCode' | head -3"
   ```

## 6. Commit, Tag & Push to GitHub

Stage only the intentional modified files, `gradle.properties`, and `docs/CHANGELOG_v<version>.md` (never stage root scratch files), then commit, tag, and push:
```powershell
git commit -m "chore(release): bump version to <version> (<versionCode>)"
git tag -a v<version> -m "Release v<version>"
git push origin master
git push origin v<version>
```

## 7. Create GitHub Release & Upload APK

`gh` CLI may not be installed in PATH; use PowerShell `Invoke-RestMethod` with TLS 1.2 and the token from `git credential fill` (never print the token):
```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$credLines = ("protocol=https`nhost=github.com`n" | git credential fill)
$token = ($credLines | Where-Object { $_ -match '^password=' }) -replace '^password=', ''
$headers = @{
    Authorization = "Bearer $token"
    Accept = "application/vnd.github+json"
    "User-Agent" = "Sonify-Release-Script"
    "X-GitHub-Api-Version" = "2022-11-28"
}

# Use ReadAllText (NOT Get-Content, which attaches PS NoteProperties that break ConvertTo-Json)
$bodyText = [System.IO.File]::ReadAllText((Resolve-Path "docs\CHANGELOG_v<version>.md").Path, [System.Text.Encoding]::UTF8)
$payload = @{
    tag_name = "v<version>"
    target_commitish = "master"
    name = "Sonify v<version>"
    body = $bodyText
    draft = $false
    prerelease = $false
} | ConvertTo-Json -Depth 4
$payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
$release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/XiaoZhi0729/Sonify/releases" -Headers $headers -ContentType "application/json; charset=utf-8" -Body $payloadBytes

# Read APK with FileShare.ReadWrite to avoid transient file-lock errors
$apkPath = (Resolve-Path "app\build\outputs\apk\release\Sonify-v<version>.apk").Path
$fs = [System.IO.File]::Open($apkPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
$bytes = New-Object byte[] $fs.Length
$null = $fs.Read($bytes, 0, $bytes.Length)
$fs.Close()
$uploadUrl = "https://uploads.github.com/repos/XiaoZhi0729/Sonify/releases/$($release.id)/assets?name=Sonify-v<version>.apk"
$asset = Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers -ContentType "application/vnd.android.package-archive" -Body $bytes
```

## 8. Final Report

Report to the user:
- Local signed APK path (`app\build\outputs\apk\release\Sonify-v<version>.apk`)
- Installed device(s) and verified `versionCode` / `versionName`
- GitHub commit/tag, Release URL, and APK download URL
- Link to `docs/CHANGELOG_v<version>.md` and a clear summary of the changelog
