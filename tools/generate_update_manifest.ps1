#requires -Version 5.1
<#!
.SYNOPSIS
Generates a local Sonify update manifest from an already signed APK.
.DESCRIPTION
Requires installed Android SDK tools and Java. Never builds, signs, uploads,
or activates an update. Validation failures leave any previous output intact.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$Tag,
    [Parameter(Mandatory = $true)]
    [string]$NotesJsonPath,
    [string]$SdkPath = 'D:\Android\Sdk',
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\updates\generated.json'),
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string]$ExpectedSignerSha256,
    [switch]$AllowDebugSigning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$apkStream = $null
$tempPath = $null

function Invoke-SdkTool {
    param([string]$ToolPath, [string[]]$Arguments)
    # PowerShell 5.1 wraps native stderr as ErrorRecord, even for warnings.
    $previousPreference = $ErrorActionPreference
    $previousEncoding = [Console]::OutputEncoding
    try {
        $ErrorActionPreference = 'Continue'
        [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
        $lines = @(& $ToolPath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        [Console]::OutputEncoding = $previousEncoding
        $ErrorActionPreference = $previousPreference
    }
    $text = ($lines | ForEach-Object { $_.ToString() }) -join "`n"
    if ($exitCode -ne 0) {
        throw "SDK tool failed ($exitCode): $ToolPath`n$text"
    }
    return $text
}

function Get-RequiredMatch {
    param([string]$Text, [string]$Pattern, [string]$Field)
    $result = [regex]::Match($Text, $Pattern, [Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $result.Success) { throw "Cannot extract $Field from APK metadata." }
    return $result.Groups[1].Value
}

function Convert-PositiveInteger {
    param([string]$Value, [string]$Field)
    [int]$number = 0
    if ($Value -notmatch '^[0-9]+$' -or
        -not [int]::TryParse($Value, [ref]$number) -or $number -le 0) {
        throw "$Field must be a positive 32-bit integer; found '$Value'."
    }
    return $number
}

try {
    if ($AllowDebugSigning -and [string]::IsNullOrWhiteSpace($ExpectedSignerSha256)) {
        throw 'AllowDebugSigning requires ExpectedSignerSha256 from a trusted previously published APK.'
    }
    $apk = Get-Item -LiteralPath $ApkPath
    if ($apk.PSIsContainer -or $apk.Extension -ine '.apk' -or $apk.Length -le 0) {
        throw 'ApkPath must point to a nonempty, already signed .apk file.'
    }
    if ($apk.Length -gt 200L * 1024 * 1024) {
        throw 'APK exceeds the client limit of 200 MiB.'
    }
    if ($apk.Name -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$') {
        throw 'APK filename must be URL-safe ASCII with a lowercase .apk extension.'
    }
    $notesFile = Get-Item -LiteralPath $NotesJsonPath
    if ($notesFile.PSIsContainer) { throw 'NotesJsonPath must be a JSON file.' }
    $outputFullPath = [IO.Path]::GetFullPath($OutputPath)
    if ([IO.Path]::GetFileName($outputFullPath) -ieq 'latest.json') {
        throw 'OutputPath cannot be latest.json. Activation is a separate manual release step.'
    }
    if ($outputFullPath -ieq $apk.FullName -or $outputFullPath -ieq $notesFile.FullName) {
        throw 'OutputPath must not overwrite the APK or release notes input.'
    }

    $notes = Get-Content -LiteralPath $notesFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -eq $notes -or $notes -isnot [pscustomobject]) {
        throw 'Release notes must be an object mapping locales to string arrays.'
    }
    $locales = @('en', 'zh-CN', 'zh-TW', 'ja')
    $properties = @($notes.PSObject.Properties)
    if ($properties.Count -ne $locales.Count) {
        throw 'Release notes must contain exactly en, zh-CN, zh-TW, and ja.'
    }
    $releaseNotes = [ordered]@{}
    foreach ($locale in $locales) {
        $property = @($properties | Where-Object { $_.Name -ceq $locale })
        if ($property.Count -ne 1 -or $property[0].Value -isnot [array]) {
            throw "Release notes '$locale' must be a JSON array."
        }
        if ($property[0].Value.Count -gt 100) {
            throw "Release notes '$locale' must contain no more than 100 items."
        }
        foreach ($note in $property[0].Value) {
            if ($note -isnot [string] -or [string]::IsNullOrWhiteSpace($note) -or $note.Length -gt 4096) {
                throw "Release notes '$locale' must contain only nonempty strings of at most 4096 characters."
            }
        }
        $releaseNotes[$locale] = [string[]]$property[0].Value
    }

    $sdk = Get-Item -LiteralPath $SdkPath
    if (-not $sdk.PSIsContainer) { throw 'SdkPath must be an Android SDK directory.' }
    $buildTools = @(Get-ChildItem -LiteralPath (Join-Path $sdk.FullName 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object -Property @{ Expression = { [version]$_.Name }; Descending = $true })
    $toolSet = @($buildTools | Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe') -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat') -PathType Leaf)
    })
    if ($toolSet.Count -eq 0) {
        throw 'Install stable Android SDK build-tools containing aapt2.exe and apksigner.bat.'
    }
    $aapt2 = Join-Path $toolSet[0].FullName 'aapt2.exe'
    $apksigner = Join-Path $toolSet[0].FullName 'apksigner.bat'
    Write-Verbose "Using build-tools $($toolSet[0].Name)."

    # Hold a read lock through verification, metadata extraction, and hashing.
    $apkStream = [IO.File]::Open($apk.FullName, [IO.FileMode]::Open,
        [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $signature = Invoke-SdkTool $apksigner @('verify', '--verbose', '--print-certs', $apk.FullName)
    $signerMatches = [regex]::Matches($signature,
        '(?m)^(?:(?:Signer #\d+)|(?:V[0-9]+(?:\.[0-9]+)? Signer)):?\s*certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$')
    if ($signerMatches.Count -eq 0) { throw 'No verified APK signer certificate was reported.' }
    if ($ExpectedSignerSha256) {
        if ($signerMatches.Count -ne 1 -or
            $signerMatches[0].Groups[1].Value -ine $ExpectedSignerSha256) {
            throw 'APK signer does not match ExpectedSignerSha256.'
        }
    }
    if ($signature -match '(?im)^(?:Signer #\d+|V[0-9]+(?:\.[0-9]+)? Signer):?\s*certificate DN:.*CN=Android Debug(?:,|\s*$)') {
        if (-not $AllowDebugSigning) {
            throw 'Android debug-key APK rejected. Legacy continuity requires AllowDebugSigning and a matching ExpectedSignerSha256.'
        }
        Write-Warning 'Allowing a verified legacy debug signer for continuity only. This does not make debug signing safe for production.'
    }
    foreach ($signer in $signerMatches) {
        Write-Host "Verified signer SHA-256: $($signer.Groups[1].Value.ToLowerInvariant())"
    }

    $badging = Invoke-SdkTool $aapt2 @('dump', 'badging', $apk.FullName)
    if ($badging -match "(?m)^package:.*\bsplit='") {
        throw 'Split APKs are not supported. Supply a standalone, universal APK.'
    }
    $applicationId = Get-RequiredMatch $badging "^package:\s+name='([^']+)'" 'applicationId'
    $versionCode = Convert-PositiveInteger (
        Get-RequiredMatch $badging "^package:.*\bversionCode='([0-9]+)'" 'versionCode') 'versionCode'
    $versionName = Get-RequiredMatch $badging "^package:.*\bversionName='([^']+)'" 'versionName'
    $minSdk = Convert-PositiveInteger (
        Get-RequiredMatch $badging "^(?:minSdkVersion|sdkVersion):'([^']+)'" 'minSdk') 'minSdk'
    if ($minSdk -gt 10000) { throw 'minSdk must not exceed 10000.' }
    if ($applicationId -cne 'com.sonify.music') {
        throw "Unexpected applicationId '$applicationId'; expected com.sonify.music."
    }
    if ([string]::IsNullOrWhiteSpace($versionName) -or $versionName.Length -gt 128) {
        throw 'versionName must contain 1 to 128 characters.'
    }
    if ($badging -match "(?m)^package:.*\bversionCodeMajor='(?!0')[^']+'") {
        throw 'versionCodeMajor is not supported by schemaVersion 1.'
    }
    if ($badging -match '(?m)^uses-split:') {
        throw 'APK requires another split. Supply a self-contained APK.'
    }

    # Command-line tools are optional; when present, independently check metadata.
    $analyzers = @()
    $cmdlineRoot = Join-Path $sdk.FullName 'cmdline-tools'
    if (Test-Path -LiteralPath $cmdlineRoot -PathType Container) {
        $analyzers = @(Get-ChildItem -LiteralPath $cmdlineRoot -Directory |
            ForEach-Object { Join-Path $_.FullName 'bin\apkanalyzer.bat' } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Sort-Object -Descending)
    }
    $legacyAnalyzer = Join-Path $sdk.FullName 'tools\bin\apkanalyzer.bat'
    if ($analyzers.Count -eq 0 -and (Test-Path -LiteralPath $legacyAnalyzer -PathType Leaf)) {
        $analyzers = @($legacyAnalyzer)
    }
    if ($analyzers.Count -gt 0) {
        $checks = [ordered]@{
            'application-id' = $applicationId
            'version-code' = $versionCode.ToString([Globalization.CultureInfo]::InvariantCulture)
            'version-name' = $versionName
            'min-sdk' = $minSdk.ToString([Globalization.CultureInfo]::InvariantCulture)
        }
        foreach ($field in $checks.Keys) {
            $value = (Invoke-SdkTool $analyzers[0] @('manifest', $field, $apk.FullName)).Trim()
            if ($value -cne $checks[$field]) {
                throw "aapt2/apkanalyzer disagree on $field; refusing to generate a manifest."
            }
        }
    }

    Add-Type -AssemblyName System.IO.Compression
    $archive = [IO.Compression.ZipArchive]::new($apkStream, [IO.Compression.ZipArchiveMode]::Read, $true)
    try {
        $abis = @($archive.Entries | ForEach-Object {
            if ($_.FullName -match '^lib/([^/]+)/[^/]+\.so$') { $Matches[1] }
        } | Sort-Object -Unique)
    }
    finally { $archive.Dispose() }
    $allowedAbis = @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
    if ($abis.Count -lt 1 -or $abis.Count -gt 4) {
        throw 'APK must contain between one and four supported native ABIs.'
    }
    foreach ($abi in $abis) {
        if ($abi -cnotin $allowedAbis) { throw "Unsupported APK ABI '$abi'." }
    }
    $apkStream.Position = 0
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $sha256 = ([BitConverter]::ToString($hasher.ComputeHash($apkStream))).Replace('-', '').ToLowerInvariant()
    }
    finally { $hasher.Dispose() }
    [long]$apkSize = $apkStream.Length
    if ($apkSize -le 0 -or $apkSize -gt 200L * 1024 * 1024) {
        throw 'APK size must be between 1 byte and 200 MiB.'
    }
    $encodedTag = [Uri]::EscapeDataString($Tag)
    $encodedName = [Uri]::EscapeDataString($apk.Name)
    $manifest = [ordered]@{
        schemaVersion = 1
        applicationId = $applicationId
        versionCode = $versionCode
        versionName = $versionName
        publishedAt = [DateTimeOffset]::UtcNow.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
        minSdk = $minSdk
        abis = [string[]]$abis
        apkUrl = "https://github.com/XiaoZhi0729/Sonify/releases/download/$encodedTag/$encodedName"
        apkSize = $apkSize
        sha256 = $sha256
        releasePageUrl = "https://github.com/XiaoZhi0729/Sonify/releases/tag/$encodedTag"
        releaseNotes = $releaseNotes
    }
    $json = ($manifest | ConvertTo-Json -Depth 6) + "`n"
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 256L * 1024) {
        throw 'Manifest exceeds the client limit of 256 KiB.'
    }
    $parent = [IO.Path]::GetDirectoryName($outputFullPath)
    [void][IO.Directory]::CreateDirectory($parent)
    $tempPath = Join-Path $parent ('.update-manifest-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    [IO.File]::WriteAllText($tempPath, $json, [Text.UTF8Encoding]::new($false))
    if ([IO.File]::Exists($outputFullPath)) {
        [IO.File]::Replace($tempPath, $outputFullPath, $null)
    }
    else { [IO.File]::Move($tempPath, $outputFullPath) }
    $tempPath = $null
    Write-Host "Generated local manifest: $outputFullPath"
    Write-Host 'Nothing was uploaded. Verify the public release attachment before manually activating latest.json.'
}
catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
finally {
    if ($null -ne $apkStream) { $apkStream.Dispose() }
    if ($null -ne $tempPath -and [IO.File]::Exists($tempPath)) { [IO.File]::Delete($tempPath) }
}
