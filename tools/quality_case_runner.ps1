# On-device regression runner for the audio-quality panel.
#
# Usage:
#   powershell -File tools\quality_case_runner.ps1 -Serial 192.168.1.5:35137
#   powershell -File tools\quality_case_runner.ps1        # first attached device
#
# Preconditions: adb on PATH, device unlocked, app installed and playing an ONLINE song.
# Exit code: 0 = all pass, 1 = some FAIL, 2 = environment not usable.
#
# Assertions rely on two observables only:
#   1) uiautomator node text + the `enabled` attribute of the nearest CLICKABLE ancestor (that is
#      where Compose puts clickable(enabled=false); the child Text node keeps saying enabled=true,
#      see Get-TextNodes);
#   2) the line count of `adb logcat -d -s QualityTrace:V` before/after an action
#      (tapping a greyed row must produce zero new trace events).
# NOTE: every tap target must come from a dump taken IMMEDIATELY before that tap - see C3.
# No hardcoded coordinates, rotation-agnostic, ASCII-only (PowerShell 5.1 reads UTF-8
# without BOM as ANSI, which breaks quoted Chinese literals).

param(
    [string]$Serial = "",
    [int]$SettleMs = 2500,
    [string]$Pkg = "yos.music.player.oss",
    # Dot-source the helpers without running the cases: ". tools\quality_case_runner.ps1 -HelpersOnly".
    # Ad-hoc work used to carve the header out with Substring and Invoke-Expression it - but that
    # header contains the param() block, so executing it silently reset $Serial to "" and every adb
    # call lost its -s flag ("more than one device" as soon as a second device is attached).
    [switch]$HelpersOnly,
    # C2 needs a song whose top tier is NOT deliverable to have anything greyed to tap. On an
    # account whose library mostly does have Hi-Res that never happens by luck, so the runner walks
    # forward up to this many songs asking for the top tier until the evidence appears.
    [int]$HuntSongs = 3
)

$ErrorActionPreference = "Stop"
$TierNeedles = @("128K", "320K", "FLAC", "Hi-Res")
$results = New-Object System.Collections.ArrayList

function Add-Result($id, $name, $status, $detail) {
    [void]$results.Add([pscustomobject]@{ Id = $id; Case = $name; Status = $status; Detail = $detail })
}

function Sh($cmd) {
    if ($Serial) { return & adb -s $Serial shell $cmd }
    return & adb shell $cmd
}

function Dump-Xml($tag) {
    Sh "uiautomator dump /sdcard/qc_$tag.xml >/dev/null" | Out-Null
    $local = Join-Path $env:TEMP "qc_$tag.xml"
    if ($Serial) { & adb -s $Serial pull "/sdcard/qc_$tag.xml" $local | Out-Null }
    else { & adb pull "/sdcard/qc_$tag.xml" $local | Out-Null }
    return (Get-Content $local -Raw)
}

function Get-TextNodes($xml) {
    # `enabled` is read off the nearest clickable ANCESTOR, never off the text node itself.
    # LiquidDropdownRow puts clickable(enabled=...) on the row container and Compose does not push
    # that flag down into the child Text semantics node, so a greyed tier still reports
    # enabled="true" on its TextView. Reading the wrong node made C3 aim at the topmost GREYED row
    # (which swallows taps by design), and C7 then reported "selecting a row does not close the
    # panel" - a false FAIL; C2/C9 could never see a greyed row either. Verified on the phone with
    # a song capped at 320K: TextView enabled=true on all four rows, row ancestors
    # enabled=true/true/false/false.
    $doc = New-Object System.Xml.XmlDocument
    $doc.LoadXml($xml)
    $out = New-Object System.Collections.ArrayList
    foreach ($n in $doc.SelectNodes("//node")) {
        $t = $n.GetAttribute("text")
        if (-not $t) { continue }
        $b = [regex]::Match($n.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $b.Success) { continue }
        $l = [int]$b.Groups[1].Value; $tp = [int]$b.Groups[2].Value
        $r = [int]$b.Groups[3].Value; $bt = [int]$b.Groups[4].Value
        $en = $n.GetAttribute("enabled")
        $p = $n.parentNode
        while ($null -ne $p -and $p.NodeType -eq "Element") {
            if ($p.GetAttribute("clickable") -eq "true") { $en = $p.GetAttribute("enabled"); break }
            $p = $p.parentNode
        }
        [void]$out.Add([pscustomobject]@{
            Text    = $t; Enabled = ($en -eq "true")
            Left    = $l; Top = $tp
            X       = ($l + $r) / 2
            Y       = ($tp + $bt) / 2
            W       = $r - $l
        })
    }
    return $out
}

function Find-TierNodes($xml) {
    Get-TextNodes $xml | Where-Object {
        $n = $_
        ($TierNeedles | Where-Object { $n.Text -like "*$_*" }).Count -gt 0
    }
}

