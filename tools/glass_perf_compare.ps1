# Liquid-glass smoothness comparison: our app vs a reference app on the same device.
#
# Usage:
#   powershell -File tools\glass_perf_compare.ps1 -Serial 192.168.1.4:37789 -Pkg yos.music.player.oss
#   powershell -File tools\glass_perf_compare.ps1 -FindLabel "香草"          # locate a package by label
#   powershell -File tools\glass_perf_compare.ps1 -Serial <s> -Pkg <refpkg> -ShellTap 0
#
# What it produces, per package:
#   A) technique fingerprint  - which glass API the APK actually calls (RenderEffect / RuntimeShader /
#      BlurMaskFilter / blurBehind / a known library). This is what tells us *why* theirs is smooth,
#      instead of guessing from how it looks.
#   B) frame timing          - `dumpsys gfxinfo framestats` + `SurfaceFlinger --latency` over a
#      scripted expand/collapse. SurfaceFlinger present times are the ground truth for perceived
#      jank and work on non-debuggable third-party apps too.
#
# Safety: every input event is preceded by a mCurrentFocus check (it aborts, never taps blindly,
# if the target is not in the foreground). It only sends input to the package under test and never
# presses BACK. ASCII-only: PowerShell 5.1 reads UTF-8-without-BOM as ANSI and mangles literals.

param(
    [string]$Serial = "",
    # Locate a package by its launcher label instead of knowing the id up front.
    [string]$FindLabel = "",
    [string]$Pkg = "",
    # Repeat count for the gesture pair; the first rep warms JIT/GPU caches and is discarded.
    [int]$Reps = 3,
    # Coordinates used to trigger our own mini player -> player transition. 0 = skip gestures and
    # only sample whatever the user drives by hand (use this for the reference app the first run).
    [int]$ShellTap = 1,
    [int]$TapX = 500,
    [int]$TapY = 2360,
    [int]$SettleMs = 2500,
    [string]$OutDir = "diagnostics\glass_compare",
    # Resolve through PATH so every tool on this machine drives exactly one adb build. Two different
    # builds take turns killing each other's server on version mismatch, which silently drops the
    # wireless transport and makes later calls return nothing.
    # (The old C:\Windows\System32 / SysWOW64 copies were broken and have been removed; the guard
    # below stays so they cannot come back unnoticed.)
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
if (-not $Adb) {
    $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $Adb) { throw "cannot resolve adb on PATH; pass -Adb <path>\adb.exe" }
}
if (-not (Test-Path $Adb)) { throw "adb not found at $Adb" }
if ($Adb -match 'Windows\\System(32|WOW64)') { throw "refusing the system-dir adb; use the platform-tools copy on PATH" }
Write-Host "adb: $Adb"

function Sh([string]$cmd) {
    if ($Serial) { return & $Adb -s $Serial shell $cmd }
    return & $Adb shell $cmd
}
function ShOut([string]$cmd) {
    # Normalize CRLF noise coming out of adb shell before callers regex it.
    return ((Sh $cmd) | Out-String) -replace "`r", ""
}

function Resolve-Device {
    $lines = (& $Adb devices) | Out-String -split "`n" | Select-Object -Skip 1 |
        Where-Object { $_ -match '\t(device|emulator)' }
    $ids = @($lines | ForEach-Object { ($_ -split '\t')[0] })
    if ($Serial) { return }
    if ($ids.Count -eq 0) { throw "no device attached; connect the phone and re-run ($Adb devices must list it)" }
    if ($ids.Count -gt 1) { throw "several devices attached ($($ids -join ', ')); pass -Serial explicitly" }
    $Script:Serial = $ids[0]
}

function Get-Foreground {
    $t = ShOut "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus"
    if ($t -match 'mCurrentFocus=Window\S*\s+([^/\s]+)/') { return $Matches[1] }
    if ($t -match 'mCurrentFocus=.*?([A-Za-z0-9._]+)/') { return $Matches[1] }
    return ""
}

function Assert-Foreground([string]$pkg) {
    # An incoming call / game overlay / system dialog can take the foreground between two adb calls,
    # and the state observed one command ago is not trustworthy.
    $fg = Get-Foreground
    if ($fg -ne $pkg) { throw "foreground is '$fg', not '$pkg' - refusing to send input" }
}

