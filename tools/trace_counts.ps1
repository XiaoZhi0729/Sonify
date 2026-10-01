# Per-frame structural counters for the attribution traces (no stack bookkeeping needed: atrace was
# started with `-a <pkg>`, so only our app emits these markers).
#
# Frame time percentiles could not separate the three bottom-bar sub-switches - with n=2 the same
# config varied by 6ms, larger than the effect being resolved. These counters are structural, so they
# are near-deterministic and can.
#
#   powershell -File tools\trace_counts.ps1 -Dir diagnostics\glass_compare\attr
param(
    [string]$Dir = "diagnostics\glass_compare\attr",
    [string]$Pattern = '*.trace'
)
$ErrorActionPreference = "Stop"

# `renderFrameImpl` is emitted once per drawn frame on the RenderThread.
$needles = [ordered]@{
    Frames      = 'renderFrameImpl'
    SaveLayer   = 'alpha caused saveLayer'
    FillRect    = 'FillRectOp'
    Texture     = 'TextureOp'
    AtlasText   = 'AtlasTextOp'
    AllocImage  = 'VulkanAMDMemoryAllocator::allocateImageMemory'
    FlushLayers = 'flush layers'
    OpsTask     = 'OpsTask::onExecute'
}

$rows = foreach ($f in (Get-ChildItem (Join-Path $Dir $Pattern))) {
    $c = @{}
    foreach ($k in $needles.Keys) { $c[$k] = 0 }
    $br = New-Object System.IO.StreamReader $f.FullName
    while (-not $br.EndOfStream) {
        $line = $br.ReadLine()
        if ($line.IndexOf('tracing_mark_write') -lt 0) { continue }
        foreach ($k in $needles.Keys) {
            if ($line.IndexOf($needles[$k], [System.StringComparison]::Ordinal) -ge 0) { $c[$k]++ }
        }
    }
    $br.Dispose()
    $frames = [math]::Max(1, $c['Frames'])
    $o = [pscustomobject]@{ Config = $f.BaseName }
    foreach ($k in $needles.Keys) {
        if ($k -eq 'Frames') { $o | Add-Member -NotePropertyName Frames -NotePropertyValue $c['Frames'] }
        else { $o | Add-Member -NotePropertyName $k -NotePropertyValue ([math]::Round($c[$k] / $frames, 2)) }
    }
    $o
}
$rows | Format-Table -AutoSize | Out-String -Width 160
Write-Host "all columns except Frames are per-frame averages (per renderFrameImpl)"