# The four panel rows are the tier labels that share one left edge (they are laid out in a
# Column, so their text nodes start at the same x). The capsule is the odd one out - it is
# centred somewhere else. Width cannot tell them apart and neither can Y (the first row sits
# level with the capsule behind the panel), so group by Left and keep the biggest group.
function Split-PanelRows($nodes) {
    $tier = @($nodes | Where-Object {
            $n = $_
            ($TierNeedles | Where-Object { $n.Text -like "*$_*" }).Count -gt 0
        })
    $group = @($tier | Group-Object Left | Sort-Object Count -Descending | Select-Object -First 1)
    if (-not $group) { return @{ Rows = @(); Capsule = $null } }
    $rows = @($group[0].Group | Sort-Object Top)
    $others = @($tier | Where-Object { $rows -notcontains $_ })
    $capsule = if ($others.Count -gt 0) { $others[0] } else { $null }
    return @{ Rows = $rows; Capsule = $capsule }
}

function Format-Rows($rows) {
    ($rows | ForEach-Object { "'" + $_.Text + "' L=" + $_.Left + " T=" + $_.Top + " w=" + $_.W + " en=" + $_.Enabled }) -join " ; "
}

# Count tier rows still sitting on the panel's own left edge inside the panel's vertical band.
# Needed because the capsule is also a tier label: after the panel closes, "one tier node left"
# is the capsule, not a still-open panel.
function Count-RowsAt($xml, $left, $topLo, $topHi) {
    return @(Find-TierNodes $xml | Where-Object {
            [Math]::Abs($_.Left - $left) -le 8 -and $_.Top -gt $topLo -and $_.Top -lt $topHi
        }).Count
}

# With the panel closed there is normally a single tier label (the capsule). If more than one
# Left-group exists, the biggest one is the panel and the capsule is the leftover node.
function Get-Capsule($tierNodes) {
    # Force array: a one-element result comes back as a bare object, whose .Count is $null,
    # which would make the comparison below take the wrong branch and return $null.
    $rows = @(@(Split-PanelRows $tierNodes).Rows)
    $leftover = @($tierNodes | Where-Object { $rows -notcontains $_ })
    if ($leftover.Count -gt 0) {
        return $leftover | Sort-Object W | Select-Object -First 1
    }
    # Nothing outside the panel's own left-group, yet a full panel is present: the capsule is
    # simply not in this dump (occluded by the sheet). Returning a ROW here once made a greyed
    # "Hi-Res" row masquerade as the reading, and a wrong conclusion followed. Say "unknown".
    if ($rows.Count -ge 3) { return $null }
    return @($tierNodes | Sort-Object W) | Select-Object -First 1
}

function Tap($node) { Sh "input tap $([int]$node.X) $([int]$node.Y)" | Out-Null }

# Dismiss an overlay (bottom sheet / dropdown) by tapping its scrim. y=800 is deliberate: the
# fullscreen player's own drag handle sits at the very top, and tapping there to "close" a sheet
# collapses the player too - which then makes every later case read an empty screen while still
# reporting the sheet as dismissed. Re-expand if that ever happens anyway.
function Dismiss-Overlay($x) {
    Sh "input tap $([int]$x) 800" | Out-Null
    Start-Sleep -Milliseconds 900
    if (-not (Get-Capsule (Find-TierNodes (Dump-Xml "after_dismiss")))) {
        Write-Host "player collapsed while dismissing an overlay - re-expanding"
        Expand-Player "recover"
    }
}

# Clickable nodes only (geometry). Needed because the title-row buttons carry no content-desc, so
# they can only be found by shape: two same-height small clickables hugging the right edge.
function Get-ClickableNodes($xml) {
    [regex]::Matches($xml, '<node[^>]*?clickable="true"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') |
        ForEach-Object {
            $g = $_.Groups
            [pscustomobject]@{
                Left = [int]$g[1].Value; Top = [int]$g[2].Value
                Right = [int]$g[3].Value; Bottom = [int]$g[4].Value
                X = ([int]$g[1].Value + [int]$g[3].Value) / 2
                Y = ([int]$g[2].Value + [int]$g[4].Value) / 2
            }
        }
}

function Tap-Desc($needle) {
    $x = Dump-Xml "desc"
    $pat = 'content-desc="[^"]*' + [regex]::Escape($needle) + '[^"]*"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Matches($x, $pat)
    if ($m.Count -eq 0) { return $false }
    $g = $m[0].Groups
    Sh "input tap $((([int]$g[1].Value + [int]$g[3].Value) / 2)) $((([int]$g[2].Value + [int]$g[4].Value) / 2))" | Out-Null
    return $true
}