function Find-PkgByLabel([string]$label) {
    # pm list packages gives ids only; the human-readable label has to come from --show-app-labels.
    $hits = @()
    $t = ShOut "cmd package list packages --show-app-labels 2>/dev/null"
    if (-not $t) { throw "this device's 'pm' cannot list app labels; pass -Pkg <package.id> instead" }
    foreach ($line in ($t -split "`n")) {
        if ($line -notmatch "^package:") { continue }
        $body = $line -replace '^package:', ''
        $sp = $body.LastIndexOf(' ')
        if ($sp -lt 1) { continue }
        $id = $body.Substring(0, $sp).Trim()
        $name = $body.Substring($sp + 1).Trim()
        if ($name -like "*$label*" -or $id -like "*$label*") { $hits += [pscustomobject]@{ Pkg = $id; Label = $name } }
    }
    return $hits
}

function Get-ApkPath([string]$pkg) {
    $t = ShOut "pm path $pkg"
    $m = [regex]::Matches($t, 'package:(/\S+base\.apk)')
    if ($m.Count -eq 0) { return "" }
    return $m[0].Groups[1].Value
}

# (A) technique fingerprint ------------------------------------------------------------------------
function Get-Fingerprint([string]$pkg, [string]$apkPath, [string]$outDir) {
    $local = Join-Path $outDir "$pkg.apk"
    if (-not (Test-Path $local)) {
        $sz = ShOut "stat -c %s '$apkPath' 2>/dev/null"
        $mb = 0
        if ($sz -match '(\d+)') { $mb = [math]::Round([int]$Matches[1] / 1MB, 1) }
        Write-Host "  pulling apk ($mb MB)..."
        if ($Serial) { & $Adb -s $Serial pull $apkPath $local | Out-Null } else { & $Adb pull $apkPath $local | Out-Null }
    }
    $bytes = [System.IO.File]::ReadAllBytes($local)
    $text = [System.Text.Encoding]::GetEncoding("ISO-8859-1").GetString($bytes)

    $needles = [ordered]@{
        # Real-time GPU blur
        "RenderEffect(recreateBlurEffect)"      = "android/graphics/RenderEffect"
        "RuntimeShader(AGSL)"                   = "android/graphics/RuntimeShader"
        "RenderNode#setRenderEffect"            = "setRenderEffect"
        # CPU-side / mask-filter blur (the slow path)
        "BlurMaskFilter(SW path mask)"          = "android/graphics/BlurMaskFilter"
        "ScriptIntrinsicBlur(RenderScript)"     = "ScriptIntrinsicBlur"
        # Window level blur (system compositor does the work, near free for the app)
        "BlurBehindRadius"                       = "setBlurBehindRadius"
        "FLAG_BLUR_BEHIND"                       = "FLAG_BLUR_BEHIND"
        # Downsampled bitmaps: blur once off the animation path
        "RenderScript/Toolkit downsample"        = "kitkat"
        # Whose glass implementation
        "kyant backdrop lib"                     = "com/kyant/backdrop"
        "miuix (HyperOS style)"                  = "miuix"
        "flutter (Skia/Impeller self-drawn)"     = "flutter/libapp"
        "unclear: androidx compose"              = "androidx/compose/ui/graphics/layer/GraphicsLayer"
    }
    $report = New-Object System.Collections.ArrayList
    foreach ($k in $needles.Keys) {
        $n = $needles[$k]
        # A DEX stores type descriptors as com/kyant/backdrop/Foo; a plain substring test is enough
        # and avoids depending on a dex parser being installed.
        $found = $text.IndexOf($n, [System.StringComparison]::Ordinal) -ge 0
        [void]$report.Add([pscustomobject]@{ Signal = $k; Needle = $n; Present = $found })
    }

    # Manifest facts that gate which of those APIs can even be used.
    $target = (ShOut "dumpsys package $pkg | grep -m1 targetSdk")
    $gl = [regex]::Match($target, 'targetSdk(?:Version)?=(\d+)').Groups[1].Value
    Write-Host "  targetSdk=$gl  (RenderEffect needs 31+, RuntimeShader uniform setters need 33+)"
    Write-Host "  hwui: $(ShOut "dumpsys package $pkg | grep -m1 hardware-accel")"
    return $report
}

