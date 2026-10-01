# Expand/collapse frame sampling + interleaved ablation driver.
#
# Protocol matches the historical onyx_ablate.sh runs (gfxinfo reset -> tap -> settle -> framestats)
# so numbers stay comparable with the ab*.txt files.
#
#   # one config, 3 reps:
#   powershell -File tools\ref_player_frames.ps1 -Serial 192.168.1.7:43641 -Probe nohiddendraw -Tag nd
#
#   # interleaved matrix, 3 cycles each (THIS IS THE MODE TO USE FOR COMPARISONS):
#   powershell -File tools\ref_player_frames.ps1 -Serial 192.168.1.7:43641 -Cycles 3 -SettleMs 700 `
#     -Configs @("base=", "nohiddendraw=nohiddendraw", "navplain=navplain")
#
# Why interleaved: the device thermally throttles under continuous benchmarking - measured today,
# the *same* default configuration drifted 23.3ms -> 27ms over an hour. Running A x3 then B x3 buries
# a real 3ms effect under drift of the same size. Cycling A,B,C,A,B,C,A,B,C puts drift between the
# pairs instead of inside them.
#
# Why the HISTOGRAM and not "Janky frames %": HWUI judges jank against each frame's own scheduled
# deadline, so it reported ~1% jank while every frame took 23ms against an 8.33ms vsync. Percentiles
# plus "share of frames overrunning 1/2/3 vsync periods" is what separates 120fps from 40fps.
#
# ASCII only: PowerShell 5.1 reads UTF-8-without-BOM as ANSI and mangles literals.
param(
    [string]$Serial = "",
    [string]$Pkg = "yos.music.player.oss",
    [int]$TapX = 640,
    [int]$TapY = 2400,
    [int]$Reps = 3,
    [int]$SettleMs = 2500,
    # GlassProbe flag string (see GlassProbe.kt). $null = do not touch the setting at all.
    [string]$Probe = $null,
    [string]$ProbeSetting = "flamingo_probe",
    [string]$Tag = "run",
    # "label=flags" entries; empty flags means "delete the setting" = shipped defaults.
    [string[]]$Configs = @(),
    [int]$Cycles = 3,
    [string]$OutDir = "diagnostics\glass_compare",
    # Resolve adb the same way everything else on this machine does. Hardcoding a second SDK copy is
    # not harmless: two different adb client builds take turns killing and re-spawning the server, and
    # a command issued while that happens comes back with an *empty* result rather than an error.
    [string]$Adb = ""
)
$ErrorActionPreference = "Stop"
if (-not $Serial) { throw "pass -Serial" }
if (-not $Adb) {
    $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $Adb) { throw "cannot resolve adb on PATH; pass -Adb <path>\adb.exe" }
}
if (-not (Test-Path $Adb)) { throw "adb not found at $Adb" }
# The System32/SysWOW64 copies were shipped without libwinpthread-1.dll: every call dies with
# 0xc0000142 behind a modal dialog. Guard stays so an old PATH cannot silently come back.
if ($Adb -match 'Windows\\System(32|WOW64)') { throw "refusing the system-dir adb (missing libwinpthread-1.dll)" }
Write-Host "adb: $Adb"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Capture stdout with -join, never Out-String. Out-String runs the console formatter, and once that
# formatter state is disturbed (seen here as "out-lineoutput: FormatEntryData 无效或顺序不正确" while
# a report was piped into Select-Object) every later Out-String in the same runspace silently returns
# an empty string - which then looks exactly like "the device has no foreground window".
function A([string]$cmd) { return @(& $Adb -s $Serial shell $cmd 2>$null) }
function AF([string]$cmd) { return ((A $cmd) -join "`n") -replace "`r", "" }

function Focus {
    $t = AF "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus"
    if ($t -match 'mCurrentFocus=.*?([A-Za-z0-9._]+)/') { return $Matches[1] }
    # Returning "" here conflated three different failures: adb produced no output, the device had no
    # focused window (mCurrentFocus=Window{... (empty)}) and a focus line in an unexpected shape.
    # Hand back the raw text so the caller's error message says what really happened.
    $raw = ($t -replace '\s+', ' ').Trim()
    if ($raw) { return "unparsed:$raw" }
    return "no-output"
}