function Trace-Lines() {
    if ($Serial) { $out = & adb -s $Serial logcat -d -s "QualityTrace:V" 2>$null }
    else { $out = & adb logcat -d -s "QualityTrace:V" 2>$null }
    return @($out | Where-Object { $_ -match "QualityTrace" }).Count
}

# Does the installed build still emit logs? Release runs R8 with
# `-assumenosideeffects class android.util.Log` (app/proguard-rules.pro), which deletes every
# log call. There "no new QualityTrace line" is not evidence of anything, so a case that PASSES
# on a zero delta (C2) would be a false green and a case that FAILS on it (C3) a false alarm.
function Get-Debuggable() {
    if ($null -eq $script:debuggable) {
        if ($Serial) { $info = & adb -s $Serial shell "dumpsys package $Pkg" 2>$null }
        else { $info = & adb shell "dumpsys package $Pkg" 2>$null }
        $script:debuggable = ((@($info) | Out-String) -match "DEBUGGABLE")
    }
    return $script:debuggable
}

# Authoritative play state. Cannot take the first PlaybackState in the dump: the session list
# order changes (another player's session came first once and made every case skip), so the
# lookup is scoped to the line block that belongs to $Pkg.
function Get-PlayState() {
    if ($Serial) { $o = & adb -s $Serial shell "dumpsys media_session $Pkg" 2>$null }
    else { $o = & adb shell "dumpsys media_session $Pkg" 2>$null }
    $lines = @($o)
    $from = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match ('package=' + [regex]::Escape($Pkg))) { $from = $i; break }
    }
    if ($from -lt 0) { return "" }
    for ($i = $from; $i -lt $lines.Count; $i++) {
        $m = [regex]::Match($lines[$i], 'state=PlaybackState \{state=([A-Z]+)')
        if ($m.Success) { return $m.Groups[1].Value }
    }
    return ""
}

# Never drive whatever happens to be on screen. A stray BACK (or a crash, or the user switching
# apps) leaves our activity, and every tap after that goes into someone else's app - which both
# corrupts the results and is plain rude. Abort loudly instead.
function Assert-Foreground($where) {
    if ($Serial) { $o = & adb -s $Serial shell "dumpsys window" 2>$null }
    else { $o = & adb shell "dumpsys window" 2>$null }
    $m = [regex]::Match((@($o) | Out-String), 'mCurrentFocus=Window\{\S+ u0 ([^/]+)/')
    $owner = $m.Groups[1].Value
    if ($owner -ne $Pkg) {
        Write-Host ("ABORT at $where : foreground is '$owner', not $Pkg - refusing to tap into " +
                   "another app. Re-run once the player is on screen.")
        exit 2
    }
}

# Identity probe for "is it still the same song?". Width alone is NOT deterministic: measured on
# the phone, the title and the artist line were both 795px wide, and a tie lets Sort-Object return
# whichever node the layout walk produced first - so a normal quality switch got reported as a song
# skip (C3 FAIL). Tie-breaking by Top (the title sits above the artist) pins one node; what matters
# is that before/after always resolve to the SAME node, not that it is literally the title.
function Current-Song($xml) {
    @(Get-TextNodes $xml | Where-Object {
        $n = $_; ($TierNeedles | Where-Object { $n.Text -like "*$_*" }).Count -eq 0
    } | Sort-Object @{Expression = { $_.W }; Descending = $true },
                    @{Expression = { $_.Top }; Descending = $false }) |
        Select-Object -First 1 | ForEach-Object { $_.Text }
}

function Expand-Player($skip) {
    # The collapsed shell occupies the bottom of the screen and reacts to an upward drag
    # anywhere on it - far more robust than hunting for a specific label.
    $h = Dump-Xml "mini$skip"
    $nodes = @(Get-TextNodes $h)
    if ($nodes.Count -eq 0) { return }
    $maxX = ($nodes | Measure-Object -Property { ([int]($_.X) * 2) } -Maximum).Maximum
    $maxY = ($nodes | Measure-Object -Property Y -Maximum).Maximum
    $cx = [int]($maxX / 2)
    if ($cx -lt 100) { $cx = 500 }
    $y1 = [int]($maxY * 0.9)
    $y2 = [int]($maxY * 0.25)
    Sh "input swipe $cx $y1 $cx $y2 300" | Out-Null
    Start-Sleep -Milliseconds $SettleMs
}

# Sourcing mode: helpers only, run nothing. Everything below drives a device.
if ($HelpersOnly) { return }

