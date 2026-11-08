# The app's main screens from the emulator, for design work (a redesign brief, the store's
# pictures). Each lands in field-logs\ui-shots, or -Out, as the emulator's 1272 x 2800 screen:
#   1-main        the viewfinder, as the app opens
#   2-capturing   a session running (the emulator's camera shows no stars; it is finished after)
#   3-photo       a stacked photo's page: a synthetic session of Orion (make-test-session.ps1)
#   4-studio      that photo in the editing studio
#   5-tonight     the Tonight planner, for Amsterdam
#   6-gallery     the sessions gallery
# and in extra\: Find over the viewfinder, the sky view, the sky calendar, the light map,
# Settings, the Adjust sheet, the Manual mode, night vision and the first-launch cards (from
# clearing the app's data, last). The status bar is in demo mode (21:30, full battery and
# signal). The emulator must be running with the app installed, either build
# (tools\emulator-test.ps1 starts it). -Clean deletes the emulator's sessions first, so the
# gallery shows only this run's.
#
#   powershell -ExecutionPolicy Bypass -File tools\ui-shots.ps1 -Clean -Out "$env:USERPROFILE\Desktop\Stelli screens"
#
# The emulator's camera shows a test scene (grey and green blocks) where a phone shows the sky.

param(
    [string]$Serial = 'emulator-5554',
    [string]$Out = (Join-Path $PSScriptRoot '..\field-logs\ui-shots'),
    [switch]$Clean
)
$ErrorActionPreference = 'Continue'
$adbExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
New-Item -ItemType Directory -Force $Out, (Join-Path $Out 'extra') | Out-Null
$sessions = '/sdcard/Android/media/com.wm.stelli/Stelli/sessions'

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
# Taps the first node whose text or description matches the wildcard $like, case and all. A label
# under a button is not the button: tap a button by its description ("Adjust*", "Night vision off").
function Tap([string]$like) {
    $n = Nodes | Where-Object { $_.text -clike $like -or $_.'content-desc' -clike $like } | Select-Object -First 1
    if (-not $n) { Write-Host "not found: $like"; return $false }
    $c = Center $n
    A shell input tap $c[0] $c[1]
    Start-Sleep -Milliseconds 900
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
function Down { A shell input swipe 636 2000 636 1200 300; Start-Sleep -Milliseconds 700 }
function Back { A shell input keyevent 4; Start-Sleep -Milliseconds 900 }
# Night vision, the sky view and Settings are in Quick tools, the square at the top right. A switch
# leaves them open, so -Switch closes them after it (and a tile not there closes them too).
function Tool([string]$like, [switch]$Switch) {
    if (-not (Tap 'Quick tools')) { return $false }
    $ok = Tap $like
    if ($Switch -or -not $ok) { Back }
    $ok
}
# The planner: the moon on the top row opens tonight's card, whose button opens it.
function Planner { (Tap 'Tonight*') -and ((Tap 'Plan tonight') -or (Tap 'Set your place')) }
function Shot([string]$name) {
    Start-Sleep -Seconds 1
    A shell screencap -p /data/local/tmp/shot.png 2>&1 | Out-Null
    A pull /data/local/tmp/shot.png (Join-Path $Out "$name.png") 2>&1 | Out-Null
    Write-Host "shot $name"
}
function Demo([string[]]$extras) { A shell am broadcast -a com.android.systemui.demo @extras 2>&1 | Out-Null }
function Launch {
    A shell am force-stop com.wm.stelli
    A shell am start -n com.wm.stelli/com.wm.starcam.MainActivity 2>&1 | Out-Null
    WaitFor 'Start astro capture|Skip|Allow camera' 20 | Out-Null
    if ((AllText) -match '\|Skip\|') { Tap 'Skip' | Out-Null }
    WaitFor 'Start astro capture' 15 | Out-Null
}
# The shutter, once the camera is ready and the shutter enabled (a release build has no adb hook).
function StartSession {
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if (Nodes | Where-Object { $_.'content-desc' -eq 'Start astro capture' -and $_.enabled -eq 'true' }) { break }
        Start-Sleep -Milliseconds 700
    }
    Start-Sleep -Milliseconds 1500
    Tap 'Start astro capture' | Out-Null
}

if ($Clean) {
    if ($Serial -notlike 'emulator-*') { throw '-Clean is for the emulator only: it deletes every session' }
    A shell rm -rf $sessions 2>&1 | Out-Null
}
foreach ($p in 'CAMERA', 'POST_NOTIFICATIONS', 'ACCESS_COARSE_LOCATION') {
    A shell pm grant com.wm.stelli "android.permission.$p" 2>&1 | Out-Null
}
& $adbExe -s $Serial emu geo fix 4.90 52.37 2>&1 | Out-Null
A shell settings put global sysui_demo_allowed 1 2>&1 | Out-Null
Demo @('-e', 'command', 'enter')
Demo @('-e', 'command', 'clock', '-e', 'hhmm', '2130')
Demo @('-e', 'command', 'battery', '-e', 'level', '100', '-e', 'plugged', 'false')
Demo @('-e', 'command', 'network', '-e', 'wifi', 'show', '-e', 'level', '4', '-e', 'fully', 'true')
Demo @('-e', 'command', 'network', '-e', 'mobile', 'show', '-e', 'datatype', 'none', '-e', 'level', '4', '-e', 'fully', 'true')
Demo @('-e', 'command', 'notifications', '-e', 'visible', 'false')

