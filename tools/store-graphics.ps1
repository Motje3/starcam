# The Play Store feature graphic, store\feature-graphic.png (1024 x 500, 24-bit, no
# transparency), drawn from the app's star and font:
#
#   powershell -ExecutionPolicy Bypass -File tools\store-graphics.ps1
#
# The icon is the owner's artwork in store\icon-512.png, also the launcher icon
# (res/drawable-nodpi/ic_launcher_art.png); the screenshots come from tools\store-screens.ps1.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $project 'store'

$night = [System.Drawing.ColorTranslator]::FromHtml('#0A0B14')
$deep = [System.Drawing.ColorTranslator]::FromHtml('#121A38')
$starBlue = [System.Drawing.ColorTranslator]::FromHtml('#7FB4FF')
$starWhite = [System.Drawing.ColorTranslator]::FromHtml('#E8F0FF')
$textDim = [System.Drawing.ColorTranslator]::FromHtml('#AAB6CC')

function Font([string]$file, [float]$px, [System.Drawing.FontStyle]$style = [System.Drawing.FontStyle]::Regular) {
    # The app's Noto Sans is one variable file; GDI+ draws its regular weight, so a heavier one
    # is asked for as Bold.
    $pfc = New-Object System.Drawing.Text.PrivateFontCollection
    $pfc.AddFontFile((Join-Path $project "app\src\main\res\font\$file"))
    $script:keep += , $pfc
    New-Object System.Drawing.Font($pfc.Families[0], $px, $style, [System.Drawing.GraphicsUnit]::Pixel)
}
$script:keep = @()

function Canvas([int]$w, [int]$h, [System.Drawing.Imaging.PixelFormat]$format) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h, $format)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
    @($bmp, $g)
}

function NightSky($g, [int]$w, [int]$h, [int]$seed, [int]$count) {
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point(0, 0)), (New-Object System.Drawing.Point(0, $h)), $night, $deep)
    $g.FillRectangle($bg, 0, 0, $w, $h)
    $rnd = New-Object System.Random($seed)
    for ($i = 0; $i -lt $count; $i++) {
        $x = $rnd.NextDouble() * $w; $y = $rnd.NextDouble() * $h
        $m = [Math]::Pow($rnd.NextDouble(), 3)          # most stars faint, a few bright
        $r = 0.5 + 1.6 * $m
        $a = [int](50 + 205 * $m)
        $c = if ($rnd.NextDouble() -lt 0.3) { $starBlue } else { $starWhite }
        if ($m -gt 0.7) {
            # A soft glow fading to nothing, not a disc.
            $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
            $gr = 6 * $r
            $gp.AddEllipse($x - $gr, $y - $gr, 2 * $gr, 2 * $gr)
            $pg = New-Object System.Drawing.Drawing2D.PathGradientBrush($gp)
            $pg.CenterColor = [System.Drawing.Color]::FromArgb(60, $c)
            $pg.SurroundColors = [System.Drawing.Color[]]@([System.Drawing.Color]::FromArgb(0, $c))
            $g.FillPath($pg, $gp)
        }
        $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb($a, $c))
        $g.FillEllipse($b, $x - $r, $y - $r, 2 * $r, 2 * $r)
    }
}

# The star of the app's notification icon (res/drawable/ic_star_vector.xml, a 108 x 108 viewport).
$StarPaths = @(
    @('M54,24 L58.5,49.5 L84,54 L58.5,58.5 L54,84 L49.5,58.5 L24,54 L49.5,49.5 Z', $starBlue),
    @('M74,32 L75.5,36.5 L80,38 L75.5,39.5 L74,44 L72.5,39.5 L68,38 L72.5,36.5 Z', $starWhite),
    @('M34,68 L35.2,71.8 L39,73 L35.2,74.2 L34,78 L32.8,74.2 L29,73 L32.8,71.8 Z', $starWhite)
)
function DrawStar($g, [float]$x0, [float]$y0, [float]$scale) {
    foreach ($p in $StarPaths) {
        $pts = [regex]::Matches($p[0], '([\d.]+),([\d.]+)') | ForEach-Object {
            New-Object System.Drawing.PointF(($x0 + [float]$_.Groups[1].Value * $scale), ($y0 + [float]$_.Groups[2].Value * $scale))
        }
        $g.FillPolygon((New-Object System.Drawing.SolidBrush($p[1])), [System.Drawing.PointF[]]$pts)
    }
}

# ---- feature graphic: the star and the name on a night sky
$bmp, $g = Canvas 1024 500 ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
NightSky $g 1024 500 7 420
$s = 4.0
DrawStar $g (250 - 54 * $s) (250 - 54 * $s) $s
$title = Font 'noto_sans.ttf' 128 ([System.Drawing.FontStyle]::Bold)
$tag = Font 'noto_sans.ttf' 32
$g.DrawString('Stelli', $title, (New-Object System.Drawing.SolidBrush($starWhite)), 452, 138)
$g.DrawString('Night sky photos from your phone', $tag, (New-Object System.Drawing.SolidBrush($textDim)), 462, 304)
$bmp.Save((Join-Path $out 'feature-graphic.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Host "feature graphic in $out"
