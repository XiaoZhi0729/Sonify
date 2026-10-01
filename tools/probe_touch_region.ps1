# Probe the effective touch region of one clickable node, point by point.
# "menu open" is detected by the ASCII token "1.0x" (the playback-speed value the
# root menu shows) so the probe does not depend on Chinese literals in the dump.
param(
    [string]$Serial = "192.168.1.5:35137",
    # node bounds: left,top,right,bottom (from a uiautomator dump)
    [string]$Node = "1825,1549,1945,1669"
)

# ASCII-only needles throughout: PowerShell 5.1 decodes a BOM-less UTF-8 script as ANSI, which
# eats quoted Chinese literals. The quality capsule only exists on the fullscreen player, so a
# tier label is a reliable "am I on the player?" probe.
$PlayerNeedle = 'Hi-Res|FLAC|128K|320K'

$parts = @($Node -split "," | ForEach-Object { [int]$_ })
$L = $parts[0]; $T = $parts[1]; $R = $parts[2]; $B = $parts[3]

function Sh($cmd) { & adb -s $Serial shell $cmd }

function Dump-Text {
    Sh "uiautomator dump /sdcard/hp.xml >/dev/null" | Out-Null
    $local = Join-Path $env:TEMP "hp.xml"
    & adb -s $Serial pull /sdcard/hp.xml $local | Out-Null
    return (Get-Content $local -Raw)
}

function Menu-Open { return ((Dump-Text) -match 'text="1\.0x"') }

# Dismiss with a scrim tap, NOT with KEYCODE_BACK: back collapses/exits the player and then
# every remaining probe point lands on whatever the home page happens to have there (that is
# what produced a fake "hollow square" the first time this ran).
function Ensure-Closed {
    for ($i = 0; $i -lt 4; $i++) {
        if (-not (Menu-Open)) { return $true }
        Sh "input tap 1000 300" | Out-Null
        Start-Sleep -Milliseconds 700
    }
    return -not (Menu-Open)
}

# The fullscreen player is the only valid starting state: it is the one that owns the node.
function Ensure-Player() {
    for ($i = 0; $i -lt 3; $i++) {
        $txt = Dump-Text
        # single-quoted pieces: PowerShell does not treat \" as an escape inside "..."
        if ($txt -match ('text="[^"]*(' + $PlayerNeedle + ')')) { return $true }
        Sh "input swipe 1000 2500 1000 900 300" | Out-Null
        Start-Sleep -Milliseconds 1500
    }
    return $false
}

$mx = [int](($L + $R) / 2)
$my = [int](($T + $B) / 2)
# Each element needs its own parentheses: inside @() the comma binds tighter than +,
# so "@($L + 8, $mx - 8)" parses as "$L + @(8, $mx, ...)" and blows up.
$xs = @(($L + 8), ($mx - 8), ($mx + 8), ($R - 8))
$ys = @(($T + 8), ($my - 8), ($my + 8), ($B - 8))

"probing node [$L,$T]-[$R,$B]"
foreach ($y in $ys) {
    $row = @()
    foreach ($x in $xs) {
        if (-not (Ensure-Player)) { $row += "NOPLAYER"; continue }
        if (-not (Ensure-Closed)) { $row += "ERR"; continue }
        Sh "input tap $([int]$x) $([int]$y)" | Out-Null
        Start-Sleep -Milliseconds 800
        if (Menu-Open) { $row += "HIT" } else { $row += "---" }
        Ensure-Closed | Out-Null
    }
    "y=$([int]$y)  " + ($row -join "  ")
}
