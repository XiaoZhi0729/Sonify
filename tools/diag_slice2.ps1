# diag_slice2.ps1 - slice full_time.txt (logcat -v time) by wall-clock window, then grep
param(
    [string]$InFile = 'd:\adxm\FlamingoSank\FlamingoSank\_diag_interrupt\full_time.txt',
    [string]$OutDir = 'd:\adxm\FlamingoSank\FlamingoSank\_diag_interrupt',
    [string]$Date = '09-27',
    [string]$From = '1:20:00',
    [string]$To = '2:10:00'
)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force -Path (Join-Path $OutDir 'w2') | Out-Null
$outDir = Join-Path $OutDir 'w2'
# normalize to fixed width for comparison: 'DD HH:MM:SS' with blank-padded hour
function Norm([string]$hm) {
    $p = $hm.Split(':')
    $h = [int]$p[0]; $m = [int]$p[1]
    $s = 0; if ($p.Count -gt 2) { $s = [int]$p[2] }
    return ('{0:d2}:{1:d2}:{2:d2}' -f $h, $m, $s)
}
$lo = $Date + ' ' + (Norm $From)
$hi = ($Date + ' ' + (Norm $To)) + '.'   # trailing dot makes the upper bound inclusive of all ms
Write-Host "window: $lo .. $hi"
$rx = '^(\d\d-\d\d)\s+(\d{1,2}):(\d\d:\d\d\.\d+)\s+(.*)$'
$slicePath = Join-Path $outDir 'slice.txt'
$sw = [IO.StreamWriter]::new($slicePath, $false)
$n = 0; $tot = 0
$reader = [IO.StreamReader]::new($InFile, [Text.Encoding]::UTF8, $true)
while (-not $reader.EndOfStream) {
    $line = $reader.ReadLine()
    $tot++
    $m = [regex]::Match($line, $rx)
    if (-not $m.Success) { continue }
    $stamp = '{0} {1}:{2}' -f $m.Groups[1].Value, $m.Groups[2].Value, $m.Groups[3].Value
    $key = '{0} {1}:{2}' -f $m.Groups[1].Value, $m.Groups[2].Value.PadLeft(2, '0'), $m.Groups[3].Value
    $keyLo = $key.Substring(0, 14)
    if ($keyLo.CompareTo($lo) -lt 0 -or $keyLo.CompareTo($hi) -gt 0) { continue }
    $n++
    $sw.WriteLine($line)
}
$reader.Close(); $sw.Close()
Write-Host "slice lines: $n / $tot -> $slicePath"

function Pick([string]$name, [string]$pattern, [int]$max = 500) {
    $r = @(Get-Content $slicePath -Encoding UTF8 | Select-String -Pattern $pattern | Select-Object -First $max)
    $lines = @($r | ForEach-Object { $_.Line })
    $p = Join-Path $outDir ("pick_{0}.txt" -f $name)
    [IO.File]::WriteAllLines($p, $lines)
    Write-Host ("--- pick_{0}: {1} -> {2}" -f $name, $lines.Count, $p)
    $lines | Select-Object -First 45
}

Pick 'pkg' 'yos'
Pick 'doze' 'DeviceIdleController|deviceidle|PowerKeeper|powerkeeper|ownd|Freeze|freeze|frozen|Suspend|suspend|idle'
Pick 'audiofocus' 'AudioFocus|audiofocus|MediaFocus|AS\.|focus'
Pick 'audio' 'AudioFlinger|AudioTrack|audio_hw|AudioPolicy|PlaybackActivity|standby|fasttrack|createTrack|destroyTrack|setParameters'
Pick 'screen' 'screen|Screen|wake|Wake|sleep|Sleep|DIM|blur|Display Power|state='
Pick 'net' 'ConnectivityService|NetworkAgent|wifi|Wifi|IpClient|netd|Tethering|DNS|socket|uid=10509|10509|clash|Clash|vpn|Vpn|radio'
Pick 'am' 'am_kill|am_proc_died|am_pss|am_mem|am_uid|anr|died|Killing|lowmemory|lmk|Shot'
Pick 'session' 'media_session|MediaSession|Session|ExoPlayer|player|Player'
Write-Host DONE
