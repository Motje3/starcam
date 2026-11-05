# The store screenshots, taken on the emulator with a clean status bar: Android's demo mode shows
# 21:30, a full battery and signal and no notification icons. They land in field-logs\store-shots,
# where tools\store-graphics.ps1 frames them. The app must be installed (either build) and the
# emulator running (tools\emulator-test.ps1 starts it); the planner is set to Amsterdam.
#
#   powershell -ExecutionPolicy Bypass -File tools\store-shots.ps1
#
# The emulator's camera shows a test scene, not the sky: screens over the viewfinder (the sky view)
# are left out. Real results come from the phone.

param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Continue'
$adbExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $project 'field-logs\store-shots'
New-Item -ItemType Directory -Force $out | Out-Null

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
# Taps the first node whose text or description matches the wildcard $like.
function Tap([string]$like) {
    $n = Nodes | Where-Object { $_.text -like $like -or $_.'content-desc' -like $like } | Select-Object -First 1
    if (-not $n) { Write-Host "not found: $like"; return $false }
    $c = Center $n
    A shell input tap $c[0] $c[1]
    Start-Sleep -Milliseconds 900
    $true
}
# The planner: the moon on the top row opens tonight's card, whose button opens it.
function Planner { (Tap 'Tonight*') -and ((Tap 'Plan tonight') -or (Tap 'Set your place')) }
function WaitFor([string]$pattern, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ((AllText) -match $pattern) { return $true }
        Start-Sleep -Milliseconds 700
    }
    $false
}
function ScrollTo([string]$pattern) {
    for ($k = 0; $k -lt 8 -and -not ((AllText) -match $pattern); $k++) {
        A shell input swipe 636 2200 636 1000 300
        Start-Sleep -Milliseconds 700
    }
}
function Back { A shell input keyevent 4; Start-Sleep -Milliseconds 900 }
function Shot([string]$name) {
    Start-Sleep -Seconds 1
    A shell screencap -p /data/local/tmp/shot.png 2>&1 | Out-Null
    A pull /data/local/tmp/shot.png (Join-Path $out "$name.png") 2>&1 | Out-Null
    Write-Host "shot $name"
}
function Demo([string[]]$extras) { A shell am broadcast -a com.android.systemui.demo @extras 2>&1 | Out-Null }
function Launch {
    A shell am force-stop com.wm.stelli
    A shell am start -n com.wm.stelli/com.wm.starcam.MainActivity 2>&1 | Out-Null
    WaitFor 'Start astro capture|Skip' 20 | Out-Null
    if ((AllText) -match '\|Skip\|') { Tap 'Skip' | Out-Null }
    WaitFor 'Start astro capture' 15 | Out-Null
}

A shell settings put global sysui_demo_allowed 1 2>&1 | Out-Null
Demo @('-e', 'command', 'enter')
Demo @('-e', 'command', 'clock', '-e', 'hhmm', '2130')
Demo @('-e', 'command', 'battery', '-e', 'level', '100', '-e', 'plugged', 'false')
Demo @('-e', 'command', 'network', '-e', 'wifi', 'show', '-e', 'level', '4', '-e', 'fully', 'true')
Demo @('-e', 'command', 'network', '-e', 'mobile', 'show', '-e', 'datatype', 'none', '-e', 'level', '4', '-e', 'fully', 'true')
Demo @('-e', 'command', 'notifications', '-e', 'visible', 'false')

A shell pm grant com.wm.stelli android.permission.ACCESS_COARSE_LOCATION 2>&1 | Out-Null
& $adbExe -s $Serial emu geo fix 4.90 52.37 2>&1 | Out-Null
Launch

# The planner, and its list of what is worth a shot tonight.
if (Planner) {
    WaitFor 'Show me the sky' 40 | Out-Null
    Shot 'tonight'
    ScrollTo 'best at \d\d:\d\d'
    A shell input swipe 636 1700 636 1300 300
    Shot 'tonight-worth'
    # The sky calendar, at the foot of the planner.
    # To the planner's end, where its calendar entry sits clear of the gesture bar (a tap there
    # goes nowhere).
    for ($k = 0; $k -lt 8; $k++) { A shell input swipe 636 2200 636 1000 300; Start-Sleep -Milliseconds 700 }
    if ((Tap 'Sky calendar') -and (WaitFor 'New moon' 40)) { Shot 'calendar' } else { Write-Host 'the calendar did not open' }
}

# Find: the list of what there is to find, from its top.
Launch
if (Tap 'Find in the sky*') {
    WaitFor 'Find in the sky\|' 20 | Out-Null
    Shot 'find'
}

# The light map (the dark-sky map).
Launch
if (Tap 'Light map*') {
    WaitFor 'Your sky' 30 | Out-Null
    Shot 'darksky'
    Back
}

Demo @('-e', 'command', 'exit')
Write-Host "screenshots in $out"
