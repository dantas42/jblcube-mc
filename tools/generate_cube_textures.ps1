param(
    [Parameter(Mandatory = $true)]
    [string]$Directory,
    [string]$ItemDirectory = "",
    [string]$EffectDirectory = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path -LiteralPath $Directory)) {
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
}

if ([string]::IsNullOrEmpty($ItemDirectory)) { $ItemDirectory = $Directory }
if ([string]::IsNullOrEmpty($EffectDirectory)) { $EffectDirectory = $Directory }
foreach ($folder in @($ItemDirectory, $EffectDirectory)) {
    if (-not (Test-Path -LiteralPath $folder)) {
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
    }
}

function New-Color([int]$r, [int]$g, [int]$b) {
    return [System.Drawing.Color]::FromArgb(255, $r, $g, $b)
}

function New-Canvas {
    return New-Object System.Drawing.Bitmap 16, 16
}

function Save-Canvas($bitmap, [string]$name) {
    $bitmap.Save((Join-Path $Directory $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

# Cabinet colours are greyscale on purpose: the block/item colour handler multiplies them by the
# wool colour the cube was crafted with, so one texture covers all sixteen variants.
$base = New-Color 0xE0 0xE0 0xE0
$baseAlt = New-Color 0xD2 0xD2 0xD2
$light = New-Color 0xFA 0xFA 0xFA
$dark = New-Color 0x9C 0x9C 0x9C
$outline = New-Color 0x58 0x58 0x58
$metal = New-Color 0x6E 0x6E 0x6E
$metalHi = New-Color 0x9E 0x9E 0x9E

# Status panel: one texture per state, and they are the only saturated colours on the cube, so the
# state reads from across the room.
$red = New-Color 0xC0 0x39 0x2B
$redDk = New-Color 0x7B 0x24 0x1C
$redHi = New-Color 0xE7 0x4C 0x3C
$yellow = New-Color 0xE1 0xB1 0x2C
$yellowDk = New-Color 0x8C 0x6D 0x15
$yellowHi = New-Color 0xF4 0xD0 0x3F
$green = New-Color 0x27 0xAE 0x60
$greenDk = New-Color 0x14 0x5A 0x32
$greenHi = New-Color 0x58 0xD6 0x8D

# ------------------------------------------------------------------- side / back
# Woven cloth with a seam down the front edge. Only rows 0..8 of the texture are ever visible
# because the cabinet is nine pixels tall.
$bmp = New-Canvas
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $color = if (($x + $y) % 2 -eq 0) { $base } else { $baseAlt }
        if ($x -eq 0 -or $x -eq 15) { $color = $dark }
        if ($x -eq 1 -or $x -eq 14) { $color = $light }
        if ($y -eq 0) { $color = $light }
        if ($y -eq 8) { $color = $dark }
        $bmp.SetPixel($x, $y, $color)
    }
}
Save-Canvas $bmp 'jbl_cube_side.png'

# -------------------------------------------------------------------------- top
# The only face that shows the whole texture: a control strip with two knobs and the two handle
# mounts.
$bmp = New-Canvas
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $color = if (($y % 2) -eq 0) { $base } else { $baseAlt }
        if ($x -eq 0 -or $y -eq 0 -or $x -eq 15 -or $y -eq 15) { $color = $dark }
        $bmp.SetPixel($x, $y, $color)
    }
}
foreach ($center in @(@(4, 5), @(11, 6))) {
    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            $dx = $x - $center[0]
            $dy = $y - $center[1]
            $dist = [Math]::Sqrt($dx * $dx + $dy * $dy)
            if ($dist -le 2.4 -and $dist -ge 1.2) { $bmp.SetPixel($x, $y, $outline) }
            elseif ($dist -lt 1.2) { $bmp.SetPixel($x, $y, $dark) }
        }
    }
    $bmp.SetPixel($center[0], $center[1] - 2, $light)
}
# Handle mounts, right where the two handle posts stand.
for ($y = 6; $y -le 9; $y++) {
    foreach ($x in @(2, 3, 12, 13)) { $bmp.SetPixel($x, $y, $metal) }
}
Save-Canvas $bmp 'jbl_cube_top.png'

