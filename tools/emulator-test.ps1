# Regression checks on the Android emulator, no phone or night sky needed:
#   self-test   the native engine's synthetic align/stack/calibration checks
#   session     a session started through the adb hook: Back asks before stopping, Finish ends it,
#               Back closes the result (the emulator's camera shows no stars, so it cannot stack)
#   cut-off     the app killed mid-session: the next launch says so
#   stack       a synthetic starfield session (make-test-session.ps1) stacked from Sessions
#   background  Home releases the camera, coming back reopens it
#   swipe       swiping the app out of recents mid-session leaves the session running
#
#   powershell -ExecutionPolicy Bypass -File tools\emulator-test.ps1            # starts starcam_ui
#   powershell -ExecutionPolicy Bypass -File tools\emulator-test.ps1 -Build -Only stack,session
#
# The AVD (starcam_ui: API 34, 1272x2800 @ 560 dpi, back camera "emulated" with RAW) is described
# in DEBUGGING.md. Debug builds only: the session hook is ignored by release builds.

param(
    [string]$Avd = 'starcam_ui',
    [string]$Serial = 'emulator-5554',
    [switch]$Build,
    [string[]]$Only = @(),
    # Deletes every session on the emulator first (each run leaves a few; the AVD's storage is small).
    [switch]$Clean
)
$ErrorActionPreference = 'Continue'
# With -File, "-Only a,b" arrives as one string.
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adbExe = Join-Path $sdk 'platform-tools\adb.exe'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$shots = Join-Path $project 'field-logs\emulator-test'
New-Item -ItemType Directory -Force $shots | Out-Null
$results = [ordered]@{}

function A { & $adbExe -s $Serial @args }
function Nodes {
    $null = A shell uiautomator dump /data/local/tmp/ui.xml 2>&1
    $raw = (A shell cat /data/local/tmp/ui.xml) -join ''
    try { ([xml]$raw).SelectNodes('//node') } catch { @() }
}
function AllText { (Nodes | ForEach-Object { $_.text; $_.'content-desc' }) -join '|' }
function Center($n) {
    if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        @([int](([int]$matches[1] + [int]$matches[3]) / 2), [int](([int]$matches[2] + [int]$matches[4]) / 2))
    }
}
function Tap([string]$label) {
    $n = Nodes | Where-Object { $_.text -eq $label -or $_.'content-desc' -eq $label } | Select-Object -First 1
    if (-not $n) { return $false }
    $c = Center $n
    A shell input tap $c[0] $c[1]
    Start-Sleep -Milliseconds 800
    $true
}
function WaitFor([string]$pattern, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ((AllText) -match $pattern) { return $true }
        Start-Sleep -Milliseconds 700
    }
    $false
}
function Back { A shell input keyevent 4; Start-Sleep -Milliseconds 800 }
function Shot([string]$name) {
    A shell screencap -p /data/local/tmp/shot.png 2>&1 | Out-Null
    A pull /data/local/tmp/shot.png (Join-Path $shots "$name.png") 2>&1 | Out-Null
}
function Launch {
    A shell am force-stop com.wm.starcam
    A shell am start -n com.wm.starcam/.MainActivity 2>&1 | Out-Null
    WaitFor 'Start astro capture|Skip|Allow camera' 20 | Out-Null
    if ((AllText) -match '\|Skip\|') { Tap 'Skip' | Out-Null }
    if ((AllText) -match 'Allow camera') { Tap 'Allow camera' | Out-Null; Start-Sleep 2; Tap 'While using the app' | Out-Null }
    if ((AllText) -match 'notifications') { Tap 'Allow' | Out-Null }
    WaitFor 'Start astro capture' 15
}
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    $results[$name] = if ($ok) { "PASS $detail" } else { "FAIL $detail" }
    Write-Host ("{0,-11} {1}" -f $name, $results[$name])
}
function Wanted([string]$name) { $Only.Count -eq 0 -or $Only -contains $name }

