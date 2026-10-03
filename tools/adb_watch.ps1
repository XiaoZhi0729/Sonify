# Catch which adb.exe is being launched, and by whom.
#
# A 0xc0000142 dialog blocks its process instead of exiting it, so the culprit stays visible in the
# process list for as long as the dialog is up. Polling fast enough therefore identifies it without
# needing an elevated WMI event consumer.
#
#   powershell -File tools\adb_watch.ps1 -Seconds 600
# Then: when a dialog appears, LEAVE IT OPEN and tell me. Read the CSV afterwards.
param(
    [int]$Seconds = 600,
    [string]$Out = "diagnostics\adb_watch.csv",
    [int]$IntervalMs = 250
)
$ErrorActionPreference = "Continue"
$bad = @('AdbWinApi.dll', 'AdbWinUsbApi.dll', 'libwinpthread-1.dll')
$seen = @{}
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$header = "time,adbPid,adbPath,sizeMB,missingDlls,parentPid,parentName,parentPath"
if (-not (Test-Path $Out)) { $header | Set-Content $Out -Encoding ascii }
while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
    foreach ($p in Get-CimInstance Win32_Process -Filter "Name='adb.exe'" -ErrorAction SilentlyContinue) {
        $key = "$($p.ProcessId)"
        if ($seen.ContainsKey($key)) { continue }
        $dir = if ($p.ExecutablePath) { Split-Path $p.ExecutablePath -Parent } else { "" }
        $miss = @()
        $sz = 0
        if ($dir -and (Test-Path $p.ExecutablePath)) {
            $sz = [math]::Round((Get-Item $p.ExecutablePath).Length / 1MB, 1)
            $miss = @($bad | Where-Object { -not (Test-Path (Join-Path $dir $_)) })
        }
        $pp = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.ParentProcessId)" -ErrorAction SilentlyContinue
        $seen[$key] = $true
        $line = @(
            (Get-Date -Format 'HH:mm:ss.fff'), $p.ProcessId, $p.ExecutablePath, $sz,
            ($miss -join '|'), $p.ParentProcessId, $(if ($pp) { $pp.Name } else { '?' }),
            $(if ($pp) { $pp.ExecutablePath } else { '' })
        ) -join ','
        $line | Add-Content $Out -Encoding ascii
        # Echo live so a background terminal shows it without opening the file.
        Write-Host ("{0} pid={1} missing=[{2}] <- {3}" -f (Get-Date -Format 'HH:mm:ss.fff'),
            $p.ExecutablePath, ($miss -join ','), $(if ($pp) { "$($pp.Name)($($pp.ProcessId))" } else { "?" }))
    }
    # Forget exited pids so a relaunch of the same exe is recorded again.
    $alive = @{}
    foreach ($p in Get-CimInstance Win32_Process -Filter "Name='adb.exe'" -ErrorAction SilentlyContinue) {
        $alive["$($p.ProcessId)"] = $true
    }
    $seen = $alive
    Start-Sleep -Milliseconds $IntervalMs
}
Write-Host "watch finished: $Seconds s -> $Out"
