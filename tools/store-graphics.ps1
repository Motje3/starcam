# The Play Store graphics, drawn from the app's own icon and font:
#   store\icon-512.png            the 512 x 512 store icon (the launcher icon, full square: Play rounds it)
#   store\feature-graphic.png     the 1024 x 500 feature graphic (24-bit, no transparency)
#   store\screenshots\*.png       phone screenshots on a 1080 x 1920 canvas (9:16) with a caption:
#                                 the emulator's and the phone's are 2.2:1, and Play takes at most 2:1
#
#   powershell -ExecutionPolicy Bypass -File tools\store-graphics.ps1
#
# The screenshots come from tools\store-shots.ps1 (field-logs\store-shots); a phone screenshot goes
# in by adding a line to $Screens. Photos in the listing must be real Stelli results.

param(
    [string]$Shots = (Join-Path $PSScriptRoot '..\field-logs\store-shots')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $project 'store'
New-Item -ItemType Directory -Force (Join-Path $out 'screenshots') | Out-Null

# Screenshot file (in $Shots, or a full path) and its caption, in store order.
$Screens = @(
    @('darksky.png', 'How dark is your sky, and where is it darker?'),
    @('tonight-worth.png', 'What is worth a shot tonight, and when'),
    @('find.png', 'Find galaxies, planets and the North Star'),
    @('calendar.png', 'Meteor showers, eclipses and new moons where you are'),
    @('tonight.png', 'Plan the night: darkness, the Moon and the Milky Way')
)

$night = [System.Drawing.ColorTranslator]::FromHtml('#0A0B14')
$deep = [System.Drawing.ColorTranslator]::FromHtml('#121A38')
$starBlue = [System.Drawing.ColorTranslator]::FromHtml('#7FB4FF')
$starWhite = [System.Drawing.ColorTranslator]::FromHtml('#E8F0FF')
$textDim = [System.Drawing.ColorTranslator]::FromHtml('#AAB6CC')

function Font([string]$file, [float]$px) {
    # One collection per weight: in one collection the weights would share a family name.
    $pfc = New-Object System.Drawing.Text.PrivateFontCollection
    $pfc.AddFontFile((Join-Path $project "app\src\main\res\font\$file"))
    $script:keep += , $pfc
    New-Object System.Drawing.Font($pfc.Families[0], $px, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
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

# The launcher icon's star (res/drawable/ic_star_vector.xml, a 108 x 108 viewport).
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

# ---- icon: the viewport's middle 96 units across 512 px, so the star keeps clear of Play's rounding
$bmp, $g = Canvas 512 512 ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g.Clear($night)
$s = 512 / 96
DrawStar $g (-6 * $s) (-6 * $s) $s
$bmp.Save((Join-Path $out 'icon-512.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# ---- feature graphic: the star and the name on a night sky
$bmp, $g = Canvas 1024 500 ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
NightSky $g 1024 500 7 420
$s = 4.0
DrawStar $g (250 - 54 * $s) (250 - 54 * $s) $s
$title = Font 'geist_semibold.ttf' 128
$tag = Font 'geist_regular.ttf' 32
$g.DrawString('Stelli', $title, (New-Object System.Drawing.SolidBrush($starWhite)), 452, 138)
$g.DrawString('Night sky photos from your phone', $tag, (New-Object System.Drawing.SolidBrush($textDim)), 462, 304)
$bmp.Save((Join-Path $out 'feature-graphic.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# ---- screenshots: each on a 1080 x 1920 night sky, its caption above it
Remove-Item (Join-Path $out 'screenshots\*.png') -ErrorAction SilentlyContinue  # numbered afresh
$caption = Font 'geist_semibold.ttf' 60
$n = 0
foreach ($sc in $Screens) {
    $src = if ([IO.Path]::IsPathRooted($sc[0])) { $sc[0] } else { Join-Path $Shots $sc[0] }
    if (-not (Test-Path $src)) { Write-Host "missing: $src"; continue }
    $n++
    $bmp, $g = Canvas 1080 1920 ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    NightSky $g 1080 1920 (100 + $n) 260
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = [System.Drawing.StringAlignment]::Center
    $g.DrawString($sc[1], $caption, (New-Object System.Drawing.SolidBrush($starWhite)),
        (New-Object System.Drawing.RectangleF(70, 70, 940, 200)), $fmt)
    $shot = [System.Drawing.Image]::FromFile($src)
    $h = 1560; $w = [int]($shot.Width * $h / $shot.Height)
    $x = [int]((1080 - $w) / 2); $y = 300
    # Rounded like a phone's screen, with a hairline edge.
    $r = 44
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($x, $y, 2 * $r, 2 * $r, 180, 90)
    $path.AddArc($x + $w - 2 * $r, $y, 2 * $r, 2 * $r, 270, 90)
    $path.AddArc($x + $w - 2 * $r, $y + $h - 2 * $r, 2 * $r, 2 * $r, 0, 90)
    $path.AddArc($x, $y + $h - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $path.CloseFigure()
    $g.SetClip($path)
    $g.DrawImage($shot, $x, $y, $w, $h)
    $g.ResetClip()
    $g.DrawPath((New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(90, $starBlue), 3)), $path)
    $shot.Dispose()
    $name = '{0}-{1}' -f $n, [IO.Path]::GetFileName($src)
    $bmp.Save((Join-Path $out "screenshots\$name"), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}
Write-Host "store graphics in $out ($n screenshots)"
