# Builds the debug APK with the toolchain from setup-toolchain.ps1, copies it to
# D:\Code\StarCam.apk, and installs it on the phone when one is plugged in over USB.
#
#   powershell -ExecutionPolicy Bypass -File tools\build.ps1            # build + install
#   powershell -ExecutionPolicy Bypass -File tools\build.ps1 -NoInstall
#   powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Clean

param([switch]$NoInstall, [switch]$Clean)
# Continue, not Stop: in Windows PowerShell 5.1 a native tool's stderr line (adb pulling a file a
# failed session never wrote) becomes a terminating error under Stop. Failures are checked explicitly.
$ErrorActionPreference = 'Continue'

$Root = Join-Path $env:LOCALAPPDATA 'Android'
$env:JAVA_HOME = Join-Path $Root 'jdk17'
$env:ANDROID_HOME = Join-Path $Root 'Sdk'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'No toolchain - run tools\setup-toolchain.ps1 first'
}

$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $project
try {
    $tasks = @('assembleDebug')
    if ($Clean) { $tasks = @('clean') + $tasks }
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }

    $apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
    Copy-Item $apk (Join-Path (Split-Path $project) 'StarCam.apk') -Force
    Write-Host "APK: $apk (copy at $(Join-Path (Split-Path $project) 'StarCam.apk'))"

    # The installed app is signed with the debug key from the original PC (backed up in
    # D:\Personal\configs\.android). An APK signed with any other key cannot update it, and the
    # only way around that is uninstalling - which deletes every session under Android/media.
    $expected = 'e1f4bb65c77a308cfa7d8ee2976b5b012c4df5af32996ccac3aab07599bc04c4'
    $apksigner = Join-Path $env:ANDROID_HOME 'build-tools\34.0.0\apksigner.bat'
    $signer = (& $apksigner verify --print-certs $apk 2>$null | Select-String 'SHA-256 digest: (\w+)').Matches.Groups[1].Value
    if ($signer -ne $expected) {
        Write-Warning "APK is signed with $signer, not the key the phone's StarCam was installed with."
        Write-Warning 'Restore D:\Personal\configs\.android\debug.keystore to %USERPROFILE%\.android\ and rebuild.'
        Write-Warning 'Installing this build would require uninstalling StarCam, which DELETES all sessions.'
        $NoInstall = $true
    }

    if (-not $NoInstall) {
        # Start the adb server detached first: when a piped adb call starts it, the server
        # inherits the pipe and PowerShell waits on it forever.
        (Start-Process -FilePath $adb -ArgumentList 'start-server' -WindowStyle Hidden -PassThru).WaitForExit()
        $connected = & $adb devices | Select-String "`tdevice$"
        if ($connected) {
            & $adb install -r $apk
            if ($LASTEXITCODE -ne 0) { throw 'install failed' }
            Write-Host 'installed on the phone'
        } else {
            Write-Host 'no phone on USB - skipped install'
        }
    }
} finally {
    Pop-Location
}