# Empty $flags deletes the setting so GlassProbe.read() falls back to its defaults.
function Set-Probe([string]$flags) {
    if ($flags) { A "settings put global $ProbeSetting '$flags'" | Out-Null }
    else { A "settings delete global $ProbeSetting" | Out-Null }
    # The probe is read once per process on purpose: toggling it live would leave already-created
    # offscreen layers behind and measure a migration instead of a cost. Cold restart is protocol.
    A "am force-stop $Pkg" | Out-Null
    Start-Sleep -Milliseconds 1500
    # monkey writes its report to stderr; with ErrorActionPreference=Stop a native command's stderr
    # becomes a terminating error, which silently aborted the rest of this function.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    A "monkey -p $Pkg -c android.intent.category.LAUNCHER 1" | Out-Null
    $ErrorActionPreference = $prev
    Start-Sleep -Seconds 5
    $f = Focus
    if ($f -ne $Pkg) { throw "after cold restart with probe='$flags' the app is not foreground (seen: $f)" }
}

function Ensure-Foreground {
    $seen = Focus
    if ($seen -ne $Pkg) {
        A "monkey -p $Pkg -c android.intent.category.LAUNCHER 1" | Out-Null
        Start-Sleep -Seconds 4
        $seen = Focus
    }
    # Report what was actually seen: a silent "-ne $Pkg" cannot tell a locked screen apart from a
    # dead adb server, and those need opposite responses.
    if ($seen -ne $Pkg) { throw "foreground is '$seen', not '$Pkg' - refusing to send input" }
}

function Playback-Playing {
    # Read the state off OUR session: taking the first PlaybackState line in the whole dump can
    # land on another player's session and report the opposite of reality.
    $t = AF "dumpsys media_session 2>/dev/null"
    foreach ($b in ($t -split "`n    package=")) {
        if ($b -notmatch [regex]::Escape($Pkg)) { continue }
        $m = [regex]::Match($b, 'PlaybackState \{state=(\d+)')
        if ($m.Success) { return ([int]$m.Groups[1].Value) -eq 3 }   # 3 = STATE_PLAYING
    }
    return $false
}

function Ensure-Paused {
    # The album-art Ken Burns loop keeps drawing every frame while playing, so a fixed-length window
    # collects a different number of frames depending on playback state (observed: 94 vs 479 frames
    # for the same gesture). Paused = the window samples the morph, not the background animation.
    if (Playback-Playing) {
        A "input keyevent 127" | Out-Null
        Start-Sleep -Milliseconds 700
    }
}

function Overrun([string]$file) {
    $h = (Get-Content $file | Select-String -Pattern '^HISTOGRAM:' | Select-Object -First 1)
    if (-not $h) { return @{ Tot = 0; O1 = 0; O2 = 0; O3 = 0 } }
    $tot = 0; $o1 = 0; $o2 = 0; $o3 = 0
    foreach ($m in [regex]::Matches($h.Line, '(\d+)ms=(\d+)')) {
        $b = [int]$m.Groups[1].Value; $c = [int]$m.Groups[2].Value
        if ($b -gt 3000) { continue }          # idle/gap buckets, not frames
        $tot += $c
        if ($b -gt 8) { $o1 += $c }
        if ($b -gt 16) { $o2 += $c }
        if ($b -gt 24) { $o3 += $c }
    }
    return @{ Tot = $tot; O1 = $o1; O2 = $o2; O3 = $o3 }
}

function Stats([string]$file, [string]$tag) {
    $head = (Get-Content $file -TotalCount 22) -join "`n"
    function Num($rx) {
        $m = [regex]::Match($head, $rx)
        if ($m.Success) { return [double]$m.Groups[1].Value }
        return [double]::NaN
    }
    $ov = Overrun $file
    $share = { param($n) if ($ov.Tot -gt 0) { 100.0 * $n / $ov.Tot } else { [double]::NaN } }
    return [pscustomobject]@{
        Tag    = $tag
        Frames = [int]$ov.Tot
        P50    = Num '\n50th percentile:\s*(\d+)ms'
        P90    = Num '\n90th percentile:\s*(\d+)ms'
        P95    = Num '\n95th percentile:\s*(\d+)ms'
        GpuP50 = Num '\n50th gpu percentile:\s*(\d+)ms'
        SlowUI = Num 'Number Slow UI thread:\s*(\d+)'
        Miss1  = [math]::Round((& $share $ov.O1), 1)
        Miss2  = [math]::Round((& $share $ov.O2), 1)
        Miss3  = [math]::Round((& $share $ov.O3), 1)
    }
}

