<#
.SYNOPSIS
    Generates Android launcher icon assets from a single source image, optionally
    cropping the mark out of an unedited phone screenshot first.

.DESCRIPTION
    Takes one source PNG and produces everything an Android app needs:

      * adaptive-icon foregrounds at five densities (108dp canvas, mark inside the
        central safe zone), for `mipmap-anydpi*/ic_launcher.xml` to reference
      * optionally, legacy raster `ic_launcher.png` files at 48/72/96/144/192 for
        apps whose minSdk is below 26

    With -AutoCrop, the source may be a raw phone screenshot: letterbox bars,
    status bar, navigation bar and viewer chrome all get measured away. That path
    exists because the artwork this project runs on arrives as screenshots, and
    cropping by eye puts the mark off-centre by exactly the amount nobody notices
    until the launcher masks it.

    # Why PowerShell

    Because it needs an image decoder and this repository does not carry one. The
    usual answer is `sharp`, which means an npm install in a build that otherwise
    has no Node dependency at all. System.Drawing is already present on Windows
    and does the whole job.

    The cost is that this script is Windows-only. That is a real limitation, and
    it is why the *output* is committed: nobody needs to run this to build the
    app, only to change the artwork.

.PARAMETER Source
    The source image. Non-square is fine; it is scaled to fit, never cropped
    (except by -AutoCrop).

.PARAMETER Res
    An Android `res/` directory to write into.

.PARAMETER MarkFraction
    Fraction of the canvas width the mark occupies. Default 0.72.

.PARAMETER Legacy
    Also write legacy raster `ic_launcher.png` files, for minSdk below 26.

.PARAMETER Background
    Hex background colour painted behind the mark. Default #FFFFFF.

.PARAMETER AutoCrop
    Treat the source as a screenshot: find the content band between the letterbox
    bars, then the subject's bounding box inside it, and use that crop.

.PARAMETER SaveCropped
    Path to also write the cropped mark to, as a reusable source asset.

.PARAMETER PlayStoreIcon
    Path to also write a 512x512 Play Store listing icon to. The mark fills 78%
    of it: there is no launcher mask on a store listing, so the safe-zone
    discount would only make it look small.

.PARAMETER WhiteThreshold
    A pixel counts as background white when every channel is at or above this.
    Default 235; lower it for heavily compressed screenshots.

.PARAMETER MonochromeSource
    A silhouette for the Material You themed-icon layer: Android tints that layer
    instead of drawing the coloured one, so only its alpha matters. Written as
    `ic_launcher_monochrome.png` at all five densities, and the
    `mipmap-anydpi*/ic_launcher*.xml` files gain a `<monochrome>` element.

.PARAMETER MonochromeFromArt
    Derive the silhouette from the source art's own alpha, instead of a second
    file. Correct only when the art is a solid shape on transparency. The script
    refuses when the result would be a filled square, because a themed icon that
    "works" by accident is worse than no themed icon at all.

.EXAMPLE
    ./make-icons.ps1 -Source branding/mark-taiji.png -Res app/src/main/res

