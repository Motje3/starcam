# Installs everything needed to build StarCam from the command line, without Android Studio:
# JDK 17 + Android SDK (platform-tools/adb, platform 35, build-tools 34, NDK 27, CMake 3.22.1).
#
# Everything goes under %LOCALAPPDATA%\Android, which is also where Android Studio looks, so a
# later Studio install reuses it. Safe to re-run: finished steps are skipped.
#
#   powershell -ExecutionPolicy Bypass -File tools\setup-toolchain.ps1

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root = Join-Path $env:LOCALAPPDATA 'Android'
$Sdk = Join-Path $Root 'Sdk'
$Jdk = Join-Path $Root 'jdk17'
$Tmp = Join-Path $Root '_downloads'
New-Item -ItemType Directory -Force $Root, $Sdk, $Tmp | Out-Null

function Fetch([string]$url, [string]$out) {
    if (Test-Path $out) { return }
    Write-Host "downloading $url"
    & curl.exe -L --fail --retry 3 -o "$out.part" $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    Move-Item "$out.part" $out
}

# ---- JDK 17 (Microsoft build, portable zip) ----
if (-not (Test-Path (Join-Path $Jdk 'bin\java.exe'))) {
    $zip = Join-Path $Tmp 'jdk17.zip'
    Fetch 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' $zip
    $x = Join-Path $Tmp 'jdk17-x'
    if (Test-Path $x) { Remove-Item -Recurse -Force $x }
    New-Item -ItemType Directory $x | Out-Null
    & tar.exe -xf $zip -C $x
    $inner = Get-ChildItem $x -Directory | Select-Object -First 1
    if (Test-Path $Jdk) { Remove-Item -Recurse -Force $Jdk }
    Move-Item $inner.FullName $Jdk
    Remove-Item -Recurse -Force $x
}
$env:JAVA_HOME = $Jdk
Write-Host "JDK: $Jdk"

# ---- Android command-line tools (provides sdkmanager) ----
# Pinned to 19.0: from 20+ sdkmanager hands off to the new "Android CLI", which needs its own
# repository endpoint and failed here. 19.0's sdkmanager is the classic, self-contained one.
$cmdline = Join-Path $Sdk 'cmdline-tools\19.0'
if (-not (Test-Path (Join-Path $cmdline 'bin\sdkmanager.bat'))) {
    $file = 'commandlinetools-win-13114758_latest.zip'
    $zip = Join-Path $Tmp $file
    Fetch "https://dl.google.com/android/repository/$file" $zip
    $x = Join-Path $Tmp 'cmdline-x'
    if (Test-Path $x) { Remove-Item -Recurse -Force $x }
    New-Item -ItemType Directory $x | Out-Null
    & tar.exe -xf $zip -C $x
    New-Item -ItemType Directory -Force (Split-Path $cmdline) | Out-Null
    if (Test-Path $cmdline) { Remove-Item -Recurse -Force $cmdline }
    Move-Item (Join-Path $x 'cmdline-tools') $cmdline
    Remove-Item -Recurse -Force $x
}
$sdkmanager = Join-Path $cmdline 'bin\sdkmanager.bat'

# ---- SDK packages (versions pinned by app/build.gradle.kts) ----
$packages = @(
    'platform-tools',
    'platforms;android-35',
    'build-tools;34.0.0',
    'ndk;27.0.12077973',
    'cmake;3.22.1'
)
# PowerShell 5.1 does not pipe text into a .bat reliably, so answer the license prompts from a
# file through cmd's own redirection.
$yesFile = Join-Path $Tmp 'yes.txt'
Set-Content -Encoding ascii $yesFile (@('y') * 40)
Write-Host 'accepting SDK licenses'
& cmd.exe /c "`"$sdkmanager`" --sdk_root=`"$Sdk`" --licenses < `"$yesFile`"" | Out-Null
Write-Host "installing: $($packages -join ', ')"
$pkgArgs = ($packages | ForEach-Object { "`"$_`"" }) -join ' '
& cmd.exe /c "`"$sdkmanager`" --sdk_root=`"$Sdk`" $pkgArgs < `"$yesFile`""
if ($LASTEXITCODE -ne 0) { throw 'sdkmanager failed' }

# ---- Point the project at this SDK ----
$props = Join-Path $PSScriptRoot '..\local.properties'
$sdkFwd = $Sdk -replace '\\', '/'
Set-Content -Encoding ascii $props @(
    '## Local machine paths - not part of the source, do not commit.'
    "sdk.dir=$sdkFwd"
)

Write-Host ''
Write-Host 'Toolchain ready:'
& (Join-Path $Jdk 'bin\java.exe') -version
& (Join-Path $Sdk 'platform-tools\adb.exe') version
Write-Host "SDK: $Sdk"
