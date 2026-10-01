# diag3.ps1 - slice the captured logcat by wall-clock window and grep with human timestamps
param(
    [string]$InFile = 'd:\adxm\FlamingoSank\FlamingoSank\_diag_interrupt\win_all_2026-09-27.txt',
    [string]$OutDir = 'd:\adxm\FlamingoSank\FlamingoSank\_diag_interrupt',
    [string]$From = '01:20',
    [string]$To = '02:10',
    [string]$Date = '2026-09-27'
)
$ErrorActionPreference = 'Continue'
# calibrate wall clock <-> epoch from the newest line whose timestamp we know:
# take min/max of the file and map with the device 'now' reported in deviceinfo.
$di = Get-Content (Join-Path $OutDir 'deviceinfo.txt')
$devNowLine = ($di | Select-String -Pattern 'date_device=(.+)').Matches[0].Groups[1].Value
$devNow = [DateTimeOffset]::ParseExact($devNowLine, 'ddd MMM dd HH:mm:ss yyyy', [Globalization.CultureInfo]::InvariantCulture)
# uptime line: uptime=SECONDS
$up = [double](($di | Select-String -Pattern 'uptime=([\d.]+)').Matches[0].Groups[1].Value)
# device boot epoch (device clock - uptime); device clock at capture:
$bootEpoch = $devNow.ToUnixTimeSeconds() - [math]::Floor($up)
$zero = [DateTimeOffset]::new([DateTime]::ParseExact($Date, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture), $devNow.Offset)
function ToEpoch([string]$hm) { return $zero.ToUnixTimeSeconds() + [TimeSpan]::Parse($hm).TotalSeconds }
$a = ToEpoch $From; $b = ToEpoch $To
Write-Host "boot=$bootEpoch window $From..$To => $a..$b  (offset=$($devNow.Offset))"

function HM([double]$e) {
    return [DateTimeOffset]::FromUnixTimeSeconds([int]$e).ToOffset($devNow.Offset).ToString('HH:mm:ss') + ('.{0:d3}' -f ([int](($e - [math]::Floor($e)) * 1000)))
}

$rx = '^\s*(\d{10})\.(\d{3})\s+(.*)$'
$parsed = New-Object System.Collections.Generic.List[object]
$reader = [IO.StreamReader]::new($InFile)
while (-not $reader.EndOfStream) {
    $line = $reader.ReadLine()
    $m = [regex]::Match($line, $rx)
    if (-not $m.Success) { continue }
    $e = [double]("" + $m.Groups[1].Value + "." + $m.Groups[2].Value)
    if ($e -lt $a -or $e -gt $b) { continue }
    $parsed.Add([pscustomobject]@{ E = $e; T = (HM $e); Rest = $m.Groups[3].Value })
}
$reader.Close()
Write-Host "window lines: $($parsed.Count)"
[IO.File]::WriteAllLines((Join-Path $OutDir 'slice_window.txt'), ($parsed | ForEach-Object { "{0} {1}" -f $_.T, $_.Rest }))

function Pick([string]$name, [string]$pattern, [int]$max = 400) {
    $r = @($parsed | Where-Object { $_.Rest -match $pattern } | Select-Object -First $max)
    $lines = $r | ForEach-Object { "{0} {1}" -f $_.T, $_.Rest }
    [IO.File]::WriteAllLines((Join-Path $OutDir ("pick_$name.txt")), $lines)
    Write-Host ("--- pick_{0}: {1} lines ---" -f $name, $r.Count)
    $lines | Select-Object -First 60
}

Pick 'doze' 'DeviceIdleController|deviceidle|mPowerChangedState|light-idle|deep-idle|doze:|Entering|exiting idle|power save'
Pick 'freeze' 'freeze|Frozen|FROZEN|unfreeze|PowerKeeper|powerkeeper|killapp|Kill.*process|cached'
Pick 'audiofocus' 'AudioFocus|audiofocus|AS.FOCUS|focus stack|OnAudioFocusChangeListener|Dispatching|balanc'
Pick 'screen' 'screen_state|ScreenOn|screenOn|mScreenOn|goToSleep|wakeUp|WAKE|SLEEP|DISPLAY_INACTIVE|dimming|blank'
Pick 'pkg' 'yos\.music'
Pick 'net' 'ConnectivityService|NetworkAgent|wifi.*(disab|suspend|scan)|IpClient|netd|Tethering|lwrun|uid=10509|setAllowed|Restrict|panic|bsdiff'
Pick 'audioflinger' 'AudioFlinger|AudioTrack|audioflinger|standby|fastpath|client death|createTrack|destroyTrack'
Pick 'am' 'am_kill|am_proc_died|am_create|am_destroy|am_foreground|am_mem|am_uid|anr|ActivityManager.*(yos|freez)'
Write-Host DONE
