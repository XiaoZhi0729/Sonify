# diag2.ps1 - time-windowed history for screen-off playback interruption
param(
    [string]$Pkg = 'yos.music.player.oss',
    [string]$Date = (Get-Date).ToString('yyyy-MM-dd'),
    [string]$FromEpoch = '',
    [string]$ToEpoch = '',
    [string]$OutDir = 'd:\adxm\FlamingoSank\FlamingoSank\_diag_interrupt'
)
$ErrorActionPreference = 'Continue'
$Adb = (Get-Command adb).Source
if ($Adb -match 'Windows\\System(32|WOW64)') { throw "bad adb copy: $Adb" }
$devLines = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' })
if ($devLines.Count -ne 1) { throw "expected 1 device, got $($devLines.Count)" }
$Serial = ($devLines[0] -split '\t')[0]
function SH([string]$cmd) { return (& $Adb -s $Serial shell "$cmd") -join "`n" }
function Save([string]$name, [string]$text) {
    $p = Join-Path $OutDir $name
    [IO.File]::WriteAllText($p, $text)
    Write-Host ("{0} -> {1} bytes" -f $name, (Get-Item $p).Length)
}
if (-not $FromEpoch) {
    # resolve device epoch for "$Date 01:20" and "$Date 02:20" via date -d
    $f = (SH "date -d '$Date 01:20:00' +%s").Trim()
    $t = (SH "date -d '$Date 02:20:00' +%s").Trim()
    if ($f -notmatch '^\d+$') { throw "date -d not supported, got '$f'" }
    $FromEpoch = $f; $ToEpoch = $t
}
Write-Host "window epoch $FromEpoch..$ToEpoch"

# whole buffer with epoch timestamps; slicing/filtering is done locally in PowerShell
Save "win_all_$Date.txt" (SH 'logcat -b all -v epoch -d')

# events log tagged tables around the window
Save "evt_am_$Date.txt" (SH "logcat -b events -d -v epoch | grep -E 'am_(kill|proc_died|freeze|oom_adj|trim_memory|create_service|destroy_service|foreground|user_interaction|screen|native_crash|anr)'")

# device-side persisted logs
Save "slog_list.txt" (SH "ls -l /data/system/uiderrors.txt /data/system/powerkeeper/ /data/developer/ 2>&1")
Save "bugreport_hint.txt" (SH "ls -l /data/user_de/0/com.miui.analytics 2>&1; settings get global logcat_ext_buf 2>&1; getprop ro.build.version.incremental; getprop ro.miui.ui.version.name; getprop ro.build.version.release")
Save "deviceidle_mstate.txt" (SH "dumpsys deviceidle | grep -i -E 'State|MState|mScreenOn|nextIdleTime|step|Last|locating|tempsavelist|recent|user'")
Save "exempt_$Pkg.txt" (SH "dumpsys deviceidle whitelist | grep -i -E 'Package|user'")
Save "power_screen_state.txt" (SH "dumpsys power | grep -i -E 'mScreenOn|LastUserActivityTime|Dim|Doze|suspend|Wakefulness|mDisplayPowerRequest'")
Write-Host DONE
