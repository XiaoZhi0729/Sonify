# Structural diff of the two backdrop library generations, from the AARs in the Gradle cache.
#
# Frame-time percentiles cannot separate these two versions (measured: the same config drifted 6ms
# between reps), so this compares what is provable: the class inventory, every public/protected
# member, and the presence of the two mechanisms that would make a difference -
#   * Modifier.Node.shouldAutoInvalidate overrides  (what the reference app's backdrop2 does)
#   * a conditional / force-flagged recordLayer     (skip re-recording an unchanged source)
#
#   powershell -File tools\backdrop_version_diff.ps1
#
# ASCII only: PowerShell 5.1 reads UTF-8-without-BOM as ANSI and silently corrupts the script.
param(
    [string]$CacheRoot = 'C:\Users\Administrator\.gradle\caches\modules-2\files-2.1\io.github.kyant0',
    [string]$WorkDir = 'd:\adxm\FlamingoSank\FlamingoSank\_glass_probe',
    [string]$Javap = 'C:\Program Files\Java\jdk-17.0.11\bin\javap.exe'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Find-Aar([string]$dir, [string]$version) {
    if (-not (Test-Path $dir)) { return $null }
    return Get-ChildItem $dir -Recurse -Filter '*.aar' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match [regex]::Escape("\$version\") } |
        Select-Object -First 1
}

# Unzips classes.jar out of an AAR using absolute paths: the .NET zip APIs resolve relative
# paths against the *process* directory, not PowerShell's current location, and silently
# produce nothing otherwise.
function Extract-Classes([string]$aarPath, [string]$destPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($aarPath)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
        if (-not $entry) { throw "no classes.jar inside $aarPath" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destPath, $true)
    } finally { $zip.Dispose() }
    return $destPath
}

# Every class that mentions one of these names is reported; a hit inside a node class means that
# node participates in the mechanism.
$markers = @(
    'shouldAutoInvalidate',
    'invalidateDrawCache',
    'recordLayer',
    'onObservedReadsChanged',
    'BlurMaskFilter',
    'RuntimeShader',
    'RenderEffect',
    'CompositingStrategy'
)

function Scan-Jar([string]$jarPath) {
    $result = @{}
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notlike '*.class') { continue }
            $stream = $entry.Open()
            $mem = New-Object System.IO.MemoryStream
            $stream.CopyTo($mem)
            $stream.Dispose()
            $text = [System.Text.Encoding]::ASCII.GetString($mem.ToArray())
            $mem.Dispose()
            foreach ($marker in $markers) {
                if ($text.Contains($marker)) {
                    $key = $entry.FullName -replace '\.class$', '' -replace '/', '.'
                    if (-not $result.ContainsKey($marker)) { $result[$marker] = @() }
                    $result[$marker] += $key
                }
            }
        }
    } finally { $zip.Dispose() }
    return $result
}

$targets = @(
    @{ Name = '1.0.5'; Dir = (Join-Path $CacheRoot 'backdrop\1.0.5') },
    @{ Name = '2.0.1'; Dir = (Join-Path $CacheRoot 'backdrop-android\2.0.1') }
)

$collect = @{}
foreach ($target in $targets) {
    $label = $target.Name
    $aar = Find-Aar $target.Dir $label
    if (-not $aar) { Write-Output "!! $label : no AAR under $($target.Dir)"; continue }
    $jar = Extract-Classes $aar.FullName (Join-Path $WorkDir "bd-$label-classes.jar")
    Write-Output ("### {0}  aar={1}  classes.jar={2}KB" -f $label, $aar.Name, [math]::Round((Get-Item $jar).Length / 1KB))

    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    $classes = @($zip.Entries | Where-Object { $_.FullName -like '*.class' } |
        ForEach-Object { $_.FullName -replace '\.class$', '' -replace '/', '.' } | Sort-Object)
    $zip.Dispose()
    $public = @($classes | Where-Object { $_ -notmatch '\$' })
    Write-Output ("    classes: {0} total, {1} top-level" -f $classes.Count, $public.Count)
    $collect[$label] = @{ Jar = $jar; Classes = $public; Scan = (Scan-Jar $jar) }
}

if ($collect.Count -lt 2) { Write-Output '!! both versions are required for a diff'; exit 1 }

$old = $collect['1.0.5']
$new = $collect['2.0.1']

Write-Output ''
Write-Output '=== class inventory: only in 1.0.5 ==='
Compare-Object $old.Classes $new.Classes | Where-Object { $_.SideIndicator -eq '<=' } |
    ForEach-Object { Write-Output ('    - ' + $_.InputObject) }
Write-Output '=== class inventory: only in 2.0.1 ==='
Compare-Object $old.Classes $new.Classes | Where-Object { $_.SideIndicator -eq '=>' } |
    ForEach-Object { Write-Output ('    + ' + $_.InputObject) }

Write-Output ''
Write-Output '=== mechanism presence (class count per marker) ==='
'{0,-24} {1,10} {2,10}' -f 'marker', '1.0.5', '2.0.1'
foreach ($marker in $markers) {
    $countOld = @($old.Scan[$marker]).Count
    $countNew = @($new.Scan[$marker]).Count
    '{0,-24} {1,10} {2,10}' -f $marker, $countOld, $countNew
}

Write-Output ''
Write-Output '=== per-class ownership of each mechanism ==='
foreach ($marker in 'shouldAutoInvalidate', 'invalidateDrawCache', 'recordLayer', 'BlurMaskFilter', 'onObservedReadsChanged') {
    Write-Output ("  " + $marker)
    foreach ($label in '1.0.5', '2.0.1') {
        $hits = @($collect[$label].Scan[$marker]) | Sort-Object
        Write-Output ("    " + $label + ": " + $(if ($hits.Count) { ($hits | ForEach-Object { $_ -replace '^com\.kyant\.backdrop\.', '' }) -join ', ' } else { 'NONE' }))
    }
}

Write-Output ''
Write-Output '=== recordLayer signatures: is there a conditional / force variant? ==='
# javap writes "class not found" to stderr; with ErrorActionPreference=Stop that aborts the whole
# script, so downgrade around the native calls and just skip the class that does not exist.
$savedPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
foreach ($label in '1.0.5', '2.0.1') {
    $jar = $collect[$label].Jar
    Write-Output ("  --- " + $label + " ---")
    foreach ($candidate in 'com.kyant.backdrop.LayerRecorderKt', 'com.kyant.backdrop.internal.LayerRecorderKt') {
        $dump = @(& $Javap -p -classpath $jar $candidate 2>&1)
        $sig = @($dump | Where-Object { $_ -match 'recordLayer' })
        if ($sig.Count) {
            foreach ($line in $sig) { Write-Output ('    ' + $line.Trim()) }
        }
    }
}
$ErrorActionPreference = $savedPreference

