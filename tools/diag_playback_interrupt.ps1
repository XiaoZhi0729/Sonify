# diag_playback_interrupt.ps1 - collect device-side evidence for screen-off playback interruption
# ASCII-only script body (PS5.1 decodes BOM-less UTF-8 as ANSI).
param(
    [string]$Since = '01:30',
    [string]$Until = '02:10',
    [string]$Date = '',          # 'YYYY-MM-DD'; empty = today
    [string]$OutDir = 'd:\adxm\FlamingoSank\FlamingoSank\_diag_interrupt'
)

$ErrorActionPreference = 'Continue'
$Pkg = 'yos.music.player'

$Adb = (Get-Command adb).Source
if ($Adb -match 'Windows\\System(32|WOW64)') { throw "bad adb copy: $Adb" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Write-Host "ADB=$Adb"

# --- device resolution (never guess when multiple devices) ---
$devLines = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' })
Write-Host "devices:`n$($devLines -join "`n")"
if ($devLines.Count -eq 0) {
    & $Adb connect 192.168.1.7:43641 | Out-Null
    $devLines = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' })
}
if ($devLines.Count -ne 1) { throw "expected exactly 1 device, got $($devLines.Count)" }
$Serial = ($devLines[0] -split '\t')[0]
Write-Host "serial=$Serial"

function SH([string]$cmd) { return (& $Adb -s $Serial shell "$cmd 2>/dev/null") -join "`n" }
function Save([string]$name, [string]$text) {
    $p = Join-Path $OutDir $name
    [IO.File]::WriteAllText($p, $text)
    $len = (Get-Item $p).Length
    Write-Host ("[{0}] {1} bytes" -f $(if ($len -lt 200) { 'WARN-SMALL' } else { 'ok' }), $name)
    return $p
}

# date prefix for logcat -T / -v
if (-not $Date) { $Date = (Get-Date).ToString('yyyy-MM-dd') }
Write-Host "date=$Date window=$Since..$Until"

Save 'deviceinfo.txt' ("adb=$Adb`nserial=$Serial`ndate_device=" + (SH 'date') + "`nuptime=" + (SH 'cat /proc/uptime') + "`nboot=" + (SH 'cat /proc/stat | grep btime'))

# ---------- A. battery / doze / standby policy state ----------
Save 'battery_exempt.txt' (SH "dumpsys deviceidle")
Save 'appstandby.txt' (SH "dumpsys usagestats | grep -i -A3 -B3 '$Pkg'")
Save 'powerkeeper.txt' (SH "dumpsys power | grep -i -E 'mWakefulness|Display Power|suspend|Screen Off|Doze'")
Save 'wakelocks.txt' (SH "dumpsys power | grep -i -E 'Wake Lock|wake_lock|$_ .*RUNTIME|PARTIAL'")
Save 'procstate.txt' (SH "dumpsys activity processes $Pkg | grep -i -E 'State adj|procState|oom|Frozen|hasActivities|FGS|curAdj'")
Save 'gservice.txt' (SH "dumpsys activity services $Pkg | grep -i -E 'ServiceRecord|isForeground|foreground|appComponentFactory|createdAt|frozen'")
Save 'jobscheduler.txt' (SH "dumpjs | grep -i -A6 -B2 '$Pkg'")

# ---------- B. installed apk + whether logs survive R8 ----------
Save 'packagelist.txt' (SH "pm list packages -3")
Save 'installedapp.txt' (SH "dumpsys package $Pkg | grep -i -E 'versionName|flags|primaryCpuAbi|installed |time spent|requestIgnoring'")

# ---------- C. audio focus / media session current state ----------
Save 'media_session.txt' (SH 'dumpsys media_session')
Save 'audiofocus.txt' (SH 'dumpsys audio')
Save 'audioserver.txt' (SH 'dumpsys media.audio_flinger | grep -i -E "^ *Client|Processes|standby|Activity"')

# ---------- D. history: what happened around the window ----------
# logcat -b all is the only surviving evidence: release build strips app logs.
Save 'logcat_window.txt' (SH "logcat -v threadtime -T '$Date` $Since`' -d | grep -i -E 'yos.music|PowerKeeper|freeze|frozen|doze|DeviceIdleController|AudioFocus|audio_focus|AudioTrack|MediaSessionService|am_kill|am_purge|am_meminfo|anr|ActivityManager|connectivity|wifi|netd|bsdiff|whitelist|oemservices|MiuiMultiWindow|sched_group' | head -c 4000000")
Save 'logcat_all_tail.txt' (SH 'logcat -b all -v threadtime -d | tail -n 4000')
Save 'logcat_doze.txt' (SH "logcat -b events,main,system -v threadtime -d | grep -i -E 'DeviceIdleController|doze|powerkeep|freeze|unfreeze|am_proc_died|am_kill|am_create|am_foreground|am_user_interaction|screen_toggled|wake_reason' | tail -n 3000")
Save 'logcat_audio.txt' (SH "logcat -b media,audio,main,system -v threadtime -d | grep -i -E 'AudioFocus|focus|AudioTrack|AudioFlinger|ExoPlayer|MediaCodec|audio_hw|AAudio|player' | tail -n 3000")

# ---------- E. dropbox / tombstone / anr records ----------
Save 'dropbox_list.txt' (SH "ls -l /data/system/dropbox")
Save 'dropbox_recent.txt' (SH "for f in /data/system/dropbox/*$Date*; do echo \"== \$f\"; head -c 3000 \$f; echo; done")

Write-Host 'DONE'
