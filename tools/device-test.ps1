# One-command camera check on the phone over USB:
#   1. backs up the app's logs and session metadata (pull-logs.ps1)
#   2. installs the current debug APK as an in-place update (sessions and settings are kept)
#   3. runs the Diagnostics camera test through the adb hook while recording the whole device
#      log, camera HAL included
#   4. waits for the result and pulls the logs again
#
# Cover the lens (or lay the phone face down) first: the test shoots real long exposures.
#
#   powershell -ExecutionPolicy Bypass -File tools\device-test.ps1
#   powershell -ExecutionPolicy Bypass -File tools\device-test.ps1 -Frames 5 -ExposureMs 20000 -Recovery
#
# Debug switches (map on every frame, frame duration, preview stream) are set in the app's
# Settings -> Advanced; this script uses whatever is set there.

param(
    [int]$Frames = 5,
    [int]$ExposureMs = 20000,
    [int]$Iso = 800,
    [switch]$Recovery,
    [switch]$NoWork,
    [switch]$SkipInstall,
    # Per-run overrides of the Settings -> Advanced switches (otherwise the phone's settings apply).
    [switch]$NoFresh,        # keep one session through long exposures (reproduces the old hang)
    [switch]$NoPreview,      # RAW-only capture session
    [switch]$FrameDuration,  # set SENSOR_FRAME_DURATION explicitly
    [switch]$MapEveryFrame   # lens shading map on every frame
)
# Continue, not Stop: in Windows PowerShell 5.1 a native tool's stderr line (adb pulling a file a
# failed session never wrote) becomes a terminating error under Stop. Failures are checked explicitly.
$ErrorActionPreference = 'Continue'

$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'

(Start-Process -FilePath $adb -ArgumentList 'start-server' -WindowStyle Hidden -PassThru).WaitForExit()
if ((& $adb get-state 2>$null) -ne 'device') { throw 'No phone on USB (or USB debugging not allowed).' }

Write-Host '1/4 backing up logs and session metadata'
& (Join-Path $PSScriptRoot 'pull-logs.ps1') | Out-Null

if (-not $SkipInstall) {
    Write-Host '2/4 installing the debug build (in-place update)'
    $expected = 'e1f4bb65c77a308cfa7d8ee2976b5b012c4df5af32996ccac3aab07599bc04c4'
    $env:JAVA_HOME = Join-Path $env:LOCALAPPDATA 'Android\jdk17'
    $signer = (& (Join-Path $sdk 'build-tools\34.0.0\apksigner.bat') verify --print-certs $apk 2>$null |
        Select-String 'SHA-256 digest: (\w+)').Matches.Groups[1].Value
    if ($signer -ne $expected) { throw "APK signed with $signer - would need an uninstall that deletes all sessions. Fix the keystore first (DEBUGGING.md)." }
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'install failed' }
}

$dir = Join-Path $project 'field-logs'
New-Item -ItemType Directory -Force $dir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$log = Join-Path $dir "devicetest_$stamp.txt"
Write-Host "3/4 camera test: $Frames x $($ExposureMs / 1000)s ISO $Iso, recovery=$($Recovery.IsPresent), work=$(-not $NoWork) - full log -> $log"
# The verdict is read from the app's own log on the phone: grep runs there, only matches travel.
$appLogs = '/sdcard/Android/media/com.wm.starcam/StarCam/logs'
function Get-Verdicts { @(& $adb shell "grep -hE 'camera test (finished|aborted)' $appLogs/app_*.log 2>/dev/null") }
$before = (Get-Verdicts).Count

& $adb logcat -c
$logcat = Start-Process -FilePath $adb -ArgumentList 'logcat', '-v', 'threadtime' `
    -RedirectStandardOutput $log -WindowStyle Hidden -PassThru
$extra = @()
if ($NoFresh) { $extra += '--ez', 'fresh', 'false' }
if ($NoPreview) { $extra += '--ez', 'preview', 'false' }
if ($FrameDuration) { $extra += '--ez', 'fd', 'true' }
if ($MapEveryFrame) { $extra += '--ez', 'lsc', 'true' }
& $adb shell am start -n com.wm.starcam/.MainActivity --ez camtest true `
    --ei frames $Frames --ei exp_ms $ExposureMs --ei iso $Iso `
    --ez work $((-not $NoWork).ToString().ToLower()) --ez recovery $($Recovery.IsPresent.ToString().ToLower()) `
    @extra | Out-Null

# Worst case per frame: 2x exposure + 15 s timeout, times 4 attempts with repairs.
$deadline = (Get-Date).AddSeconds(90 + $Frames * (4 * (2 * $ExposureMs / 1000 + 20)))
$result = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $v = Get-Verdicts
    if ($v.Count -gt $before) { $result = $v[-1]; break }
}
Start-Sleep -Seconds 2
Stop-Process -Id $logcat.Id -ErrorAction SilentlyContinue

Write-Host '4/4 pulling logs'
& (Join-Path $PSScriptRoot 'pull-logs.ps1') | Out-Null

Write-Host ''
if ($result) { Write-Host "RESULT: $($result -replace '.*\[camtest\] ', '')" }
else { Write-Host 'RESULT: no verdict before the deadline - see the log' }
Write-Host "camera trace: Select-String -Path '$log' -Pattern 'StarCam'"