# ---------- environment ----------
if (-not $Serial) {
    $candidates = @((& adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } |
            ForEach-Object { ($_ -split "\s+")[0] })
    if ($candidates.Count -eq 0) { Write-Host "NO DEVICE"; exit 2 }
    # Guessing here is how a suite ends up running against the wrong screen and reporting green.
    if ($candidates.Count -gt 1) {
        Write-Host ("AMBIGUOUS DEVICE - pass -Serial, attached: " + ($candidates -join ", "))
        exit 2
    }
    $Serial = $candidates[0]
}
Write-Host "device = $Serial"
Write-Host ("build = " + $(if (Get-Debuggable) { "debuggable (logs available)" } else { "release (Log.d stripped by R8 - trace not evidence)" }))

Sh "monkey -p $Pkg -c android.intent.category.LAUNCHER 1" | Out-Null
Start-Sleep -Milliseconds 9000
Expand-Player "a"

$player = Dump-Xml "player"
$tiers = @(Find-TierNodes $player)
if ($tiers.Count -eq 0) {
    Expand-Player "b"
    $player = Dump-Xml "player2"
    $tiers = @(Find-TierNodes $player)
}
if ($tiers.Count -eq 0) {
    $seen = (@(Get-TextNodes $player) | Select-Object -First 14 | ForEach-Object { $_.Text }) -join " | "
    Write-Host "SKIP: quality capsule not found - play an online song first. visible: $seen"
    exit 2
}
$capsule = Get-Capsule $tiers
Assert-Foreground "before the cases"
# Normalise the start state: a dropdown left open by an earlier session makes every later case
# read the wrong nodes (it already cost a false C1/C8 FAIL once).
if (@(@(Split-PanelRows $tiers).Rows).Count -ge 3) {
    Write-Host "panel was already open - closing it before starting"
    Dismiss-Overlay ([int]($capsule.Left / 2))
    $player = Dump-Xml "player3"
    $tiers = @(Find-TierNodes $player)
    $capsule = Get-Capsule $tiers
}
$songBefore = Current-Song $player
Write-Host "capsule = '$($capsule.Text)'  song = '$songBefore'"
Write-Host "tier nodes: $(Format-Rows $tiers)"

# ---------- C4: capsule shows a real tier ----------
$capOk = ($TierNeedles | Where-Object { $capsule.Text -like "*$_*" }).Count -gt 0
Add-Result "C4" "capsule shows a valid tier" $(if ($capOk) { "PASS" } else { "FAIL" }) $capsule.Text

# ---------- C8: the "more" button reacts at its own centre ----------
# Pins a real regression: the lyric-translation button in the control band used to sit exactly on
# top of this button's centre (Album column fillMaxHeight(0.595f) + control band 0.437f = 1.032,
# so the two bands overlap by ~80px), leaving only a ring around the icon tappable - which reads to
# the user as "I have to hit the top-right corner". The menu is closed again so C1 starts from the
# state a user actually sees.
$small = @(Get-ClickableNodes $player | Where-Object {
        ((($_.Right - $_.Left) -ge 60) -and (($_.Right - $_.Left) -le 260))
    })
$peers = @($small | Group-Object { [int]($_.Top / 20) } | Where-Object { $_.Count -ge 2 })
$more = @($peers | ForEach-Object { $_.Group } | Sort-Object Right -Descending) |
    Select-Object -First 1
if (-not $more) {
    Add-Result "C8" "more button reacts at its centre" "SKIP" "no title-row button pair (controls hidden?)"
}
else {
    Sh "input tap $([int]$more.X) $([int]$more.Y)" | Out-Null
    Start-Sleep -Milliseconds 1600
    # "1.0x" is the playback-speed row the root menu always shows; ASCII on purpose. The sheet
    # animates in, so one retry - a too-early dump once reported the button as dead right before
    # a case that opened the very same sheet succeeded.
    $opened = ((Dump-Xml "c8_open") -match 'text="1\.0x"')
    if (-not $opened) {
        Start-Sleep -Milliseconds 1500
        $opened = ((Dump-Xml "c8_open2") -match 'text="1\.0x"')
    }
    Dismiss-Overlay ([int]($more.Left / 2))
    $afterC8 = Dump-Xml "c8_closed"
    $closed = -not (($afterC8) -match 'text="1\.0x"')
    # "gone" is only a real dismissal while the player is still on screen: if the whole page
    # collapsed, the token disappeared for the wrong reason and the case must not pass.
    $stillThere = [bool](Get-Capsule (Find-TierNodes $afterC8))
    Add-Result "C8" "more button reacts at its centre" $(if ($opened -and $closed -and $stillThere) { "PASS" } else { "FAIL" }) (
        "tapped ($([int]$more.X),$([int]$more.Y)) opened=$opened dismissed=$closed playerVisible=$stillThere")
}

