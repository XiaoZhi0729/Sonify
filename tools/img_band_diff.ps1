# Compare a screen band between two PNGs: mean absolute per-channel difference and mean luminance.
#
# Why a band and not the whole frame: the point under test is whether the bottom bar's MATERIAL
# changed, and the rest of the screen (artwork, list content) legitimately differs between captures.
#
#   powershell -File tools\img_band_diff.ps1 -A a.png -B b.png -Y 2270 -H 230
#
# ASCII only (PowerShell 5.1 misreads UTF-8-without-BOM as ANSI).
param(
    [Parameter(Mandatory = $true)][string]$PathA,
    [Parameter(Mandatory = $true)][string]$PathB,
    [int]$Y = -1,
    [int]$H = -1,
    [double]$ScaleY = 0.0   # fallback: band starts at Height*ScaleY when Y is not given
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Read-Band([string]$path) {
    $img = [System.Drawing.Image]::FromFile((Resolve-Path $path).Path)
    $bmp = New-Object System.Drawing.Bitmap($img.Width, $img.Height)
    $gr = [System.Drawing.Graphics]::FromImage($bmp)
    $gr.DrawImage($img, 0, 0, $img.Width, $img.Height)   # forces 32bpp ARGB regardless of source format
    $gr.Dispose(); $img.Dispose()
    return $bmp
}

# Parameter names must not differ from a local only by case: PowerShell variables are
# case-insensitive, so "$a = Load $A" overwrites the path itself and the result comes back wrong.
$bmpA = Read-Band $PathA
$bmpB = Read-Band $PathB
if ($bmpA -isnot [System.Drawing.Bitmap]) { throw "load returned $($bmpA.GetType().Name), not a Bitmap" }
"image A: $($bmpA.Width)x$($bmpA.Height)   image B: $($bmpB.Width)x$($bmpB.Height)"
if ($bmpA.Width -ne $bmpB.Width -or $bmpA.Height -ne $bmpB.Height) { throw "different sizes, cannot compare" }

if ($Y -lt 0) { $Y = [int]($bmpA.Height * $ScaleY) }
if ($H -lt 0) { $H = $bmpA.Height - $Y }
$H = [Math]::Min($H, $bmpA.Height - $Y)

$step = 2   # sample every 2nd px; the band is ~300k px, full scan doubles runtime for nothing
$sumDiff = 0.0; $n = 0; $lumA = 0.0; $lumB = 0.0
# Loop variables must not read as the band parameters: PowerShell names are case-insensitive, so
# "$y" IS "$Y", which turns the loop bound `$y -lt ($Y + $H)` into an identity and runs off the image.
for ($row = $Y; $row -lt ($Y + $H); $row += $step) {
    for ($col = 0; $col -lt $bmpA.Width; $col += $step) {
        $pa = $bmpA.GetPixel($col, $row); $pb = $bmpB.GetPixel($col, $row)
        $sumDiff += [Math]::Abs($pa.R - $pb.R) + [Math]::Abs($pa.G - $pb.G) + [Math]::Abs($pa.B - $pb.B)
        $lumA += 0.299 * $pa.R + 0.587 * $pa.G + 0.114 * $pa.B
        $lumB += 0.299 * $pb.R + 0.587 * $pb.G + 0.114 * $pb.B
        $n++
    }
}
$bmpA.Dispose(); $bmpB.Dispose()

"{0,-14} band y={1}..{2}  samples={3}" -f '', $Y, ($Y + $H), $n
"{0,-14} mean |A-B| per channel = {1,6:N2}   (0 = identical)" -f 'diff', ($sumDiff / $n / 3)
"{0,-14} mean luminance  A={1,6:N1}  B={2,6:N1}" -f 'brightness', ($lumA / $n), ($lumB / $n)
