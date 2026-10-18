param(
    [Parameter(Mandatory = $true)]
    [string]$Resources
)

$ErrorActionPreference = "Stop"

# Writes every per colour and per beer file: models, blockstates, loot tables and recipes.
# The textures themselves come from generate_cube_textures.ps1.

$colors = @(
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
)

$beers = @(
    @{ id = "skol"; wheat = 1 },
    @{ id = "brahma"; wheat = 2 },
    @{ id = "antarctica"; wheat = 3 },
    @{ id = "original"; wheat = 4 },
    @{ id = "bohemia"; wheat = 5 },
    @{ id = "kaiser"; wheat = 6 },
    @{ id = "serramalte"; wheat = 7 }
)

function Write-Text([string]$path, [string]$content) {
    $folder = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $folder)) {
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
    }
    # No BOM, the game resource loader is happier without one.
    [System.IO.File]::WriteAllText((Join-Path (Resolve-Path -LiteralPath $folder).Path (Split-Path -Leaf $path)), $content)
}

# --------------------------------------------------------------- block models
# Three models only: the cabinet is identical for every colour, the panel texture is what changes.
# __PANEL__ is replaced with the state texture.
#
# A JBL-style boombox lying along X: a horizontal rounded prism (nine 1px bands that follow an
# ellipse) so the silhouette reads round, a chunky fold-down handle on top, and a display on the
# front made of a dark bezel plus the coloured status lamp.
function Band([int]$y, [int]$zMin, [int]$zMax) {
    # The front art (16 rows) is stretched across the boombox front, which spans y 2..11.
    $v1 = $y - 2
    $v2 = $v1 + 1
    $by1 = 16 - ($y + 1)
    $by2 = 16 - $y
    return "      {
        ""from"": [0, $y, $zMin],
        ""to"": [16, $($y + 1), $zMax],
        ""faces"": {
          ""north"": { ""uv"": [0, $by1, 16, $by2], ""texture"": ""#side"", ""tintindex"": 0 },
          ""south"": { ""uv"": [0, $v1, 16, $v2], ""texture"": ""#front"", ""tintindex"": 0 },
          ""east"": { ""uv"": [$zMin, $by1, $zMax, $by2], ""texture"": ""#side"", ""tintindex"": 0 },
          ""west"": { ""uv"": [$zMin, $by1, $zMax, $by2], ""texture"": ""#side"", ""tintindex"": 0 },
          ""up"": { ""uv"": [0, $zMin, 16, $zMax], ""texture"": ""#top"", ""tintindex"": 0 },
          ""down"": { ""uv"": [0, $zMin, 16, $zMax], ""texture"": ""#bottom"", ""tintindex"": 0 }
        }
      }"
}

function Accent([double]$x1, [double]$y1, [double]$z1, [double]$x2, [double]$y2, [double]$z2,
                [string]$tex, [string[]]$faces) {
    $lines = @()
    foreach ($f in $faces) {
        $lines += "          ""$f"": { ""uv"": [0, 0, 16, 16], ""texture"": ""$tex"" }"
    }
    # Faces are joined with a comma, so the last one never carries a trailing comma.
    $faceBlock = ($lines -join ",`n")
    return "      {
        ""from"": [$x1, $y1, $z1],
        ""to"": [$x2, $y2, $z2],
        ""faces"": {
$faceBlock
        }
      }"
}

$elements = New-Object System.Collections.Generic.List[string]
# Boombox barrell: an ellipse in the Y-Z plane centred at y=6.5, so the top and the bottom are
# rounded and the whole body sits almost flush on the block instead of floating three pixels up.
for ($y = 2; $y -le 10; $y++) {
    $mid = $y + 0.5
    $half = [math]::Floor([math]::Sqrt(20.25 - ($mid - 6.5) * ($mid - 6.5)))
    if ($half -le 0) { continue }
    $zMin = 8 - $half
    $zMax = 8 + $half
    if ($zMin -ge $zMax) { continue }
    $elements.Add((Band $y $zMin $zMax))
}
# Four small rubber feet that touch the block below, so the barrell does not seem to hover; the rest
# of the bottom stays open, which is fine because the block's occlusion shape keeps whatever is
# underneath (grass, planks, ...) visible instead of cutting its faces away.
foreach ($foot in @(@(2, 4), @(12, 4), @(2, 10), @(12, 10))) {
    $elements.Add((Accent $foot[0] 0 $foot[1] ($foot[0] + 2) 2 ($foot[1] + 2) "#handle" @("north", "south", "east", "west", "up", "down")))
}
# The fold-down handle. Thin but tall: it starts just above the barrell top (y=11.5) and reaches the
# top of the block (y=16), so there is no mistaking it for part of the cabinet. Every element starts
# on its own plane (half-pixel offsets and non-overlapping x ranges), so no two faces share a
# surface: sharing one is what made the bar flicker.
$elements.Add((Accent 3 11.5 6 5 16 10 "#handle" @("north", "south", "east", "west", "up")))
$elements.Add((Accent 11 11.5 6 13 16 10 "#handle" @("north", "south", "east", "west", "up")))
$elements.Add((Accent 5 15 6.5 11 16 9.5 "#handle" @("north", "south", "east", "west", "up")))
# The display on the front: a big dark bezel with the coloured lamp on top of it.
$screen = @"
      {
        "from": [8, 4, 12.05],
        "to": [15, 9, 12.06],
        "faces": {
          "south": { "uv": [0, 0, 16, 16], "texture": "#screen" }
        }
      }
"@
$lamp = @"
      {
        "from": [9, 4, 12.11],
        "to": [14, 9, 12.12],
        "faces": {
          "south": { "uv": [10, 1, 15, 8], "texture": "#panel" }
        }
      }
"@
$elements.Add($screen)
$elements.Add($lamp)

$modelTemplate = @'
{
  "textures": {
    "particle": "jblcube:block/jbl_cube_side",
    "side": "jblcube:block/jbl_cube_side",
    "top": "jblcube:block/jbl_cube_top",
    "bottom": "jblcube:block/jbl_cube_bottom",
    "front": "jblcube:block/jbl_cube_front",
    "handle": "jblcube:block/jbl_cube_handle",
    "screen": "jblcube:block/jbl_cube_screen",
    "panel": "jblcube:block/jbl_cube_panel___PANEL__"
  },
  "elements": [
__ELEMENTS__
  ]
}
'@
$modelTemplate = $modelTemplate.Replace("__ELEMENTS__", ($elements -join ",`n"))

foreach ($status in @("stopped", "loading", "playing")) {
    Write-Text "$Resources\assets\jblcube\models\block\jbl_cube_$status.json" $modelTemplate.Replace("__PANEL__", $status)
}

# -------------------------------------------------------------- item models
# All sixteen point at the same stopped model, the colour comes from the item colour handler.
$itemModelTemplate = @'
{
  "parent": "jblcube:block/jbl_cube_stopped",
  "gui_light": "front",
  "display": {
    "gui": { "rotation": [30, 225, 0], "translation": [0, -0.5, 0], "scale": [0.6, 0.6, 0.6] },
    "ground": { "rotation": [0, 0, 0], "translation": [0, 0.5, 0], "scale": [0.5, 0.5, 0.5] },
    "fixed": { "rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5] },
    "firstperson_righthand": { "rotation": [0, -90, 20], "translation": [0.9, 2.2, -1.6], "scale": [0.5, 0.5, 0.5] },
    "firstperson_lefthand": { "rotation": [0, 90, -20], "translation": [0.9, 2.2, -1.6], "scale": [0.5, 0.5, 0.5] },
    "thirdperson_righthand": { "rotation": [0, -90, 50], "translation": [0, 4.2, 0.4], "scale": [0.45, 0.45, 0.45] },
    "thirdperson_lefthand": { "rotation": [0, 90, -50], "translation": [0, 4.2, 0.4], "scale": [0.45, 0.45, 0.45] }
  }
}
'@

$blockStateTemplate = @'
{
  "variants": {
    "loading=false,playing=false": { "model": "jblcube:block/jbl_cube_stopped" },
    "loading=false,playing=true": { "model": "jblcube:block/jbl_cube_playing" },
    "loading=true,playing=true": { "model": "jblcube:block/jbl_cube_loading" },
    "loading=true,playing=false": { "model": "jblcube:block/jbl_cube_stopped" }
  }
}
'@

# Empty on purpose: the block drops itself from onRemove so the playlist survives a break.
$lootTemplate = @'
{
  "pools": []
}
'@

# Iron frame with a gold ingot in the middle and the wool that picks the colour in the bottom centre.
# The layout lives in JblCubeRecipe, so the recipe file only carries the colour.
$recipeTemplate = @'
{
  "type": "jblcube:cube",
  "color": "__COLOR__"
}
'@

foreach ($color in $colors) {
    $id = "jbl_cube_$color"
    Write-Text "$Resources\assets\jblcube\models\item\$id.json" $itemModelTemplate
    Write-Text "$Resources\assets\jblcube\blockstates\$id.json" $blockStateTemplate
    Write-Text "$Resources\data\jblcube\loot_table\blocks\$id.json" $lootTemplate
    Write-Text "$Resources\data\jblcube\recipe\$id.json" $recipeTemplate.Replace("__COLOR__", $color)
}

# ------------------------------------------------------------------ the beers
$beerModelTemplate = @'
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "jblcube:item/beer_icon"
  }
}
'@

foreach ($beer in $beers) {
    $id = "beer_$($beer.id)"
    Write-Text "$Resources\assets\jblcube\models\item\$id.json" $beerModelTemplate

    $ingredients = @()
    for ($i = 0; $i -lt $beer.wheat; $i++) {
        $ingredients += "    { ""item"": ""minecraft:wheat"" }"
    }
    $wheatList = ($ingredients -join ",`n")

    # The bucket is part of the recipe but comes straight back, so only the wheat is eaten.
    Write-Text "$Resources\data\jblcube\recipe\$id.json" @"
{
  "type": "jblcube:bucket",
  "ingredients": [
$wheatList
  ],
  "result": { "id": "jblcube:$id", "count": 1 }
}
"@
}

# The cube recipe only ever asks for vanilla iron, gold and wool, so no data pack tag is needed:
# a pack without a bronze mod can never break the data pack with an unresolved tag reference.

Write-Output "generated $($colors.Count) cube variants and $($beers.Count) beers under $Resources"