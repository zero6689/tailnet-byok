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
    [int]$WhiteThreshold = 235
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Source)) { throw "source image not found: $Source" }
if (-not (Test-Path $Res)) { throw "resource directory not found: $Res" }
if ($MarkFraction -le 0 -or $MarkFraction -gt 1) { throw "MarkFraction must be in (0, 1]" }

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

$written = @()

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