# ---------- C1: panel contains exactly the four tier rows ----------
# Pause first: a track that reaches its end mid-run advances on its own, which would look
# exactly like a quality-switch skip. Paused playback cannot auto-advance, so the title
# comparison below is unambiguous. Also keeps the lyric line from changing between dumps.
# The play/pause button's content-desc names the ACTION it performs ("Pause" while playing,
# "Play" while paused), so the media session decides which label to look for and "Pause"
# below really means "make it paused". We only resume what we paused ourselves.
$paused = $false
$needResume = $false
$st0 = Get-PlayState
if ($st0 -eq "PLAYING") {
    Tap-Desc "Pause"
    Start-Sleep -Milliseconds 900
    if ((Get-PlayState) -ne "PLAYING") { $paused = $true; $needResume = $true }
} elseif ($st0 -eq "PAUSED") {
    $paused = $true
}
Tap $capsule
Start-Sleep -Milliseconds 1500
$sheet = Dump-Xml "sheet"
$panel = Split-PanelRows (Find-TierNodes $sheet)
$sheetNodes = @($panel.Rows)
# Any other text line that starts at the rows' own left edge would be leftover explanation
# copy (section header / conclusion line). Chips are right-aligned, so they don't count.
$distinct = @($sheetNodes | ForEach-Object {
        $row = $_
        ($TierNeedles | Where-Object { $row.Text -like "*$_*" }) | Select-Object -First 1
    } | Select-Object -Unique)
