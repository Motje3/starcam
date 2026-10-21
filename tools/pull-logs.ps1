# Pulls everything needed to debug a field session off the phone (USB debugging on) into
# field-logs\<timestamp>\ next to the project:
#
#   logs\                     the app's own logs, logcat mirrors, crash files, exit traces
#   sessions\<name>\          each session's meta\ (session.json, session.log) + stack_report.json
#   camera-service.txt        dumpsys media.camera: the camera service's own event history
#   device-logcat.txt         the phone's full log buffer, including the camera HAL's messages
#   exit-info.txt             why recent app processes ended, as the system recorded it
#
# Frames (DNG/sraw) are deliberately not pulled - copy a session folder by hand if you need them.
#
#   powershell -ExecutionPolicy Bypass -File tools\pull-logs.ps1

# Continue, not Stop: in Windows PowerShell 5.1 a native tool's stderr line (adb pulling a file a
# failed session never wrote) becomes a terminating error under Stop. Failures are checked explicitly.
$ErrorActionPreference = 'Continue'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw 'adb not found - run tools\setup-toolchain.ps1 first' }

# Start the adb server detached first: when a piped adb call starts it, the server inherits the
# pipe and PowerShell waits on it forever.
(Start-Process -FilePath $adb -ArgumentList 'start-server' -WindowStyle Hidden -PassThru).WaitForExit()
$state = (& $adb get-state 2>$null)
if ($state -ne 'device') {
    throw 'No phone found. Plug it in over USB, unlock it, and accept the "Allow USB debugging" prompt.'
}

$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $project ("field-logs\" + (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Force $out | Out-Null
$remote = '/sdcard/Android/media/com.wm.starcam/StarCam'

Write-Host "pulling into $out"
& $adb pull "$remote/logs" (Join-Path $out 'logs') | Out-Null

$sessions = & $adb shell "ls $remote/sessions" 2>$null
foreach ($s in $sessions) {
    $s = $s.Trim()
    if (-not $s) { continue }
    $dst = Join-Path $out "sessions\$s"
    New-Item -ItemType Directory -Force (Join-Path $dst 'out') | Out-Null
    & $adb pull "$remote/sessions/$s/meta" $dst 2>$null | Out-Null
    & $adb pull "$remote/sessions/$s/out/stack_report.json" (Join-Path $dst 'out') 2>$null | Out-Null
    & $adb shell "ls -la $remote/sessions/$s/lights $remote/sessions/$s/cache $remote/sessions/$s/out" 2>$null |
        Set-Content -Encoding utf8 (Join-Path $dst 'files.txt')
}

& $adb shell dumpsys media.camera | Set-Content -Encoding utf8 (Join-Path $out 'camera-service.txt')
& $adb logcat -d -b all -v threadtime | Set-Content -Encoding utf8 (Join-Path $out 'device-logcat.txt')
& $adb shell dumpsys activity exit-info com.wm.starcam | Set-Content -Encoding utf8 (Join-Path $out 'exit-info.txt')
& $adb shell dumpsys package com.wm.starcam | Select-String 'versionName|versionCode|lastUpdateTime' |
    Set-Content -Encoding utf8 (Join-Path $out 'app-version.txt')

Write-Host ''
Write-Host "done: $out"
Get-ChildItem $out | ForEach-Object { Write-Host "  $($_.Name)" }
