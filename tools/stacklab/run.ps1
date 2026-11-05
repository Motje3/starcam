# Builds stacklab for the x86_64 emulator, runs it there over a session's cached frames and pulls
# what it wrote into field-logs\stacklab\<session>_<time>\. The emulator must be running
# (tools\emulator-test.ps1 starts it). The session is a folder as the phone keeps it, with
# cache\frame_*.sraw (and meta\session.json for the stacking options).
#
#   powershell -ExecutionPolicy Bypass -File tools\stacklab\run.ps1 -Session <sessionDir>
#   powershell -ExecutionPolicy Bypass -File tools\stacklab\run.ps1 -Session <sessionDir> -Mode align
#
# align: each frame against the first as the capture aligns them (align.txt, align_NN.csv).
# stack: the whole re-stack, and the stars' width in it against the reference frame by region
#        (stack.txt, sheet.png: the brightest star per region, frame left, stack right).

param([Parameter(Mandatory = $true)][string]$Session, [ValidateSet('align', 'stack', 'both')][string]$Mode = 'both',
      [string]$Serial = 'emulator-5554', [switch]$NoBuild, [switch]$NoPush)
$ErrorActionPreference = 'Continue'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$cmake = Join-Path $sdk 'cmake\3.22.1\bin\cmake.exe'
$ninja = Join-Path $sdk 'cmake\3.22.1\bin\ninja.exe'
$ndk = Join-Path $sdk 'ndk\27.0.12077973'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$build = Join-Path $project 'build\stacklab'
$Session = (Resolve-Path $Session).Path
$name = Split-Path $Session -Leaf

if (-not $NoBuild) {
    & $cmake -S $PSScriptRoot -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" -DANDROID_ABI=x86_64 `
        -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release | Out-Null
    & $cmake --build $build | Select-String -Pattern 'error|warning' | ForEach-Object { $_.Line }
    if ($LASTEXITCODE -ne 0) { throw 'stacklab build failed' }
}

# The stacking options the session was made with, its turn included (the post-processing finds
# the ground by it); stacklab turns the TIFFs back to measure them.
$options = '{}'
$rotation = 0
$meta = Join-Path $Session 'meta\session.json'
if (Test-Path $meta) {
    $cfg = (Get-Content $meta -Raw | ConvertFrom-Json).header.stackConfig
    if ($cfg) {
        if ($null -ne $cfg.rotation) { $rotation = [int]$cfg.rotation }
        $options = $cfg | ConvertTo-Json -Compress
    }
}
$phoneTiff = Join-Path $Session 'out\master_linear16.tiff'
$optFile = Join-Path $env:TEMP 'stacklab-options.json'
[IO.File]::WriteAllText($optFile, $options)

$remote = '/data/local/tmp/stacklab'
$frames = "$remote/frames/$name"
if (-not $NoPush) {
    & $adb -s $Serial shell "rm -rf $frames $frames.tiff; mkdir -p $frames"
    Get-ChildItem (Join-Path $Session 'cache') -Filter 'frame_*.sraw' | ForEach-Object {
        & $adb -s $Serial push $_.FullName "$frames/" | Out-Null
    }
    if (Test-Path $phoneTiff) { & $adb -s $Serial push $phoneTiff "$frames.tiff" | Out-Null }
}
$phoneArgs = if (Test-Path $phoneTiff) { "$frames.tiff" } else { '' }
& $adb -s $Serial shell "rm -rf $remote/out; mkdir -p $remote/out"
& $adb -s $Serial push (Join-Path $build 'stacklab') "$remote/stacklab" | Out-Null
& $adb -s $Serial push $optFile "$remote/options.json" | Out-Null
& $adb -s $Serial shell chmod 755 "$remote/stacklab"

$dest = Join-Path $project ("field-logs\stacklab\${name}_" + (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Force $dest | Out-Null
if ($Mode -ne 'stack') {
    & $adb -s $Serial shell "$remote/stacklab align $frames $remote/out" | Tee-Object (Join-Path $dest 'align.txt')
}
if ($Mode -ne 'align') {
    & $adb -s $Serial shell "$remote/stacklab stack $frames $remote/out $remote/options.json $rotation $phoneArgs" |
        Where-Object { $_ -notmatch '^\s+add \d+:' } | Tee-Object (Join-Path $dest 'stack.txt')
}
# The re-stacks' big outputs stay on the emulator; the reports and pictures come along, and the
# finished photo of the stack (final.png, upright like the phone's).
if ($Mode -ne 'align') {
    $raw = Join-Path $dest 'final_rgb.raw'
    & $adb -s $Serial pull "$remote/out/stack/out/final_rgb.raw" $raw 2>&1 | Out-Null
    $result = Get-Content (Join-Path $dest 'stack.txt') | Where-Object { $_ -like 'result:*' } | Select-Object -First 1
    if ((Test-Path $raw) -and $result -match '"width":(\d+),"height":(\d+)') {
        $w = [int]$matches[1]; $h = [int]$matches[2]
        $bytes = [IO.File]::ReadAllBytes($raw)
        Add-Type -AssemblyName System.Drawing
        $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $data = $bmp.LockBits((New-Object System.Drawing.Rectangle 0, 0, $w, $h),
            [System.Drawing.Imaging.ImageLockMode]::WriteOnly, $bmp.PixelFormat)
        $row = New-Object byte[] ($w * 3)
        for ($y = 0; $y -lt $h; $y++) {
            [Array]::Copy($bytes, $y * $w * 3, $row, 0, $w * 3)
            for ($x = 0; $x -lt $w * 3; $x += 3) { $t = $row[$x]; $row[$x] = $row[$x + 2]; $row[$x + 2] = $t }
            [Runtime.InteropServices.Marshal]::Copy($row, 0, [IntPtr]($data.Scan0.ToInt64() + $y * $data.Stride), $w * 3)
        }
        $bmp.UnlockBits($data)
        $bmp.Save((Join-Path $dest 'final.png'), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        Remove-Item $raw
    }
}
& $adb -s $Serial shell "rm -f $remote/out/*/out/*.raw $remote/out/*/out/*.tiff"
& $adb -s $Serial pull "$remote/out/." $dest | Out-Null
Add-Type -AssemblyName System.Drawing
Get-ChildItem $dest -Filter *.bmp | ForEach-Object {
    $bmp = [System.Drawing.Bitmap]::FromFile($_.FullName)
    $bmp.Save([IO.Path]::ChangeExtension($_.FullName, '.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Remove-Item $_.FullName
}
Write-Host "results: $dest"