$noise = @()
if ($sheetNodes.Count -gt 0) {
    $left = $sheetNodes[0].Left
    $noise = @(Get-TextNodes $sheet | Where-Object {
            $n = $_
            $n.Top -gt ($sheetNodes[0].Top - 120) -and $n.Top -lt ($sheetNodes[-1].Top + 120) -and
            [Math]::Abs($n.Left - $left) -le 8 -and
            ($TierNeedles | Where-Object { $n.Text -like "*$_*" }).Count -eq 0 -and
            $n.Text -notlike "*Bluetooth*"
        })
}
$c1 = ($distinct.Count -eq 4) -and ($noise.Count -eq 0)
Add-Result "C1" "panel has exactly 4 tier rows and no extra text" $(if ($c1) { "PASS" } else { "FAIL" }) `n    "rows=$($sheetNodes.Count) distinct=$($distinct.Count) noise=$($noise.Count) [$(Format-Rows $sheetNodes)] noiseText=$(@($noise | ForEach-Object { $_.Text }) -join ',')"

# Band geometry, reused by C7 below.
$panelLeft = if ($sheetNodes.Count -gt 0) { $sheetNodes[0].Left } else { -999 }
$bandLo = if ($sheetNodes.Count -gt 0) { $sheetNodes[0].Top - 120 } else { 0 }
$bandHi = if ($sheetNodes.Count -gt 0) { $sheetNodes[-1].Top + 120 } else { 0 }

# NOTE: C2 (greyed row ignores taps) deliberately runs AFTER C3, not here. The greyed state is
# created by C3's own request for a tier this song cannot get, so it can only be observed after
# that tap - checking for it before is what made C2 a permanent SKIP on this account.

# ---------- C3: tapping an enabled row switches once without skipping the song ----------
# Ask for the HIGHEST selectable tier (rows are laid out low -> high, so the last enabled row is
# the top one). Asking the lowest can never produce "proven unavailable" evidence, which is why
# C2 used to SKIP forever; asking the top one either delivers it (reading moves) or gets capped
# (row greys) - and both outcomes are observable.
# Re-read the panel immediately before tapping. Opening the sheet kicks off the tier probe, and
# when its conclusions land the greyed rows gain a right-aligned "no source" chip: the column is
# measured on the widest row, so the panel WIDENS and every row slides sideways (centred anchor)
# or shifts vertically (top-anchored + reveal clip, which grows the panel downward). A tap
# computed from C1's dump then lands on whatever row moved into that spot - on the phone that was
# a greyed row, which swallows the tap: C7 then read "select does not close the panel" (FAIL)
# while C3 counted the occluded "?" reading as a tier move (false green).
$prePanel = @(Split-PanelRows (Find-TierNodes (Dump-Xml "c3_pretap")))
if (@($prePanel.Rows).Count -ge 2) {
    if (@($prePanel.Rows).Count -ne $sheetNodes.Count -or $prePanel.Rows[0].Left -ne $panelLeft) {
        Write-Host "C3: panel moved while it was open (chips landed) - re-reading row geometry"
    }
    $sheetNodes = @($prePanel.Rows)
    $panelLeft = $sheetNodes[0].Left
    $bandLo = $sheetNodes[0].Top - 120
    $bandHi = $sheetNodes[-1].Top + 120
}
$askable = @($sheetNodes | Where-Object { $_.Enabled })
$enabled = $(if ($askable.Count -gt 0) { @($askable[-1]) } else { @() })
if ($enabled.Count -eq 0) {
    Add-Result "C3" "enabled row switches once, no skip" "SKIP" "no tappable tier"
} elseif (-not $paused) {
    Add-Result "C3" "enabled row switches once, no skip" "SKIP" "no play/pause node - cannot tell a skip from a natural track end"
} else {
    $targetText = $enabled[0].Text
    $before = Trace-Lines
    Tap $enabled[0]
    Start-Sleep -Milliseconds 1800
    $after = Trace-Lines
    $p2 = Dump-Xml "after_switch"
    $songAfter = Current-Song $p2
    $delta = $after - $before
    # The panel closes on select (that is C7), so the reading is readable again here.
    $capAfterNode = Get-Capsule (Find-TierNodes (Dump-Xml "c3_capsule"))
    $capAfterText = $(if ($capAfterNode) { $capAfterNode.Text } else { "?" })
    $noSkip = ($songAfter -eq $songBefore)

    # ---------- C7: selecting a row closes the panel ----------
    # A single-choice menu that stays on screen reads as "nothing happened"; it is also how
    # the pre-refactor widget behaved, so closing on select is the pinned contract.
    $stillOpen = Count-RowsAt $p2 $panelLeft $bandLo $bandHi
    Add-Result "C7" "selecting a row dismisses the panel" $(if ($stillOpen -eq 0) { "PASS" } else { "FAIL" }) "rows still visible=$stillOpen"

    # Re-open the panel: this ask is what CREATES the "proven unavailable" evidence, so the
    # greyed rows can only be read now - and the asked row's state is also what tells an honest
    # "capped" result (reading stays, row greys) apart from a button that did nothing at all.
    if ($capAfterNode) { Tap $capAfterNode | Out-Null }
    Start-Sleep -Milliseconds 1500
    $rows2 = @(@(Split-PanelRows (Find-TierNodes (Dump-Xml "c3_reopen"))).Rows)
    $askedRow = @($rows2 | Where-Object { $_.Text -eq $targetText }) | Select-Object -First 1
    $askedGreyed = [bool]($askedRow -and (-not $askedRow.Enabled))
    $moved = ($null -ne $capAfterNode) -and (($capAfterText -ne $capsule.Text) -or ($capAfterText -eq $targetText))
    # Do NOT infer "nothing should happen" from the reading already naming the top tier: the
    # reading can sit at Hi-Res through cache stickiness while the intent is still 128, and then a
    # reload is correct. "Same intent -> no reload" is a pure-function rule, pinned by
    # QualitySwitchPolicyTest; here any real effect (moved / capped-grey / trace activity) passes.
    $effect = $moved -or $askedGreyed -or ((Get-Debuggable) -and ($delta -ge 1))
    Add-Result "C3" "enabled row switches once, no skip" $(if ($noSkip -and $effect) { "PASS" } else { "FAIL" }) (
        "tier '$($capsule.Text)' -> '$capAfterText' (asked '$targetText') greyedNow=$askedGreyed song unchanged=$noSkip trace delta=$delta")

    # ---------- C2: tapping a greyed row does nothing ----------
    $panelLeft2 = if ($rows2.Count -gt 0) { $rows2[0].Left } else { -999 }
    $bandLo2 = if ($rows2.Count -gt 0) { $rows2[0].Top - 120 } else { 0 }
    $bandHi2 = if ($rows2.Count -gt 0) { $rows2[-1].Top + 120 } else { 0 }
    $disabled = @($rows2 | Where-Object { -not $_.Enabled })
    $hunted = 0
    while ($disabled.Count -eq 0 -and $hunted -lt $HuntSongs) {
        $hunted++
        Write-Host ("C2: this song delivers the top tier - advancing to song {0}/{1}" -f $hunted, $HuntSongs)
        Tap-Desc "Next" | Out-Null
        Start-Sleep -Seconds 6
        $hp = Dump-Xml "hunt$hunted"
        $hcap = Get-Capsule (Find-TierNodes $hp)
        if (-not $hcap) { continue }
        Tap $hcap | Out-Null
        Start-Sleep -Milliseconds 1500
        $hrows = @(@(Split-PanelRows (Find-TierNodes (Dump-Xml "huntp$hunted"))).Rows)
        $htop = @($hrows | Where-Object { $_.Enabled }) | Select-Object -Last 1
        if ($htop) {
            Tap $htop | Out-Null
            Start-Sleep -Seconds 7
        }
        # Selecting a row closes the panel, so reopen it to read the enabled flags.
        $hcap2 = Get-Capsule (Find-TierNodes (Dump-Xml "huntcap$hunted"))
        if ($hcap2) {
            Tap $hcap2 | Out-Null
            Start-Sleep -Milliseconds 1500
            $hrows = @(@(Split-PanelRows (Find-TierNodes (Dump-Xml "huntopen$hunted"))).Rows)
        }
        $rows2 = $hrows
        $disabled = @($rows2 | Where-Object { -not $_.Enabled })
        if ($disabled.Count -gt 0) {
            # Keep every later case honest about which song it is now looking at: the baseline
            # reading and the title must follow the hunt, or the C5 / cleanup guards compare
            # against a song that is no longer loaded.
            Dismiss-Overlay ([int]($rows2[0].Left / 2))
            $hb = Dump-Xml "huntbase$hunted"
            $hbase = Get-Capsule (Find-TierNodes $hb)
            if ($hbase) {
                $capsule = $hbase
                $songBefore = Current-Song $hb
                Tap $capsule | Out-Null
                Start-Sleep -Milliseconds 1500
                $rows2 = @(@(Split-PanelRows (Find-TierNodes (Dump-Xml "huntre$hunted"))).Rows)
                $disabled = @($rows2 | Where-Object { -not $_.Enabled })
            }
        }
    }
    if ($disabled.Count -eq 0) {
        Add-Result "C2" "greyed row ignores taps" "SKIP" ("{0} songs all delivered the top tier" -f ($hunted + 1))
    }
    else {
        $d0 = $disabled[0]
        $capBefore = $capAfterText
        $beforeC2 = Trace-Lines
        Tap $d0
        Start-Sleep -Milliseconds 2500
        $afterC2 = Trace-Lines
        $pC2 = Dump-Xml "after_disabled"
        $rowsNow = Count-RowsAt $pC2 $panelLeft2 $bandLo2 $bandHi2
        $capAfter = (Get-Capsule (Find-TierNodes $pC2)).Text
        $songNow = Current-Song $pC2
        # The panel must also stay put: a greyed row that dismisses it would read as a tap that
        # was accepted but silently did nothing. UI evidence holds on every build type - a tap
        # that had been accepted would move the reading, close the panel, or change the song.
        $okC2 = ($rowsNow -eq $rows2.Count) -and ($capAfter -eq $capBefore) -and ($songNow -eq $songBefore)
        if ((Get-Debuggable) -and ($afterC2 -ne $beforeC2)) { $okC2 = $false }
        $traceNote = $(if (Get-Debuggable) { "trace delta=$($afterC2 - $beforeC2)" } else { "trace n/a (stripped)" })
        Add-Result "C2" "greyed row ignores taps ($($d0.Text))" $(if ($okC2) { "PASS" } else { "FAIL" }) "rows=$rowsNow/$($rows2.Count) tier '$capBefore' -> '$capAfter' $traceNote"
    }
    # ---------- C9: nothing above the playing tier is left "tap to find out" ----------
    # The reported bug: a song capped at 320K still offered 无损 FLAC as tappable; tapping it
    # silently came back at 320K and only THEN did the panel mark 无损 unavailable. The resolve
    # chain has already probed every tier on the way down, so every tier above what is actually
    # playing must be greyed the first time the panel opens.
    $needleRank = @{}
    for ($i = 0; $i -lt $TierNeedles.Count; $i++) { $needleRank[$TierNeedles[$i]] = $i }
    $playingRank = -1
    foreach ($n in $TierNeedles) { if ($capAfterText -like "*$n*") { $playingRank = $needleRank[$n] } }
    if ($hunted -gt 0) {
        Add-Result "C9" "tiers above the playing one are pre-marked" "SKIP" "song changed during the C2 hunt"
    }
    elseif ($playingRank -lt 0 -or $rows2.Count -lt 4) {
        Add-Result "C9" "tiers above the playing one are pre-marked" "SKIP" "reading '$capAfterText' not recognised"
    }
    else {
        $stale = @($rows2 | Where-Object {
                $r = $_
                $rank = -1
                foreach ($n in $TierNeedles) { if ($r.Text -like "*$n*") { $rank = $needleRank[$n] } }
                ($rank -gt $playingRank) -and $r.Enabled
            })
        Add-Result "C9" "tiers above the playing one are pre-marked" $(if ($stale.Count -eq 0) { "PASS" } else { "FAIL" }) (
            "playing='$capAfterText' still-clickable-above=" + (@($stale | ForEach-Object { $_.Text }) -join ','))
    }

    # Leave the panel closed for whatever runs next.
    if ($capAfterNode) { Dismiss-Overlay ([int]($capAfterNode.Left / 2)) }

    # ---------- C6: capability table persisted ----------
    # run-as only works on debuggable builds, so on the release-signed package this must be a
    # SKIP (environment limitation) and not a FAIL - a FAIL reads as "persistence is broken"
    # when nothing in the app is wrong.
    if (-not (Get-Debuggable)) {
        Add-Result "C6" "capability table persisted in MMKV" "SKIP" "not a debuggable build"
    }
    else {
        if ($Serial) { $kv = & adb -s $Serial shell "run-as $Pkg sh -c 'strings files/mmkv/yos_data_normal | grep -c quality_capability_'" 2>$null }
        else { $kv = & adb shell "run-as $Pkg sh -c 'strings files/mmkv/yos_data_normal | grep -c quality_capability_'" 2>$null }
        $n = 0
        [void][int]::TryParse(($kv | Out-String).Trim(), [ref]$n)
        Add-Result "C6" "capability table persisted in MMKV" $(if ($n -ge 1) { "PASS" } else { "FAIL" }) "keys=$n"
    }
}

# ---------- C5: re-opening the same song keeps the same reading ----------
# Next -> Previous forces a fresh open (urlCache hit path). Meaningful only when we land
# back on the same song, hence the title guard; otherwise SKIP rather than report a false FAIL.
# Stay in the fullscreen player: a back press leaves the player entirely (the mini bar carries
# no quality capsule), which used to turn this case into a SKIP.
# Resume first: a paused player does not resolve the next track, so C5 would be meaningless.
if ((Get-PlayState) -ne "PLAYING") { Tap-Desc "Play" | Out-Null; Start-Sleep -Milliseconds 1200 }
$pre = Dump-Xml "pre_c5"
$capsuleAtStartNode = Get-Capsule (Find-TierNodes $pre)
if (-not $capsuleAtStartNode) {
    # The collapsed shell animates in; one retry keeps a slow frame from skipping the case.
    Start-Sleep -Milliseconds 1500
    $pre = Dump-Xml "pre_c5b"
    $capsuleAtStartNode = Get-Capsule (Find-TierNodes $pre)
}
$songAtStart = Current-Song $pre
if (-not $capsuleAtStartNode) {
    Add-Result "C5" "second open keeps the same reading" "SKIP" "capsule gone before re-open"
}
elseif (-not (Tap-Desc "Next")) {
    Add-Result "C5" "second open keeps the same reading" "SKIP" "Next button not found"
} else {
    Start-Sleep -Milliseconds 6000
    Tap-Desc "Previous" | Out-Null
    Start-Sleep -Milliseconds 6000
    $p4 = Dump-Xml "reopened"
    $cap3 = Get-Capsule (Find-TierNodes $p4)
    $songNow = Current-Song $p4
    if (-not $cap3) {
        Add-Result "C5" "second open keeps the same reading" "SKIP" "capsule gone after re-open"
    } elseif ($songNow -ne $songAtStart) {
        Add-Result "C5" "second open keeps the same reading" "SKIP" "did not land back on the same song"
    } else {
        $same = ($cap3.Text -eq $capsuleAtStartNode.Text)
        Add-Result "C5" "second open keeps the same reading" $(if ($same) { "PASS" } else { "FAIL" }) "'$($capsuleAtStartNode.Text)' -> '$($cap3.Text)'"
    }
}

# ---------- cleanup: put the start song back on the tier it came from ----------
# C3 writes a per-song override, and overrides now survive restarts (30 days), so a run that
# leaves without restoring silently pins whatever tier the test clicked. Restore only when we
# can still identify the same song and the original tier is offered and enabled in the panel.
if ($songBefore -and $capsule -and $capsule.Text) {
    $pEnd = Dump-Xml "restore"
    $songEnd = Current-Song $pEnd
    $capEnd = Get-Capsule (Find-TierNodes $pEnd)
    if ($songEnd -eq $songBefore -and $capEnd -and $capEnd.Text -ne $capsule.Text) {
        Tap $capEnd
        Start-Sleep -Milliseconds 1500
        $backRows = @(@(Split-PanelRows (Find-TierNodes (Dump-Xml "restore_sheet"))).Rows) |
            Where-Object { $_.Text -eq $capsule.Text -and $_.Enabled }
        if (@($backRows).Count -eq 1) {
            Tap $backRows[0]
            Start-Sleep -Milliseconds ($SettleMs * 2)
            $capRestored = Get-Capsule (Find-TierNodes (Dump-Xml "restore_done"))
            Write-Host ("restored tier = " + $(if ($capRestored) { $capRestored.Text } else { "?" }))
        } else {
            Write-Host ("cleanup skipped: original tier '" + $capsule.Text + "' not selectable")
        }
    }
}

# ---------- summary ----------
# -Wrap: without it the detail column is cut at the console width, which is exactly the field
# a FAIL needs to be readable.
$results | Format-Table -AutoSize -Wrap
$fails = @($results | Where-Object { $_.Status -eq "FAIL" })
$skips = @($results | Where-Object { $_.Status -eq "SKIP" })
Write-Host ("total={0} pass={1} fail={2} skip={3}" -f $results.Count, ($results.Count - $fails.Count - $skips.Count), $fails.Count, $skips.Count)
if ($fails.Count -gt 0) { exit 1 }
if ($skips.Count -eq $results.Count) { exit 2 }
exit 0
