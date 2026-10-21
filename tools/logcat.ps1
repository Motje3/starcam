# Live log while testing on the phone over USB. Everything goes to field-logs\live_<time>.txt
# (the whole device log, so camera HAL messages are kept); the console shows only the lines
# that matter for StarCam and the camera stack.
#
#   powershell -ExecutionPolicy Bypass -File tools\logcat.ps1          # Ctrl+C to stop
#   powershell -ExecutionPolicy Bypass -File tools\logcat.ps1 -Clear   # empty the buffer first

param([switch]$Clear)
# Continue, not Stop: in Windows PowerShell 5.1 a native tool's stderr line (adb pulling a file a
# failed session never wrote) becomes a terminating error under Stop. Failures are checked explicitly.
$ErrorActionPreference = 'Continue'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
# Start the adb server detached first: when a piped adb call starts it, the server inherits the
# pipe and PowerShell waits on it forever.
(Start-Process -FilePath $adb -ArgumentList 'start-server' -WindowStyle Hidden -PassThru).WaitForExit()
if ((& $adb get-state 2>$null) -ne 'device') { throw 'No phone on USB (or USB debugging not allowed).' }

$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path $project 'field-logs'
New-Item -ItemType Directory -Force $dir | Out-Null
$file = Join-Path $dir ("live_" + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.txt')
if ($Clear) { & $adb logcat -c }

# StarCam's own tags, the Camera2 client and service, and the usual Qualcomm/OPPO HAL tags.
$interesting = 'StarCam|CameraDevice|CameraCaptureSession|Camera3|CameraService|camera|CamX|CHI|Oplus.*Cam|ImageReader|BufferQueue|AndroidRuntime|FATAL|DEBUG\s*:'
Write-Host "full log -> $file   (Ctrl+C to stop)"
& $adb logcat -v threadtime | Tee-Object -FilePath $file | Select-String -Pattern $interesting
