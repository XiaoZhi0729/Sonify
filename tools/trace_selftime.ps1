# Attribute main-thread frame time from an atrace text dump by pairing B/E markers into a stack and
# computing SELF time per section (inclusive time double-counts nesting and hides the real cost).
#
#   powershell -File tools\trace_selftime.ps1 -Trace _glass.trace -Tid 14316
param(
    [string]$Trace = "_glass.trace",
    # Named Tid, not Pid: PowerShell parameters are case-insensitive, so a $Pid parameter would
    # shadow the automatic $PID variable and read as *this script's* pid.
    [int]$Tid = 0,
    # ftrace timestamps are in SECONDS; everything below converts to ns for the arithmetic.
    [int]$Frames = 0,
    [int]$Top = 30
)
$ErrorActionPreference = "Stop"

if ($Tid -eq 0) {
    # Main thread of the app: tid == tgid, and the comm is truncated to 15 chars by the kernel.
    $m = Select-String -Path $Trace -Pattern '^\s*\S*sic\.player\.oss-\s*(\d+)\s+\(\s*(\d+)\)' |
        Select-Object -First 1
    if (-not $m) { throw "app main thread not found in $Trace" }
    $Tid = [int]$m.Matches[0].Groups[1].Value
}
Write-Host "analysing tid=$Tid (main thread)"

$rx = '^\s*(\S+)-\s*(\d+)\s+\(\s*(\d+)\)\s+\[\d+\]\s+\S+\s+(\d+\.\d+):\s+tracing_mark_write:\s+([BES])\|(\d+)(?:\|(.*))?$'
$stack = New-Object System.Collections.Generic.Stack[object]
$self = @{}          # name -> self ns
$incl = @{}          # name -> inclusive ns
$calls = @{}
$reader = [System.IO.File]::OpenRead((Resolve-Path $Trace))
$buf = New-Object System.IO.StreamReader $reader
$curName = $null; $curStart = 0.0; $curChild = 0.0
while (-not $buf.EndOfStream) {
    $line = $buf.ReadLine()
    # Cheap pre-filter: the regex over 500k lines costs several times more than the analysis itself.
    if ($line.IndexOf('tracing_mark_write') -lt 0) { continue }
    $m = [regex]::Match($line, $rx)
    if (-not $m.Success) { continue }
    if ([int]$m.Groups[2].Value -ne $Tid) { continue }
    $kind = $m.Groups[5].Value
    $ts = [double]$m.Groups[4].Value
    $name = $m.Groups[7].Value
    if ($kind -eq 'B') {
        if ($null -ne $curName) { $stack.Push(@($curName, $curStart, $curChild, $ts)) }
        $curName = $name; $curStart = $ts; $curChild = 0.0
    } elseif ($kind -eq 'E') {
        if ($null -eq $curName) { continue }
        $dur = ($ts - $curStart) * 1e9      # seconds -> ns
        if (-not $self.ContainsKey($curName)) { $self[$curName] = 0.0; $incl[$curName] = 0.0; $calls[$curName] = 0 }
        $self[$curName] += ($dur - $curChild)
        $incl[$curName] += $dur
        $calls[$curName] += 1
        if ($stack.Count -gt 0) {
            $p = $stack.Pop()
            $curName = $p[0]; $curStart = $p[1]; $curChild = $p[2] + $dur
        } else { $curName = $null; $curStart = 0.0; $curChild = 0.0 }
    }
}
$buf.Dispose()

$total = ($self.Values | Measure-Object -Sum).Sum
if ($Frames -eq 0) {
    # One `traversal` per drawn frame is the closest thing to a frame counter in an atrace dump.
    $t = @($self.Keys | Where-Object { $_ -eq 'traversal' })
    if ($t.Count) { $Frames = $calls['traversal'] }
}
Write-Host ("traced self time: {0:N1} ms over {1} frames = {2:N1} ms/frame of traced work" -f `
    ($total / 1e6), $Frames, $(if ($Frames) { $total / 1e6 / $Frames } else { 0 }))
""
"{0,-58} {1,9} {2,7} {3,9} {4,9} {5,8}" -f "section (self)", "total ms", "share", "calls", "ms/frame", "avg us"
"-" * 104
$self.GetEnumerator() | Sort-Object { -$_.Value } | Select-Object -First $Top | ForEach-Object {
    $n = $_.Key
    if ($n.Length -gt 56) { $n = $n.Substring(0, 56) + ".." }
    "{0,-58} {1,9:N1} {2,6:N1}% {3,9:N0} {4,9:N2} {5,8:N1}" -f `
        $n, ($_.Value / 1e6), (100 * $_.Value / $total), $calls[$_.Key],
        $(if ($Frames) { $_.Value / 1e6 / $Frames } else { 0 }), ($_.Value / $calls[$_.Key] / 1e3)
}