# (B) frame timing -------------------------------------------------------------------------------
function Measure-Frames([string]$pkg, [string]$outDir, [int]$rep, [bool]$drive) {
    $tag = "$pkg-r$rep"
    if ($drive) {
        Assert-Foreground $pkg
        Sh "dumpsys gfxinfo $pkg reset" | Out-Null
        # keyevent 127 = MEDIA_PLAY_PAUSE keeps playback state identical between reps; the actual
        # transition is the tap. Collapse afterwards so the next rep starts from the same state.
        if ($ShellTap -ne 0) {
            Assert-Foreground $pkg
            Sh "input tap $TapX $TapY" | Out-Null
        }
        Start-Sleep -Milliseconds $SettleMs
        if ($ShellTap -ne 0) {
            Assert-Foreground $pkg
            Sh "input keyevent 4" | Out-Null   # only ever sent to a foreground we just verified
        }
    }
    $gf = Join-Path $outDir "gfxinfo_$tag.txt"
    ShOut "dumpsys gfxinfo $pkg framestats" | Set-Content $gf -Encoding ascii
    $sf = Join-Path $outDir "sflat_$tag.txt"
    $layer = ShOut "dumpsys SurfaceFlinger --list | grep -m1 -i $pkg"
    if ($layer) {
        ShOut "dumpsys SurfaceFlinger --latency $($layer.Trim())" | Set-Content $sf -Encoding ascii
    }
    return $gf
}

function Summarize-GfxInfo([string]$file) {
    $t = Get-Content $file -Raw
    $g = { param($rx) $m = [regex]::Match($t, $rx); if ($m.Success) { $m.Groups[1].Value } else { "n/a" } }
    return [pscustomobject]@{
        File     = Split-Path $file -Leaf
        Frames   = & $g 'Total frames rendered:\s*(\d+)'
        Janky    = & $g 'Janky frames:\s*\d+\s*\(([\d.]+%)\)'
        P50      = & $g '\n50th percentile:\s*(\d+)ms'
        P90      = & $g '\n90th percentile:\s*(\d+)ms'
        P95      = & $g '\n95th percentile:\s*(\d+)ms'
        SlowUI   = & $g 'Number Slow UI thread:\s*(\d+)'
        SlowDraw = & $g 'Number Slow issue draw commands:\s*(\d+)'
        UpBitmap = & $g 'Number Slow bitmap uploads:\s*(\d+)'
        MissVsy  = & $g 'Number Missed Vsync:\s*(\d+)'
    }
}

Resolve-Device
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Write-Host "device: $Serial"

if ($FindLabel) {
    Write-Host "looking for installed packages matching '$FindLabel':"
    Find-PkgByLabel $FindLabel | Format-Table -AutoSize
    return
}
if (-not $Pkg) { throw "pass -Pkg <package.id> (or -FindLabel to discover it)" }

$apk = Get-ApkPath $Pkg
Write-Host "=== $Pkg ==="
Write-Host "  apk: $(if ($apk) { $apk } else { 'NOT FOUND - is the package installed?' })"

if ($apk) {
    Write-Host "-- technique fingerprint --"
    Get-Fingerprint $Pkg $apk $OutDir | Format-Table -AutoSize Signal,Present
}

Write-Host "-- frame timing (rep 1 is a warm-up and should be read separately) --"
$rows = @()
for ($r = 1; $r -le $Reps; $r++) {
    $f = Measure-Frames $Pkg $OutDir $r ($ShellTap -ne 0)
    $rows += Summarize-GfxInfo $f
    Start-Sleep -Milliseconds 800
}
$rows | Format-Table -AutoSize
Write-Host "raw dumps in $OutDir"
Write-Host "NOTE: for a third-party release build the jank % is per-window HWUI stats; compare it"
Write-Host "      against ours measured the same way on the SAME device, SAME refresh rate, and with"
Write-Host "      battery saver off - a device that has dropped to 60/72Hz makes both look worse."
