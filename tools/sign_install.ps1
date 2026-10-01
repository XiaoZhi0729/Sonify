# Build-artifact installer: re-sign the newest release APK with debug.keystore and install -r.
#
# Replaces the pile of one-off `_pblur_sign_install.sh` / `_plfix_sign_install.sh` clones at the
# repo root. Same contract as those: release build re-signed with the *debug* key so it can
# overwrite-install onto a device that already has a debug-signed build (no uninstall, no data loss).
#
#   powershell -File tools\sign_install.ps1                      # newest release apk, first device
#   powershell -File tools\sign_install.ps1 -Serial 192.168.1.7:43641 -Tag glass
#   powershell -File tools\sign_install.ps1 -SkipBuild
#
# Exit code 0 = installed. ASCII only (PowerShell 5.1 reads UTF-8-without-BOM as ANSI).
param(
    [string]$Serial = "",
    [string]$Pkg = "yos.music.player.oss",
    # Names the copied artifact `_glass_release_debugsigned.ApK`, matching the existing convention.
    [string]$Tag = "manual",
    [switch]$SkipBuild,
    # SDK root is still needed for apksigner, but NOT for adb: two different adb builds take turns
    # killing each other's server (version mismatch), which silently drops the wireless transport.
    # adb therefore resolves through PATH, the same single source every other tool uses.
    [string]$SdkRoot = "D:\Android\Sdk",
    [string]$Adb = "",
    [string]$Jdk = "C:\Program Files\Java\jdk-17.0.11"
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$env:Path"

if (-not $SkipBuild) {
    Write-Host "building release..."
    & .\gradlew.bat :app:assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed" }
}

$src = Get-ChildItem "app\build\outputs\apk\release\*.ApK" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $src) { throw "no release apk under app\build\outputs\apk\release" }
$out = "_${Tag}_release_debugsigned.ApK"
Write-Host "src: $($src.Name)  ($([math]::Round($src.Length/1MB,1)) MB, $($src.LastWriteTime.ToString('MM-dd HH:mm')))"

$apksigner = Get-ChildItem "$SdkRoot\build-tools" -Recurse -Filter apksigner.bat |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $apksigner) { throw "apksigner not found under $SdkRoot\build-tools" }
$ks = Join-Path $env:USERPROFILE ".android\debug.keystore"
if (-not (Test-Path $ks)) { throw "debug.keystore missing at $ks" }

# Sign from the build output into a separate file - apksigner is not safe reading and writing the
# same path, and leaving the build output untouched keeps a clean rollback target.
$signer = $apksigner.FullName
& $signer sign --ks $ks --ks-pass pass:android --out $out $src.FullName
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }
& $signer verify --print-certs $out | Select-Object -First 3

if (-not $Adb) {
    $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $Adb) { throw "cannot resolve adb on PATH; pass -Adb <path>\adb.exe" }
}
if ($Adb -match 'Windows\\System(32|WOW64)') { throw "refusing the system-dir adb; use the platform-tools copy on PATH" }
Write-Host "adb: $Adb"
if (-not $Serial) {
    $ids = @((& $adb devices) | Out-String -split "`n" | Select-Object -Skip 1 |
        Where-Object { $_ -match '\t(device|emulator)' } | ForEach-Object { ($_ -split '\t')[0] })
    if ($ids.Count -eq 0) { throw "no device attached" }
    if ($ids.Count -gt 1) { throw "several devices ($($ids -join ', ')); pass -Serial" }
    $Serial = $ids[0]
}
Write-Host "installing to $Serial"
# -d (allow version downgrade) is required here: this artifact is installed over a build of the same
# versionCode but a newer timestamp, and plain `install -r` fails on that without saying why.
# 2>&1 keeps adb's own failure line in the log - the throw below used to print only "install failed".
$installLog = ((& $Adb -s $Serial install -r -d $out 2>&1) -join "`n") -replace "`r", ""
Write-Host $installLog.Trim()
if ($LASTEXITCODE -ne 0 -or $installLog -notmatch '\bSuccess\b') {
    throw "install failed (exit=$LASTEXITCODE): $($installLog.Trim())"
}
& $adb -s $Serial shell "dumpsys package $Pkg | grep -E 'lastUpdateTime|versionName' | head -2"
Write-Host "INSTALL_OK $Tag -> $out"
