# Find the device's current adb-over-Wi-Fi port, then say which candidate is really adb.
#
# Usage:
#   powershell -File tools\adb_port_scan.ps1                       # default phone IP
#   powershell -File tools\adb_port_scan.ps1 -Ip 192.168.1.5
#   powershell -File tools\adb_port_scan.ps1 -Ip 192.168.1.4 -NoProbe
#
# Why the probe exists: an ephemeral wireless-debugging port is not the only thing listening in
# that range. One run found three open ports and NONE of them was adb - all three went straight
# to "offline" after `adb connect`. Reporting only "OPEN PORTS: a, b, c" sent the reader at the
# wrong tool (re-pair the device) instead of the right one.
#
# Scan scope is deliberately narrow: ONE host, the Android wireless-debugging range plus 5555.
# Implementation: pure .NET async sockets, chunked, so no PowerShell runspace is spawned per
# probe. ASCII only - PowerShell 5.1 decodes a BOM-less UTF-8 script as ANSI.
param(
    [string]$Ip = "192.168.1.4",
    [int]$Low = 32768,
    [int]$High = 60999,
    [int]$Chunk = 300,
    [int]$TimeoutMs = 400,
    [switch]$NoProbe
)

# NOT $args: that is a PowerShell automatic variable, and using it as a parameter name silently
# swallows the arguments (adb then prints its version banner and every probe reads as unknown).
function Invoke-Adb([string[]]$AdbArgs) {
    return @(& adb @AdbArgs 2>&1)
}

$ports = New-Object System.Collections.Generic.List[int]
$ports.Add(5555)
for ($p = $Low; $p -le $High; $p++) { $ports.Add($p) }

$found = New-Object System.Collections.Generic.List[int]
$sw = [System.Diagnostics.Stopwatch]::StartNew()

for ($i = 0; $i -lt $ports.Count; $i += $Chunk) {
    $take = [Math]::Min($Chunk, $ports.Count - $i)
    $batch = @()
    for ($j = 0; $j -lt $take; $j++) {
        # Parentheses per element: inside @() the comma binds tighter than +.
        $port = $ports[$i + $j]
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $task = $client.ConnectAsync($Ip, $port)
            $batch += [pscustomobject]@{ Port = $port; Client = $client; Task = $task }
        } catch {
            $client.Close()
        }
    }
    $tasks = @($batch | ForEach-Object { $_.Task })
    [void][System.Threading.Tasks.Task]::WaitAll($tasks, $TimeoutMs)
    foreach ($item in $batch) {
        if ($item.Task.IsCompleted -and -not $item.Task.IsFaulted -and $item.Client.Connected) {
            $found.Add($item.Port)
        }
        try { $item.Client.Close() } catch { }
    }
}

"scanned $($ports.Count) ports on $Ip in $([int]$sw.Elapsed.TotalSeconds)s"
if ($found.Count -eq 0) {
    "no open ports - the host is up but wireless debugging is off (or the range is wrong)"
    exit 1
}
"OPEN PORTS: " + (($found | Sort-Object) -join ", ")
# Loopback is never a wireless-debugging target, and pointing adb at its own server socket
# (`adb connect 127.0.0.1:5037`) wedges the client - a run against localhost hung until the
# caller's whole timeout expired. Skip the probe for local addresses instead.
if ($NoProbe -or $Ip -match '^(127\.|0\.0\.0\.0$|localhost$)') {
    if (-not $NoProbe) { "probe skipped for a local address (adb would talk to its own server)" }
    "try each one: " + (($found | Sort-Object | ForEach-Object { "adb connect ${Ip}:$_" }) -join "  ")
    exit 0
}

# Classify each open port by actually connecting and reading back the device state. A stale
# wireless-debugging pairing shows up as "offline" here, which no amount of retrying fixes.
$results = @()
foreach ($port in ($found | Sort-Object)) {
    $target = "${Ip}:$port"
    Invoke-Adb @("disconnect", $target) | Out-Null
    $conn = ((Invoke-Adb @("connect", $target)) | Select-Object -Last 1 | Out-String).Trim()
    Start-Sleep -Milliseconds 1500
    $line = @(((Invoke-Adb @("devices")) | Out-String) -split "`n") |
        Where-Object { $_ -match [regex]::Escape($target) } | Select-Object -First 1
    $state = "unknown"
    if ($line -match "\s(offline|unauthorized|device|no permissions)\b") { $state = $matches[1] }
    $results += [pscustomobject]@{ Port = $port; Connect = $conn; State = $state }
}

$results | Format-Table -AutoSize

$live = @($results | Where-Object { $_.State -eq "device" })
# Deliberately NO `adb kill-server` here: it tears down the user's server (and any logcat
# capture running beside this script) just to tidy a list, and doing it while a connect is
# still pending is how a probe hangs.
foreach ($r in $results) {
    if ($r.Port -notin $live.Port) { Invoke-Adb @("disconnect", "${Ip}:$($r.Port)") | Out-Null }
}
if ($live.Count -gt 0) {
    "ADB READY: " + (($live | ForEach-Object { "${Ip}:$($_.Port)" }) -join ", ")
    exit 0
}
"NOT adb (or pairing rejected): every open port failed to reach state 'device'."
"Most likely the phone's wireless-debugging pairing expired - re-enable it (or pair with a new"
"code) on the device; scanning again is pointless until then."
exit 2
