# The Play Store phone screenshots: a big icon and a short headline over a large phone that runs
# off the bottom edge. Each is tools\store-screens.html, filled in here and drawn by Edge
# (headless), from the emulator's screens that tools\store-shots.ps1 takes (field-logs\store-shots).
# They land in store\screenshots as 1080 x 1920 24-bit PNGs (Play takes no transparency), numbered
# in store order.
#
#   powershell -ExecutionPolicy Bypass -File tools\store-screens.ps1
#
# Photos in the listing must be real Stelli results: a phone screenshot of a finished photo goes
# in by adding a line to $Screens with its full path.

param(
    [string]$Shots = (Join-Path $PSScriptRoot '..\field-logs\store-shots')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $project 'store\screenshots'
$work = Join-Path $Shots 'prepared'
New-Item -ItemType Directory -Force $out, $work | Out-Null
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path $edge)) { throw "Edge not found at $edge" }
$edgeProfile = Join-Path $work 'edge-profile'

# The icons, on a 24 x 24 grid: Material Symbols shapes (Apache 2.0), the meteor drawn here.
$Icons = @{
    pin      = '<path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"/>'
    sparkles = '<path d="M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z"/>'
    compass  = '<path d="M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1s-.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z"/>'
    meteor   = '<path d="M2.5 21.5 15.4 5 19 8.6Z" opacity=".75"/><circle cx="17.2" cy="6.8" r="3.6"/><circle cx="6" cy="5" r="1.1"/><circle cx="20.5" cy="17" r="1"/>'
}

# In store order. Title: the headline, its key words in <em> (shown in the bright accent).
# A1, A2: the accent, bright and deep, as r,g,b. Skip: keep the screen's top bar (rows 0 to the
# first number) and show the rest from the second, as if scrolled. Dark: the emulator's camera
# shows a test scene, not the night: black out that area (x0, y0, x1, y1) except the sheet over
# it (x0, y0, x1, y1, corner radius). Coordinates are the emulator's 1272 x 2800 screen.
$Screens = @(
    @{ File = 'darksky.png'; Title = 'Find <em>dark skies</em> near you'; Icon = 'pin'
       A1 = '52,211,153'; A2 = '14,165,233' },
    @{ File = 'tonight-worth.png'; Title = 'Know what to shoot <em>tonight</em>'; Icon = 'sparkles'
       A1 = '196,181,253'; A2 = '124,58,237'; Skip = @(314, 1090) },
    @{ File = 'find.png'; Title = 'Find <em>galaxies</em> and planets'; Icon = 'compass'
       A1 = '125,211,252'; A2 = '37,99,235'; Dark = @{ Area = @(30, 380, 1242, 1970); Keep = @(84, 438, 1187, 2414, 52) } },
    @{ File = 'calendar.png'; Title = 'Never miss a <em>meteor shower</em>'; Icon = 'meteor'
       A1 = '252,211,77'; A2 = '234,88,12' }
)

function RoundRect([float]$x0, [float]$y0, [float]$x1, [float]$y1, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x0, $y0, 2 * $r, 2 * $r, 180, 90)
    $p.AddArc($x1 - 2 * $r, $y0, 2 * $r, 2 * $r, 270, 90)
    $p.AddArc($x1 - 2 * $r, $y1 - 2 * $r, 2 * $r, 2 * $r, 0, 90)
    $p.AddArc($x0, $y1 - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $p.CloseFigure()
    $p
}