.EXAMPLE
    # Straight from a screenshot, keeping the cropped mark for next time:
    ./make-icons.ps1 -Source shot.jpg -AutoCrop -SaveCropped branding/mark-taiji.png `
                     -Res ../other-app/res -Legacy
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Res,
    [double]$MarkFraction = 0.72,
    [switch]$Legacy,
    [string]$Background = '#FFFFFF',
    [switch]$AutoCrop,
    [string]$SaveCropped,
    [string]$PlayStoreIcon,
    [int]$CropPadding = 6,
    [int]$WhiteThreshold = 235,
    [string]$MonochromeSource,
    [switch]$MonochromeFromArt
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { throw "source image not found: $Source" }
if (-not (Test-Path $Res)) { throw "resource directory not found: $Res" }
if ($MarkFraction -le 0 -or $MarkFraction -gt 1) { throw "MarkFraction must be in (0, 1]" }
if ($MonochromeSource -and $MonochromeFromArt) {
    throw 'pass either -MonochromeSource or -MonochromeFromArt, not both'
}
if ($MonochromeSource -and -not (Test-Path $MonochromeSource)) {
    throw "monochrome silhouette not found: $MonochromeSource"
}

function Test-IsWhite {
    param([System.Drawing.Color]$Color, [int]$Threshold)
    return ($Color.R -ge $Threshold -and $Color.G -ge $Threshold -and $Color.B -ge $Threshold)
}

function Test-IsBlack {
    param([System.Drawing.Color]$Color)
    # 45 rather than 0: JPEG leaves the letterbox bars slightly off-black, and a
    # strict test would fail to find them at all.
    return ($Color.R -lt 45 -and $Color.G -lt 45 -and $Color.B -lt 45)
}

<#
    Crops the mark out of a screenshot.

    Two stages, and the order matters:

      1. Find the letterbox. Take the longest run of rows that are NOT mostly
         black. Those bars are what a screenshot viewer adds, along with the
         status bar and navigation bar — and whatever chrome sits inside them
         (a close button, a "2/3" counter) is discarded with them.
      2. Inside that band, find the extremes of non-white pixels. That is the mark.

    Doing it the other way round — "the longest run of mostly-white rows is the
    background" — is wrong, and was wrong here first: a row crossing the mark is
    not mostly white, so that approach finds the empty margin *above* the mark and
    reports a bounding box containing nothing.
#>
function Get-MarkBounds {
    param([System.Drawing.Bitmap]$Bitmap, [int]$Threshold)

    $w = $Bitmap.Width
    $h = $Bitmap.Height

    $rowBlack = New-Object 'double[]' $h
    for ($y = 0; $y -lt $h; $y++) {
        $dark = 0; $sampled = 0
        for ($x = 0; $x -lt $w; $x += 4) {
            if (Test-IsBlack $Bitmap.GetPixel($x, $y)) { $dark++ }
            $sampled++
        }
        $rowBlack[$y] = $dark / $sampled
    }

    $bandStart = -1; $bandLength = 0; $runStart = -1
    for ($y = 0; $y -lt $h; $y++) {
        if ($rowBlack[$y] -lt 0.60) {
            if ($runStart -lt 0) { $runStart = $y }
        } else {
            if ($runStart -ge 0) {
                $len = $y - $runStart
                if ($len -gt $bandLength) { $bandLength = $len; $bandStart = $runStart }
                $runStart = -1
            }
        }
    }
    if ($runStart -ge 0) {
        $len = $h - $runStart
        if ($len -gt $bandLength) { $bandLength = $len; $bandStart = $runStart }
    }
    if ($bandStart -lt 0) { throw 'could not find a content band between the letterbox bars' }

    $bandTop = $bandStart
    $bandBottom = $bandStart + $bandLength - 1

    $left = $w; $right = -1; $top = $bandBottom; $bottom = $bandTop
    for ($y = $bandTop; $y -le $bandBottom; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            if (-not (Test-IsWhite $Bitmap.GetPixel($x, $y) $Threshold)) {
                if ($x -lt $left) { $left = $x }
                if ($x -gt $right) { $right = $x }
                if ($y -lt $top) { $top = $y }
                if ($y -gt $bottom) { $bottom = $y }
            }
        }
    }
    if ($right -lt 0) { throw 'the content band is entirely white — nothing to crop' }

    return [pscustomobject]@{
        BandTop  = $bandTop
        BandBot  = $bandBottom
        Left     = $left
        Right    = $right
        Top      = $top
        Bottom   = $bottom
    }
}

function New-CroppedBitmap {
    param([System.Drawing.Bitmap]$Bitmap, [pscustomobject]$Bounds, [int]$Padding)

    $x0 = [Math]::Max(0, $Bounds.Left - $Padding)
    $y0 = [Math]::Max(0, $Bounds.Top - $Padding)
    $x1 = [Math]::Min($Bitmap.Width - 1, $Bounds.Right + $Padding)
    $y1 = [Math]::Min($Bitmap.Height - 1, $Bounds.Bottom + $Padding)

    $cw = $x1 - $x0 + 1
    $ch = $y1 - $y0 + 1

    $cropped = [System.Drawing.Bitmap]::new($cw, $ch)
    $g = [System.Drawing.Graphics]::FromImage($cropped)
    $g.DrawImage(
        $Bitmap,
        [System.Drawing.Rectangle]::new(0, 0, $cw, $ch),
        [System.Drawing.Rectangle]::new($x0, $y0, $cw, $ch),
        [System.Drawing.GraphicsUnit]::Pixel
    )
    $g.Dispose()

    # These diagnostics go to Write-Host, NOT Write-Output.
    #
    # Write-Output writes to the *pipeline*, so inside a function that also
    # returns a value it becomes part of the return value: the caller receives an
    # array of three strings plus the bitmap, and the next `.Save()` call fails
    # with "System.String does not contain a method named 'Save'" — an error that
    # names neither the function nor the stray Write-Output that caused it.
    Write-Host ("  content band y={0}..{1}" -f $Bounds.BandTop, $Bounds.BandBot)
    Write-Host ("  mark bounds  x={0}..{1} y={2}..{3}" -f $Bounds.Left, $Bounds.Right, $Bounds.Top, $Bounds.Bottom)
    Write-Host ("  crop         {0}x{1} at ({2},{3})" -f $cw, $ch, $x0, $y0)
    return $cropped
}

$loaded = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Source))

if ($AutoCrop) {
    Write-Output "auto-cropping $([System.IO.Path]::GetFileName($Source))"
    $bounds = Get-MarkBounds -Bitmap $loaded -Threshold $WhiteThreshold
    $art = New-CroppedBitmap -Bitmap $loaded -Bounds $bounds -Padding $CropPadding
    $loaded.Dispose()

    if ($SaveCropped) {
        $saveDir = Split-Path -Parent $SaveCropped
        if ($saveDir -and -not (Test-Path $saveDir)) { New-Item -ItemType Directory -Force -Path $saveDir | Out-Null }
        if (Test-Path $SaveCropped) {
            # Files copied out of a screenshot viewer often carry the read-only
            # attribute, and overwriting one makes GDI+ fail with a bare "generic
            # error" that names neither the file nor the attribute.
            #
            # `-band (-bnot ReadOnly)` CLEARS the flag. `-bxor` does not: it
            # toggles, so on a file that was already writable it would *set*
            # read-only and break the very save it was meant to protect.
            $existing = Get-Item $SaveCropped
            $existing.Attributes = $existing.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)
        }
        $art.Save($SaveCropped, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output "  saved cropped mark -> $SaveCropped"
    }
} else {
    $art = $loaded
}

$aspect = $art.Height / $art.Width
Write-Output ("mark: {0}x{1}  aspect {2:N3}" -f $art.Width, $art.Height, $aspect)

$bgColor = [System.Drawing.ColorTranslator]::FromHtml($Background)

# Adaptive icons are authored on a 108dp canvas. Legacy launcher icons are
# authored at 48dp. Both scale linearly with density.
$densityScale = [ordered]@{
    'mdpi'    = 1.0
    'hdpi'    = 1.5
    'xhdpi'   = 2.0
    'xxhdpi'  = 3.0
    'xxxhdpi' = 4.0
}

function New-IconCanvas {
    param([int]$Size, [System.Drawing.Bitmap]$Art, [double]$Aspect, [double]$Fraction, [System.Drawing.Color]$Bg)
    $bmp = [System.Drawing.Bitmap]::new($Size, $Size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear($Bg)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality

    $mw = [int][Math]::Round($Size * $Fraction)
    $mh = [int][Math]::Round($mw * $Aspect)
    $mx = [int][Math]::Round(($Size - $mw) / 2)
    $my = [int][Math]::Round(($Size - $mh) / 2)
    $g.DrawImage($Art, [System.Drawing.Rectangle]::new($mx, $my, $mw, $mh))

    # Flush before returning: without it GDI+ can hand back a half-drawn bitmap
    # and the saved PNG is silently wrong.
    $g.Flush()
    $g.Dispose()
    return $bmp
}

function New-MonochromeCanvas {
    param([int]$Size, [System.Drawing.Bitmap]$Silhouette, [double]$Aspect, [double]$Fraction)
    # Transparent, never a plate: the launcher draws this layer on its own themed
    # background and tints it, so a ground here would become a coloured square.
    $bmp = [System.Drawing.Bitmap]::new($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality

    $mw = [int][Math]::Round($Size * $Fraction)
    $mh = [int][Math]::Round($mw * $Aspect)
    $mx = [int][Math]::Round(($Size - $mw) / 2)
    $my = [int][Math]::Round(($Size - $mh) / 2)

    # Flatten to white while keeping alpha. Which colour this layer is does not
    # matter (the launcher tints it), and a ColorMatrix does it in one draw call
    # where a per-pixel loop would walk 1.3M pixels across the five densities.
    $matrix = [System.Drawing.Imaging.ColorMatrix]::new()
    $matrix.Matrix00 = 0; $matrix.Matrix11 = 0; $matrix.Matrix22 = 0
    $matrix.Matrix33 = 1
    $matrix.Matrix40 = 1; $matrix.Matrix41 = 1; $matrix.Matrix42 = 1
    $attrs = [System.Drawing.Imaging.ImageAttributes]::new()
    $attrs.SetColorMatrix($matrix)
    $g.DrawImage(
        $Silhouette,
        [System.Drawing.Rectangle]::new($mx, $my, $mw, $mh),
        0, 0, $Silhouette.Width, $Silhouette.Height,
        [System.Drawing.GraphicsUnit]::Pixel, $attrs)
    $attrs.Dispose()

    $g.Flush()
    $g.Dispose()
    return $bmp
}

function Get-OpaqueFraction {
    param([System.Drawing.Bitmap]$Bitmap, [int]$Step = 4)
    $opaque = 0; $total = 0
    for ($y = 0; $y -lt $Bitmap.Height; $y += $Step) {
        for ($x = 0; $x -lt $Bitmap.Width; $x += $Step) {
            $total++
            if ($Bitmap.GetPixel($x, $y).A -ge 128) { $opaque++ }
        }
    }
    return $opaque / $total
}

$written = @()

# The silhouette is either a second file or the art's own alpha. A supplied
# silhouette is used as-is; a derived one has to be a real shape, which is what
# the filled-square check below is for.
$monoArt = $null
$monoAspect = 1.0
if ($MonochromeSource) {
    $monoArt = [System.Drawing.Bitmap]::FromFile((Resolve-Path $MonochromeSource))
    $monoAspect = $monoArt.Height / $monoArt.Width
    Write-Output ("silhouette: {0}x{1}" -f $monoArt.Width, $monoArt.Height)
} elseif ($MonochromeFromArt) {
    $monoArt = $art
    $monoAspect = $aspect
    $artCoverage = Get-OpaqueFraction -Bitmap $art -Step 8
    if ($artCoverage -gt 0.97) {
        $msg = "the source art is opaque (opaque fraction {0:P0}), so its alpha is not a shape; " -f $artCoverage
        throw ($msg + 'pass -MonochromeSource with artwork that carries a silhouette, or drop the themed-icon layer')
    }
}

foreach ($density in $densityScale.Keys) {
    $scale = $densityScale[$density]
    $dir = Join-Path $Res "mipmap-$density"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    # --- Adaptive foreground: 108dp canvas ---
    $adaptiveSize = [int][Math]::Round(108 * $scale)
    $fgBitmap = New-IconCanvas -Size $adaptiveSize -Art $art -Aspect $aspect -Fraction $MarkFraction -Bg $bgColor
    $fgPath = Join-Path $dir 'ic_launcher_foreground.png'
    $fgBitmap.Save($fgPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $fgBitmap.Dispose()
    $written += $fgPath

    # --- Legacy raster: 48dp canvas, mark nearly edge to edge ---
    if ($Legacy) {
        # Legacy icons get no safe-zone discount: the launcher shows them whole, so
        # a smaller fraction would look undersized next to every other app.
        #
        # The variable is `$squareIcon`, NOT `$legacy`. PowerShell variable names are
        # case-insensitive, so `$legacy = ...` would silently overwrite the -Legacy
        # switch parameter with a Bitmap, and the second loop iteration would die
        # with "cannot convert System.Drawing.Bitmap to SwitchParameter" — an error
        # that names the parameter rather than the assignment that broke it.
        $legacySize = [int][Math]::Round(48 * $scale)
        $squareIcon = New-IconCanvas -Size $legacySize -Art $art -Aspect $aspect -Fraction 0.86 -Bg $bgColor
        $legacyPath = Join-Path $dir 'ic_launcher.png'
        $squareIcon.Save($legacyPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $squareIcon.Dispose()
        $written += $legacyPath
    }

    # --- Material You themed-icon layer ---
    if ($monoArt) {
        $mono = New-MonochromeCanvas -Size $adaptiveSize -Silhouette $monoArt -Aspect $monoAspect -Fraction $MarkFraction
        $monoCoverage = Get-OpaqueFraction -Bitmap $mono -Step 4
        if ($monoCoverage -gt 0.97) {
            $mono.Dispose()
            $msg = "the monochrome layer is a filled square (opaque fraction {0:P0} at {1}): " -f $monoCoverage, $density
            throw ($msg + 'a themed-icon silhouette has to come from the artwork, not from a filter')
        }
        if ($monoCoverage -lt 0.02) {
            $mono.Dispose()
            throw ("the monochrome layer is empty (opaque fraction {0:P1} at {1})" -f $monoCoverage, $density)
        }
        $monoPath = Join-Path $dir 'ic_launcher_monochrome.png'
        $mono.Save($monoPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $mono.Dispose()
        $written += $monoPath
    }
}

if ($monoArt) {
    # Reference it, or the layer is a file nothing reads. `</adaptive-icon>` is
    # unique in each file and carries no indentation of its own, so replacing it
    # keeps the element aligned with its siblings without parsing XML.
    $anydpi = Join-Path $Res 'mipmap-anydpi'
    $xmls = @(Get-ChildItem -Path $anydpi -Filter 'ic_launcher*.xml' -ErrorAction SilentlyContinue)
    if ($xmls.Count -eq 0) {
        Write-Warning "no mipmap-anydpi/ic_launcher*.xml under ${Res}: the monochrome layer was written but nothing references it"
    }
    foreach ($x in $xmls) {
        $text = [IO.File]::ReadAllText($x.FullName)
        # Match the element, not the word: the hand-written comment in
        # `ic_launcher.xml` discusses a `<monochrome>` layer it does not have, and a
        # bare substring test would read that comment as "already patched" and skip
        # the file — leaving a themed icon that is generated but never referenced.
        if ($text -match '<monochrome\s+android:drawable') { continue }
        $element = "    <monochrome android:drawable=`"@mipmap/ic_launcher_monochrome`" />`r`n</adaptive-icon>"
        $text = $text.Replace('</adaptive-icon>', $element)
        [IO.File]::WriteAllText($x.FullName, $text, [Text.UTF8Encoding]::new($false))
        Write-Output "patched $($x.Name): added <monochrome>"
    }
    Write-Warning 'the comment block in mipmap-anydpi/ic_launcher.xml still says there is no monochrome layer: update it'
}

