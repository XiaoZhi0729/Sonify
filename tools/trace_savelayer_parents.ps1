# Attribute each "alpha caused saveLayer" to the draw call that forced it, by walking the
# RenderThread's B/E stack and recording the ancestor chain. gfxinfo percentiles can tell you that
# the RenderThread is the pacer; only the nesting says which modifier did it.
#
#   powershell -File tools\trace_savelayer_parents.ps1 -Trace _glass.trace -Tgid 14316 -RtTid 16497 -Frames 330
param(
    [string]$Trace = "_glass.trace",
    [int]$Tgid = 14316,
    [int]$RtTid = 0,
    [int]$Frames = 330,
    [int]$Depth = 3
)
$ErrorActionPreference = "Stop"
if ($RtTid -eq 0) {
    $m = Select-String -Path $Trace -Pattern "^\s*RenderThread-\s*(\d+)\s+\(\s*$Tgid\s*\)" | Select-Object -First 1
    if (-not $m) { throw "no RenderThread for tgid $Tgid" }
    $RtTid = [int]$m.Matches[0].Groups[1].Value
}
# The marker's pid field is the TGID, not the tid - matching on tid silently yields zero hits.
$rx = "^\s*RenderThread-\s*$RtTid\s+\(\s*$Tgid\s*\)\s+\[\d+\]\s+\S+\s+[\d.]+:\s+tracing_mark_write:\s+([BE])\|$Tgid(?:\|(.*))?`$"
$stack = New-Object System.Collections.Generic.List[string]
$groups = @{}
$buf = New-Object System.IO.StreamReader((Resolve-Path $Trace))
while (-not $buf.EndOfStream) {
    $line = $buf.ReadLine()
    if ($line.IndexOf('tracing_mark_write') -lt 0) { continue }
    $m = [regex]::Match($line, $rx)
    if (-not $m.Success) { continue }
    $kind = $m.Groups[1].Value
    $name = $m.Groups[2].Value
    if ($kind -eq 'B') {
        if ($name) { $stack.Add($name) }
        if ($name -and $name.StartsWith('alpha caused saveLayer')) {
            # Nearest meaningful ancestors, skipping the anonymous size-variant duplicates.
            $chain = @()
            for ($i = $stack.Count - 2; $i -ge 0 -and $chain.Count -lt $Depth; $i--) {
                $p = $stack[$i]
                if ($p -eq '') { continue }
                if ($p -match '^Choreographer|^traversal$|^postAndWait$|^animation$') { continue }
                $chain += ($p -replace '\s+\d+x\d+$', '')
            }
            if ($chain.Count -eq 0) { $chain = @('(no traced ancestor)') }
            $key = ($chain -join '  <-  ')
            if (-not $groups.ContainsKey($key)) { $groups[$key] = New-Object System.Collections.Generic.List[string] }
            $groups[$key].Add($name)
        }
    } elseif ($kind -eq 'E') {
        if ($stack.Count -gt 0) { $stack.RemoveAt($stack.Count - 1) }
    }
}
$buf.Dispose()
"RenderThread tid=$RtTid, $Frames frames"
"{0,6} {1,7}  {2}" -f "count", "perframe", "forced-by (ancestor chain)"
"-" * 108
$groups.GetEnumerator() | Sort-Object { -$_.Value.Count } | ForEach-Object {
    $sizes = ($_.Value | Group-Object | Sort-Object Count -Descending | Select-Object -First 3 |
        ForEach-Object { "$($_.Name -replace 'alpha caused saveLayer ','')x$($_.Count)" }) -join ' '
    "{0,6} {1,7:N1}  {2}   [{3}]" -f $_.Value.Count, ($_.Value.Count / $Frames), $_.Key, $sizes
}