# The screen as the phone in the picture shows it: scrolled or darkened where asked.
function Prepare($sc) {
    $src = if ([IO.Path]::IsPathRooted($sc.File)) { $sc.File } else { Join-Path $Shots $sc.File }
    $img = [System.Drawing.Bitmap]::FromFile($src)
    $w = $img.Width; $h = $img.Height
    $px = [System.Drawing.GraphicsUnit]::Pixel
    $rgb = [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
    if ($sc.Skip) {
        $top, $from = $sc.Skip
        $bmp = New-Object System.Drawing.Bitmap($w, ($top + $h - $from), $rgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $w, $top)), 0, 0, $w, $top, $px)
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, $top, $w, ($h - $from))), 0, $from, $w, ($h - $from), $px)
    } else {
        $bmp = New-Object System.Drawing.Bitmap($w, $h, $rgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $w, $h)), 0, 0, $w, $h, $px)
    }
    if ($sc.Dark) {
        $x0, $y0, $x1, $y1 = $sc.Dark.Area
        $g.FillRectangle([System.Drawing.Brushes]::Black, $x0, $y0, ($x1 - $x0), ($y1 - $y0))
        $k = $sc.Dark.Keep
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.SetClip((RoundRect $k[0] $k[1] $k[2] $k[3] $k[4]))
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $w, $h)), 0, 0, $w, $h, $px)
        $g.ResetClip()
    }
    $g.Dispose(); $img.Dispose()
    $path = Join-Path $work ('screen-' + [IO.Path]::GetFileName($src))
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $path
}

$template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'store-screens.html'))
# The app's own face: Noto Sans, one variable file for every weight.
$fontDir = Join-Path $project 'app\src\main\res\font'
$template = $template.Replace('{{NOTO_SANS}}', [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $fontDir 'noto_sans.ttf'))))

Remove-Item (Join-Path $out '*.png') -ErrorAction SilentlyContinue  # numbered afresh
$n = 0
foreach ($sc in $Screens) {
    $n++
    $shot = Prepare $sc
    $html = Join-Path $work "screen-$n.html"
    $page = $template.Replace('{{A1}}', $sc.A1).Replace('{{A2}}', $sc.A2).Replace('{{ICON}}', $Icons[$sc.Icon]).
        Replace('{{TITLE}}', $sc.Title).Replace('{{SHOT}}', ([Uri]$shot).AbsoluteUri).Replace('{{SEED}}', [string](7919 * $n))
    [IO.File]::WriteAllText($html, $page)
    $drawn = Join-Path $work "screen-$n-edge.png"
    Remove-Item $drawn -ErrorAction SilentlyContinue
    $edgeArgs = @('--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
        '--no-default-browser-check', "--user-data-dir=$edgeProfile-$n",
        '--force-device-scale-factor=1', '--window-size=1080,1920', '--virtual-time-budget=4000',
        "--screenshot=$drawn", ([Uri]$html).AbsoluteUri)
    # Not Start-Process -Wait: that waits for every process Edge starts, and its updater can stay
    # behind. The picture is written by a child process, so wait for the file to stop growing.
    $null = Start-Process -FilePath $edge -ArgumentList $edgeArgs -NoNewWindow -PassThru
    $deadline = (Get-Date).AddSeconds(60); $size = -1
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $drawn) {
            $now = (Get-Item $drawn).Length
            if ($now -gt 0 -and $now -eq $size) { break }
            $size = $now
        }
        Start-Sleep -Milliseconds 500
    }
    Get-CimInstance Win32_Process -Filter "Name = 'msedge.exe'" |
        Where-Object { $_.CommandLine -like "*$edgeProfile*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if (-not (Test-Path $drawn)) { throw "Edge drew nothing for $($sc.File)" }
    # Play wants 24-bit PNGs: no alpha channel.
    $img = [System.Drawing.Image]::FromFile($drawn)
    if ($img.Width -ne 1080 -or $img.Height -ne 1920) { throw "Edge drew $($img.Width) x $($img.Height) for $($sc.File)" }
    $bmp = New-Object System.Drawing.Bitmap(1080, 1920, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, 1080, 1920)), 0, 0, 1080, 1920, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose(); $img.Dispose()
    $name = '{0}-{1}' -f $n, [IO.Path]::GetFileName($sc.File)
    $bmp.Save((Join-Path $out $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "drew $name"
}
Write-Host "screenshots in $out ($n)"