if ($PlayStoreIcon) {
    $storeDir = Split-Path -Parent $PlayStoreIcon
    if ($storeDir -and -not (Test-Path $storeDir)) { New-Item -ItemType Directory -Force -Path $storeDir | Out-Null }
    if (Test-Path $PlayStoreIcon) {
        # Clears, not toggles. See the note on the -SaveCropped path above.
        $existingStore = Get-Item $PlayStoreIcon
        $existingStore.Attributes = $existingStore.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)
    }
    $storeSide = 512
    $store = [System.Drawing.Bitmap]::new($storeSide, $storeSide)
    $sg = [System.Drawing.Graphics]::FromImage($store)
    $sg.Clear($bgColor)
    $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sg.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $sw = [int]($storeSide * 0.78)
    $sh = [int]($sw * $aspect)
    $sg.DrawImage($art, [System.Drawing.Rectangle]::new([int](($storeSide - $sw) / 2), [int](($storeSide - $sh) / 2), $sw, $sh))
    $sg.Flush(); $sg.Dispose()
    $store.Save($PlayStoreIcon, [System.Drawing.Imaging.ImageFormat]::Png)
    $store.Dispose()
    Write-Output "wrote Play Store icon ${storeSide}x${storeSide} -> $PlayStoreIcon"
}

# Disposed last, and only after every consumer: the store icon above draws from
# the same bitmap, and GDI+ throws "Parameter is not valid" if it is used after
# disposal rather than failing to compile.
$art.Dispose()

Write-Output "wrote $($written.Count) file(s) into $Res"
foreach ($f in $written) {
    $rel = $f.Replace((Resolve-Path $Res).Path + '\', '')
    Write-Output ("  {0,-46} {1,8:N0} B" -f $rel, (Get-Item $f).Length)
}