# ---- emulator and app
if (-not ((& $adbExe devices) -match "^$Serial\s+device")) {
    Write-Host "starting the emulator ($Avd)"
    Start-Process (Join-Path $sdk 'emulator\emulator.exe') -ArgumentList '-avd', $Avd, '-no-snapshot', '-no-boot-anim' -WindowStyle Minimized
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline -and -not ((A shell getprop sys.boot_completed 2>$null) -match '1')) { Start-Sleep 5 }
}
if ($Build) {
    $env:JAVA_HOME = Join-Path $env:LOCALAPPDATA 'Android\jdk17'
    $env:ANDROID_HOME = $sdk
    Push-Location $project
    & .\gradlew.bat assembleDebug --console=plain | Out-Null
    Pop-Location
}
if ($Clean) {
    if ($Serial -notlike 'emulator-*') { throw '-Clean is for the emulator only: it deletes every session' }
    A shell rm -rf /sdcard/Android/media/com.wm.starcam/StarCam/sessions 2>&1 | Out-Null
}
A install -r (Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
A shell pm grant com.wm.starcam android.permission.CAMERA 2>&1 | Out-Null
A shell pm grant com.wm.starcam android.permission.POST_NOTIFICATIONS 2>&1 | Out-Null

# ---- self-test
if (Wanted 'self-test') {
    Launch | Out-Null
    Tap 'Settings' | Out-Null
    for ($k = 0; $k -lt 4 -and -not ((AllText) -match '\|Advanced\|'); $k++) { A shell input swipe 1100 590 150 590 300; Start-Sleep -Milliseconds 700 }
    Tap 'Advanced' | Out-Null
    for ($k = 0; $k -lt 8 -and -not ((AllText) -match 'Engine self-test'); $k++) { A shell input swipe 636 2200 636 1000 300; Start-Sleep -Milliseconds 700 }
    Tap 'Engine self-test' | Out-Null
    Tap 'Run self-test' | Out-Null
    $ok = WaitFor 'Self-test finished: PASS' 600
    Check 'self-test' $ok
}

# ---- session through the hook
if (Wanted 'session') {
    Launch | Out-Null
    A shell am start -n com.wm.starcam/.MainActivity --ez session true 2>&1 | Out-Null
    $capturing = WaitFor 'Finish & stack now' 180
    Back
    $asked = (AllText) -match 'Stop this capture'
    Tap 'Keep going' | Out-Null
    Tap 'Finish & stack now' | Out-Null
    $ended = WaitFor "Couldn't stack|Saved to Gallery|RAWs saved" 240
    Shot 'session-result'
    Back
    $closed = WaitFor 'Start astro capture' 10
    Check 'session' ($capturing -and $asked -and $ended -and $closed) "capturing=$capturing asked=$asked ended=$ended closed=$closed"
}

# ---- cut off mid-session
if (Wanted 'cut-off') {
    Launch | Out-Null
    A shell am start -n com.wm.starcam/.MainActivity --ez session true 2>&1 | Out-Null
    $capturing = WaitFor 'Finish & stack now' 180
    Start-Sleep -Seconds 4
    A shell am force-stop com.wm.starcam
    Launch | Out-Null
    $notice = WaitFor 'Last session was cut off' 10
    Shot 'cut-off-notice'
    if ($notice) { Tap 'Dismiss' | Out-Null }
    Check 'cut-off' ($capturing -and $notice)
}

# ---- synthetic session stacked from Sessions
if (Wanted 'stack') {
    # Named for now, so it is the newest session and the first tile in the list.
    $now = Get-Date
    $name = $now.ToString('yyyyMMdd_HHmmss') + '_astro'
    & (Join-Path $PSScriptRoot 'make-test-session.ps1') -Name $name -Push -Serial $Serial | Out-Null
    Launch | Out-Null
    Tap 'Sessions gallery' | Out-Null
    Start-Sleep -Seconds 2
    $tile = Nodes | Where-Object { $_.text -match ('Today . ' + $now.ToString('HH:mm')) } | Select-Object -First 1
    $done = $false
    if ($tile) {
        $c = Center $tile
        A shell input tap $c[0] $c[1]
        Start-Sleep -Seconds 3
        for ($k = 0; $k -lt 5 -and -not ((AllText) -match '\|Stack\|'); $k++) { A shell input swipe 636 2200 636 900 300; Start-Sleep -Milliseconds 700 }
        Tap 'Stack' | Out-Null
        $done = WaitFor 'Done: the photo above is updated' 600
        Shot 'stack-done'
    }
    $jpg = (A shell ls "/sdcard/Android/media/com.wm.starcam/StarCam/sessions/$name/out/") -match "starcam_$name.jpg"
    $local = Join-Path $shots 'session.json'
    A pull "/sdcard/Android/media/com.wm.starcam/StarCam/sessions/$name/meta/session.json" $local 2>&1 | Out-Null
    $used = try { (Get-Content $local -Raw | ConvertFrom-Json).result.framesUsed } catch { $null }
    # 8 frames, one recorded as bumped: 7 stacked.
    Check 'stack' ($done -and [bool]$jpg -and $used -eq 7) "done=$done jpeg=$([bool]$jpg) framesUsed=$used"
}

# ---- Home releases the camera, return reopens it
if (Wanted 'background') {
    Launch | Out-Null
    A shell logcat -c
    A shell input keyevent KEYCODE_HOME
    Start-Sleep -Seconds 3
    $released = (A shell logcat -d -s StarCam:* 2>$null) -match 'releasing the camera'
    A shell am start -n com.wm.starcam/.MainActivity 2>&1 | Out-Null
    Start-Sleep -Seconds 5
    $reopened = (A shell logcat -d -s StarCam:* 2>$null) -match 'Resume: opening camera'
    $ready = WaitFor 'Start astro capture' 10
    Check 'background' ([bool]$released -and [bool]$reopened -and $ready)
}

# ---- swipe out of recents mid-session
if (Wanted 'swipe') {
    Launch | Out-Null
    A shell am start -n com.wm.starcam/.MainActivity --ez session true 2>&1 | Out-Null
    $capturing = WaitFor 'Finish & stack now' 180
    $pid1 = (A shell pidof com.wm.starcam) -join ''
    A shell input keyevent KEYCODE_APP_SWITCH
    Start-Sleep -Seconds 2
    A shell input swipe 636 1400 636 150 250
    Start-Sleep -Seconds 2
    A shell input keyevent KEYCODE_HOME
    A shell logcat -c
    Start-Sleep -Seconds 6
    $frames = (A shell logcat -d -s StarCam:* 2>$null) -match 'light [0-9]+/[0-9]+ ok'
    $pid2 = (A shell pidof com.wm.starcam) -join ''
    A shell am start -n com.wm.starcam/.MainActivity 2>&1 | Out-Null
    $back = WaitFor 'Finish & stack now' 10
    Tap 'Finish & stack now' | Out-Null
    WaitFor "Couldn't stack|Saved to Gallery|RAWs saved" 240 | Out-Null
    Back
    Check 'swipe' ($capturing -and $pid1 -eq $pid2 -and [bool]$frames -and $back) "same process=$($pid1 -eq $pid2) kept capturing=$([bool]$frames) screens back=$back"
}

Write-Host ''
$failed = @($results.Values | Where-Object { $_ -like 'FAIL*' }).Count
Write-Host ("{0} of {1} passed - screenshots in {2}" -f ($results.Count - $failed), $results.Count, $shots)
if ($failed -gt 0) { exit 1 }