# ----------------------------------------------------------------------- bottom
# Own texture, so the base plate never shows a stretched edge when the cube sits on another block.
$bmp = New-Canvas
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $color = if (($x + $y) % 2 -eq 0) { $dark } else { $outline }
        if ($x -eq 0 -or $y -eq 0 -or $x -eq 15 -or $y -eq 15) { $color = $outline }
        $bmp.SetPixel($x, $y, $color)
    }
}
foreach ($p in @(@(3, 3), @(12, 3), @(3, 12), @(12, 12), @(7, 7), @(8, 7), @(7, 8), @(8, 8))) {
    $bmp.SetPixel($p[0], $p[1], $metal)
}
Save-Canvas $bmp 'jbl_cube_bottom.png'

# ------------------------------------------------------------------------ front
# Speaker cone on the left, dark screen frame on the right. The lit panel itself is a separate
# element in the model so the state colour is not tinted along with the cabinet.
$bmp = New-Canvas
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $color = if (($x + $y) % 2 -eq 0) { $base } else { $baseAlt }
        if ($y -eq 0 -or $y -eq 8) { $color = $dark }
        if ($x -eq 0 -or $x -eq 15) { $color = $dark }
        $bmp.SetPixel($x, $y, $color)
    }
}
for ($y = 1; $y -le 7; $y++) {
    for ($x = 1; $x -le 8; $x++) {
        $dx = $x + 0.5 - 4.5
        $dy = $y + 0.5 - 4.0
        $dist = [Math]::Sqrt($dx * $dx + $dy * $dy)
        if ($dist -gt 3.7) { $color = $dark }
        elseif ($dist -gt 3.1) { $color = $outline }
        elseif ($dist -lt 0.9) { $color = $dark }
        else {
            $ring = [Math]::Floor($dist * 1.7)
            $color = if ($ring % 2 -eq 0) { $light } else { $base }
        }
        $bmp.SetPixel($x, $y, $color)
    }
}
# Screen frame, the panel element lands on x 10..14 and y 1..7.
for ($y = 0; $y -le 8; $y++) {
    for ($x = 9; $x -le 15; $x++) {
        $color = if ($x -eq 9 -or $x -eq 15 -or $y -eq 0 -or $y -eq 8) { $outline } else { New-Color 0x2A 0x2A 0x2A }
        $bmp.SetPixel($x, $y, $color)
    }
}
# Logo strip under the cone.
for ($x = 1; $x -le 8; $x++) { $bmp.SetPixel($x, 8, $outline) }
foreach ($p in @(@(2, 8), @(3, 8), @(5, 8), @(6, 8))) { $bmp.SetPixel($p[0], $p[1], $light) }
Save-Canvas $bmp 'jbl_cube_front.png'

# ------------------------------------------------------------------------ panel
# The state colour lives in a 5x7 window that the model samples with uv [10, 1, 15, 8].
function New-Panel([string]$Name, $Main, $Dark, $Light) {
    $bmp = New-Canvas
    for ($y = 1; $y -le 7; $y++) {
        for ($x = 10; $x -le 14; $x++) {
            $color = if ($x -eq 10 -or $x -eq 14 -or $y -eq 1 -or $y -eq 7) { $Dark }
                elseif ($y -eq 2) { $Light }
                else { $Main }
            $bmp.SetPixel($x, $y, $color)
        }
    }
    # A couple of brighter bars so the panel looks like a lit display and not a flat swatch.
    foreach ($p in @(@(11, 4), @(12, 4), @(12, 5), @(13, 5))) { $bmp.SetPixel($p[0], $p[1], $Light) }
    Save-Canvas $bmp $Name
}