# One gesture -> one dump -> one stats row. Assumes the probe is already applied and the app started.
function Measure-Once([string]$tag, [int]$rep) {
    Ensure-Foreground
    # Collapse first (swipe the sheet down) so every rep starts from the same geometry. Never BACK:
    # from the collapsed list BACK leaves the app and the next rep measures the launcher.
    A "input swipe 640 1200 640 2500 220" | Out-Null
    Start-Sleep -Milliseconds 1200
    Ensure-Paused
    A "dumpsys gfxinfo $Pkg reset" | Out-Null
    A "input tap $TapX $TapY" | Out-Null
    Start-Sleep -Milliseconds $SettleMs
    $f = Join-Path $OutDir "${tag}_r${rep}.txt"
    AF "dumpsys gfxinfo $Pkg framestats" | Set-Content $f -Encoding ascii
    # An empty dump means adb itself failed, not that the app is idle. Failing loudly here is the
    # whole point: a probe that exits 0 while writing nothing turns every downstream number into a
    # false negative, which is worse than no measurement.
    if ((Get-Item $f).Length -lt 500) {
        throw "empty gfxinfo dump ($f, $((Get-Item $f).Length) bytes) - adb or the target app is not responding"
    }
    return Stats $f $tag
}

function Report($rows, [string]$byTag) {
    $rows | Format-Table -AutoSize
    if ($rows.Count -lt 2) { return }
    $groups = if ($byTag) { $rows | Group-Object Tag } else { @(@{ Name = $Tag; Group = $rows }) }
    # A `foreach` *statement* is not an expression, so it cannot be piped into; wrap it in $().
    $(foreach ($g in $groups) {
        function AvgOf($prop) {
            $v = @($g.Group | ForEach-Object { $_.$prop } | Where-Object { $null -ne $_ -and -not [double]::IsNaN([double]$_) })
            if ($v.Count) { return [math]::Round(($v | Measure-Object -Average).Average, 1) }
            return 'n/a'
        }
        $lo = ($g.Group | Measure-Object P50 -Minimum).Minimum
        $hi = ($g.Group | Measure-Object P50 -Maximum).Maximum
        [pscustomobject]@{
            Group    = $g.Name
            n        = $g.Group.Count
            P50      = AvgOf 'P50'
            P50range = "$lo-$hi"
            P90      = AvgOf 'P90'
            P95      = AvgOf 'P95'
            GpuP50   = AvgOf 'GpuP50'
            Miss2    = AvgOf 'Miss2'
            Miss3    = AvgOf 'Miss3'
        }
    }) | Sort-Object P50 | Format-Table -AutoSize
}

if ($Configs.Count -gt 0) {
    $rows = @()
    foreach ($cycle in 1..$Cycles) {
        foreach ($entry in $Configs) {
            $label, $flags = $entry -split '=', 2
            if (-not $flags) { $flags = "" }
            Set-Probe $flags
            Write-Host "cycle $cycle : $label ('$flags')"
            $rows += Measure-Once "$label-c$cycle" 1
        }
    }
    # Strip the cycle suffix so the report groups by config, not by individual run.
    $rows | ForEach-Object { $_.Tag = $_.Tag -replace '-c\d+$', '' }
    Report $rows $true
} else {
    if ($null -ne $Probe) { Set-Probe $Probe }
    $rows = @()
    for ($r = 1; $r -le $Reps; $r++) { $rows += Measure-Once $Tag $r }
    Report $rows $false
    if ($null -ne $Probe) { A "settings delete global $ProbeSetting" | Out-Null }
}
Write-Host "raw dumps: $OutDir"