# 1, 2: the viewfinder, then a session running, finished early. The planner is opened once first,
# so its place (the emulator's geo fix) is known and tonight's card has tonight to show.
Launch
if (Planner) {
    WaitFor 'Show me the sky' 40 | Out-Null
    Launch
    WaitFor 'Bortle' 15 | Out-Null
}
Shot '1-main'
StartSession
if (WaitFor 'Finish & stack now' 180) {
    Start-Sleep -Seconds 6
    Shot '2-capturing'
    Tap 'Finish & stack now' | Out-Null
    WaitFor "Couldn't stack|Saved to Gallery|RAWs saved" 240 | Out-Null
} else { Write-Host 'the session did not reach its capturing step' }
Back

# 3, 4: a stacked photo of Orion, and the studio.
$now = Get-Date
$name = $now.ToString('yyyyMMdd_HHmmss') + '_astro'
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'make-test-session.ps1') -Name $name -Rotation 90 -Sky orion -Push -Serial $Serial | Out-Null
Launch
Tap 'Sessions gallery' | Out-Null
Start-Sleep -Seconds 2
$tile = Nodes | Where-Object { $_.text -match ('Today . ' + $now.ToString('HH:mm')) } | Select-Object -First 1
if ($tile) {
    $c = Center $tile
    A shell input tap $c[0] $c[1]
    Start-Sleep -Seconds 3
    for ($k = 0; $k -lt 5 -and -not ((AllText) -match '\|Stack\|'); $k++) { A shell input swipe 636 2200 636 900 300; Start-Sleep -Milliseconds 700 }
    Tap 'Stack' | Out-Null
    # The page scrolls up to the new photo when the stack lands.
    if (WaitFor 'Done: the photo above is updated' 600) {
        Start-Sleep -Seconds 2
        Shot '3-photo'
        for ($k = 0; $k -lt 4 -and -not ((AllText) -match '\|Edit\|'); $k++) { Down }
        if ((Tap 'Edit') -and (WaitFor 'Save as new photo' 90)) {
            Start-Sleep -Seconds 4
            Shot '4-studio'
        }
    } else { Write-Host 'the stack did not finish' }
} else { Write-Host 'the new session is not in the gallery' }

# 5, 6: the Tonight planner; the gallery with that photo.
Launch
if (Planner) {
    WaitFor 'Show me the sky' 40 | Out-Null
    Shot '5-tonight'
}
Launch
if (Tap 'Sessions gallery') {
    Start-Sleep -Seconds 3
    Shot '6-gallery'
}

# Extra: Find over the viewfinder and the sky view behind it.
Launch
if (Tap 'Find in the sky*') {
    WaitFor 'Find in the sky\|' 20 | Out-Null
    Shot 'extra\find'
    Back
    Start-Sleep -Seconds 2
    Shot 'extra\skyview'
}
# The sky calendar, at the planner's end.
Launch
if (Planner) {
    WaitFor 'Show me the sky' 40 | Out-Null
    for ($k = 0; $k -lt 8; $k++) { A shell input swipe 636 2200 636 1000 300; Start-Sleep -Milliseconds 700 }
    if ((Tap 'Sky calendar') -and (WaitFor 'New moon' 40)) { Shot 'extra\calendar' }
}
# The light map, Settings, the Adjust sheet, the Manual mode, night vision.
Launch
if (Tap 'Light map*') {
    WaitFor 'Your sky' 30 | Out-Null
    Shot 'extra\lightmap'
}
Launch
if (Tap 'Quick tools') {
    Shot 'extra\quick-tools'
    Back
}
Launch
if (Tool 'Settings') {
    Start-Sleep -Seconds 2
    Shot 'extra\settings'
}
Launch
if (Tap 'Adjust*') {
    Start-Sleep -Seconds 2
    Shot 'extra\adjust'
}
Launch
if (Tap 'Manual') {
    Start-Sleep -Seconds 2
    Shot 'extra\manual-mode'
    Tap 'Stars' | Out-Null
}
Launch
if (Tool 'Night vision off' -Switch) {
    Start-Sleep -Seconds 2
    Shot 'extra\night'
    Tool 'Night vision on' -Switch | Out-Null
}
# The first-launch cards: the app's data cleared, last.
A shell pm clear com.wm.stelli 2>&1 | Out-Null
A shell am start -n com.wm.stelli/com.wm.starcam.MainActivity 2>&1 | Out-Null
if (WaitFor '\|Skip\|' 20) { Shot 'extra\first-launch' }
A shell am force-stop com.wm.stelli

Demo @('-e', 'command', 'exit')
Write-Host "screens in $Out"