New-Panel 'jbl_cube_panel_stopped.png' $red $redDk $redHi
New-Panel 'jbl_cube_panel_loading.png' $yellow $yellowDk $yellowHi
New-Panel 'jbl_cube_panel_playing.png' $green $greenDk $greenHi

# ------------------------------------------------------------------------ screen
# The boombox display bezel: a dark glass panel with a lighter rim, drawn behind the coloured
# status lamp so the state reads like a screen instead of a sticker.
$bmp = New-Canvas
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $color = New-Color 0x16 0x18 0x1A
        if ($x -eq 0 -or $y -eq 0 -or $x -eq 15 -or $y -eq 15) { $color = New-Color 0x39 0x3D 0x41 }
        $bmp.SetPixel($x, $y, $color)
    }
}
Save-Canvas $bmp 'jbl_cube_screen.png'

# ----------------------------------------------------------------------- handle
# Not tinted: the handle stays metal whichever colour the cabinet is.
$bmp = New-Canvas
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $color = if ($y -le 2) { $metalHi } elseif ($y -ge 13) { $outline } else { $metal }
        if ($x -eq 0 -or $x -eq 15) { $color = $outline }
        $bmp.SetPixel($x, $y, $color)
    }
}
Save-Canvas $bmp 'jbl_cube_handle.png'

# --------------------------------------------------------------------- beer can
# Greyscale as well, the item colour handler gives each brand its can colour.
$bmp = New-Canvas
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $color = $outline
        if ($x -ge 3 -and $x -le 12 -and $y -ge 1 -and $y -le 14) { $color = $light }
        if (($x -eq 4 -or $x -eq 11) -and $y -ge 1 -and $y -le 14) { $color = $dark }
        if (($x -ge 4 -and $x -le 11) -and ($y -eq 1 -or $y -eq 14)) { $color = $dark }
        if (($x -eq 5 -or $x -eq 10) -and $y -eq 1) { $color = $outline }
        # Label band and a highlight stripe.
        if (($x -ge 4 -and $x -le 11) -and $y -ge 6 -and $y -le 10) { $color = $base }
        if ($x -eq 5 -and $y -ge 6 -and $y -le 10) { $color = $light }
        $bmp.SetPixel($x, $y, $color)
    }
}
$bmp.Save((Join-Path $ItemDirectory 'beer_icon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

# -------------------------------------------------------- drunkenness effect icon
# Icon for the beers effect. Sprite name has to match the effect id, so it is drunkenness.png.
$bmp = New-Canvas
$canMetal = New-Color 0xC8 0xC8 0xC8
$canDark = New-Color 0x60 0x60 0x60
$clear = New-Color 0x00 0x00 0x00
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $bmp.SetPixel($x, $y, $clear)
    }
}
for ($y = 4; $y -le 14; $y++) {
    for ($x = 5; $x -le 10; $x++) {
        $color = $canMetal
        if ($x -eq 5 -or $x -eq 10) { $color = $canDark }
        if ($y -eq 4 -or $y -eq 14) { $color = $canDark }
        $bmp.SetPixel($x, $y, $color)
    }
}
# Foam on top plus three stars, so it reads as drunk rather than as a potion.
foreach ($p in @(@(5, 3), @(6, 3), @(7, 2), @(8, 3), @(9, 3), @(10, 3))) { $bmp.SetPixel($p[0], $p[1], $light) }
foreach ($p in @(@(3, 1), @(12, 2), @(12, 5), @(2, 7), @(13, 8), @(4, 11))) {
    $bmp.SetPixel($p[0], $p[1], $yellowHi)
    $bmp.SetPixel($p[0] + 1, $p[1], $yellow)
}
$bmp.Save((Join-Path $EffectDirectory 'drunkenness.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

Write-Output "wrote cube, panel, handle textures to $Directory; beer icon to $ItemDirectory; effect icon to $EffectDirectory"