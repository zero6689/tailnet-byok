<#
.SYNOPSIS
    Installs a pre-composed adaptive-icon layer set into an Android res/ directory.

.DESCRIPTION
    `make-icons.ps1` starts from one mark image and composes the icon itself: it
    paints the art on a plate, keeps the mark inside the adaptive safe zone, and can
    derive a themed-icon silhouette from the art's alpha.

    This script is for the other kind of delivery: artwork that already arrives as a
    finished adaptive icon -- a background plate, a foreground with the safe zone
    baked in, and a silhouette drawn by the artist rather than derived by a filter.
    Re-composing that art through make-icons.ps1 would be wrong twice over: it would
    scale an already-safe-zone-composed foreground down a second time (0.72 x 0.72)
    and paint a plate behind a layer that is a separate background now.

    So the layers are copied and scaled, never rebuilt. Every layer is authored on
    the 108dp canvas, which is what makes a plain scale the whole transform:

      * ic_launcher_background.png    opaque plate, scaled to five densities
      * ic_launcher_foreground.png    transparent, alpha preserved
      * ic_launcher_monochrome.png    transparent, alpha preserved (themed icon)

    and the adaptive-icon XMLs under `mipmap-anydpi*/` are pointed at the bitmap
    background and given the `<monochrome>` element.

    The 4x density is copied byte for byte rather than re-encoded: it is the
    largest layer a launcher can pick, and there is no reason to run it through a
    resampler that has nothing to resample.

.PARAMETER Background
    The background layer, at 432x432 (108dp at xxxhdpi) or any other square size
    shared by every layer.

.PARAMETER Foreground
    The foreground layer: the mark, transparent, already inside the safe zone.

.PARAMETER Monochrome
    Optional. The themed-icon silhouette: transparent, single colour (the launcher
    tints it). When given, the XMLs gain a `<monochrome>` element.

.PARAMETER Res
    The Android `res/` directory to write into.

.EXAMPLE
    ./install-icon-layers.ps1 -Background ..\dsh-icon\android\ic_launcher_background.png `
        -Foreground ..\dsh-icon\android\ic_launcher_foreground.png `
        -Monochrome ..\dsh-icon\android\ic_launcher_monochrome.png `
        -Res app/src/main/res
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Background,
    [Parameter(Mandatory = $true)][string]$Foreground,
    [string]$Monochrome,
    [Parameter(Mandatory = $true)][string]$Res
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

foreach ($p in @($Background, $Foreground, $Monochrome)) {
    if ($p -and -not (Test-Path $p)) { throw "layer not found: $p" }
}
if (-not (Test-Path $Res)) { throw "resource directory not found: $Res" }

$layers = [ordered]@{ background = $Background; foreground = $Foreground }
if ($Monochrome) { $layers['monochrome'] = $Monochrome }

# Every layer has to share one geometry, or the plate and the mark disagree about
# where the canvas is -- the failure mode is a mark that sits off-centre under the
# launcher's mask, which nobody notices until it is on a phone.
$side = $null
foreach ($name in $layers.Keys) {
    $img = [System.Drawing.Image]::FromFile((Resolve-Path $layers[$name]))
    if ($img.Width -ne $img.Height) { throw "$name is not square ($($img.Width)x$($img.Height))" }
    if ($null -eq $side) { $side = $img.Width }
    elseif ($img.Width -ne $side) { throw "$name is $($img.Width)px but the first layer is ${side}px; all layers must share a size" }
    $img.Dispose()
}
Write-Output "layers: $($layers.Count) at ${side}x${side}"

# 108dp is the adaptive-icon canvas; these are the density multipliers.
$densityScale = [ordered]@{
    'mdpi'    = 1.0
    'hdpi'    = 1.5
    'xhdpi'   = 2.0
    'xxhdpi'  = 3.0
    'xxxhdpi' = 4.0
}

$written = @()
foreach ($density in $densityScale.Keys) {
    $px = [int][Math]::Round(108 * $densityScale[$density])
    $dir = Join-Path $Res "mipmap-$density"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    foreach ($name in $layers.Keys) {
        $out = Join-Path $dir "ic_launcher_$name.png"
        if ($px -eq $side) {
            Copy-Item -LiteralPath (Resolve-Path $layers[$name]) -Destination $out -Force
        } else {
            $src = [System.Drawing.Image]::FromFile((Resolve-Path $layers[$name]))
            $bmp = [System.Drawing.Bitmap]::new($px, $px, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.CompositingMode   = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $g.DrawImage($src, [System.Drawing.Rectangle]::new(0, 0, $px, $px))
            $g.Flush()
            $g.Dispose()
            $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
            $bmp.Dispose()
            $src.Dispose()
        }
        $written += $out
    }
}

# Point the XMLs at the bitmap background and at the silhouette. The element is
# matched, not the word: these files discuss `<monochrome>` in their comments, and a
# bare substring test reads that prose as "already patched" and skips the file.
$anydpi = Join-Path $Res 'mipmap-anydpi'
$xmls = @(Get-ChildItem -Path $anydpi -Filter 'ic_launcher*.xml' -ErrorAction SilentlyContinue)
if ($xmls.Count -eq 0) { Write-Warning "no mipmap-anydpi/ic_launcher*.xml under ${Res}: the layers were written but nothing references them" }
foreach ($x in $xmls) {
    $text = [IO.File]::ReadAllText($x.FullName)
    $before = $text
    $text = [regex]::Replace($text,
        '(?m)^[ \t]*<background android:drawable="[^"]*"[ \t]*/>',
        '    <background android:drawable="@mipmap/ic_launcher_background" />')
    if ($Monochrome -and $text -notmatch '<monochrome\s+android:drawable') {
        $element = "    <monochrome android:drawable=`"@mipmap/ic_launcher_monochrome`" />`r`n</adaptive-icon>"
        $text = $text.Replace('</adaptive-icon>', $element)
    }
    if ($text -ne $before) {
        [IO.File]::WriteAllText($x.FullName, $text, [Text.UTF8Encoding]::new($false))
        Write-Output "patched $($x.Name)"
    } else {
        Write-Output "$($x.Name) already references the layers"
    }
}
if ($Monochrome) {
    Write-Warning 'the comment block in mipmap-anydpi/ic_launcher.xml still describes the old icon: update the prose'
}

Write-Output "wrote $($written.Count) file(s) into $Res"
foreach ($f in $written) {
    $rel = $f.Replace((Resolve-Path $Res).Path + '\', '')
    Write-Output ("  {0,-46} {1,8:N0} B" -f $rel, (Get-Item $f).Length)
}
