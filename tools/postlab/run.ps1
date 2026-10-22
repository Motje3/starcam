# Builds postlab for the x86_64 emulator, runs it there and pulls the pictures (PNG) into
# field-logs\postlab\<time>\. The emulator must be running (tools\emulator-test.ps1 starts it).
#
#   powershell -ExecutionPolicy Bypass -File tools\postlab\run.ps1                    # scene, classic + v2
#   powershell -ExecutionPolicy Bypass -File tools\postlab\run.ps1 -LabArgs '--nofg classic'
#   powershell -ExecutionPolicy Bypass -File tools\postlab\run.ps1 -Tiff <session>\out\master_linear16.tiff
#
# Every variant writes <name>_small.png (the whole frame at 1/4) and three 1:1 crops (horizon, a
# dense part of the Milky Way, plain sky). On the scene it also prints the metrics from lab.cpp.

param([string]$Serial = 'emulator-5554', [string]$LabArgs = '', [string]$Tiff = '', [switch]$NoBuild)
$ErrorActionPreference = 'Continue'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$cmake = Join-Path $sdk 'cmake\3.22.1\bin\cmake.exe'
$ninja = Join-Path $sdk 'cmake\3.22.1\bin\ninja.exe'
$ndk = Join-Path $sdk 'ndk\27.0.12077973'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$build = Join-Path $project 'build\postlab'

if (-not $NoBuild) {
    & $cmake -S $PSScriptRoot -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" -DANDROID_ABI=x86_64 `
        -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release | Out-Null
    & $cmake --build $build | Select-String -Pattern 'error|warning' | ForEach-Object { $_.Line }
    if ($LASTEXITCODE -ne 0) { throw 'postlab build failed' }
}

$remote = '/data/local/tmp/postlab'
& $adb -s $Serial shell "rm -rf $remote; mkdir -p $remote/out"
& $adb -s $Serial push (Join-Path $build 'postlab') "$remote/postlab" | Out-Null
& $adb -s $Serial shell chmod 755 "$remote/postlab"
if ($Tiff) {
    & $adb -s $Serial push $Tiff "$remote/in.tiff" | Out-Null
    & $adb -s $Serial shell "$remote/postlab tiff $remote/in.tiff $remote/out $LabArgs"
} else {
    & $adb -s $Serial shell "$remote/postlab scene $remote/out $LabArgs"
}

$dest = Join-Path $project ("field-logs\postlab\" + (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Force $dest | Out-Null
& $adb -s $Serial pull "$remote/out/." $dest | Out-Null
Add-Type -AssemblyName System.Drawing
Get-ChildItem $dest -Filter *.bmp | ForEach-Object {
    $bmp = [System.Drawing.Bitmap]::FromFile($_.FullName)
    $bmp.Save([IO.Path]::ChangeExtension($_.FullName, '.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Remove-Item $_.FullName
}
Write-Host "results: $dest"
