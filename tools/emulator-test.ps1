# Regression checks on the Android emulator, no phone or night sky needed:
#   self-test   the native engine's synthetic align/stack/calibration checks
#   session     a session started through the adb hook: Back asks before stopping, Finish ends it,
#               Back closes the result (the emulator's camera shows no stars, so it cannot stack)
#   cut-off     the app killed mid-session: the next launch says so
#   stack       a synthetic starfield session (make-test-session.ps1) stacked from Sessions, recorded
#               as shot with the phone upright: 7 of 8 frames, a portrait photo, a 1-frame comparison
#   studio      that session opened in the editing studio, restyled and saved as a new photo
#   replay      that session's "watch it build up" video made, kept in the session and the gallery
#   tonight     the Tonight planner opened from the viewfinder, for a place set with geo fix
#   sky         the sky view: from the planner onto the viewfinder, closed, and from its own chip
#   autopilot   two sessions under a simulated sky (SkySim): cloud left out, waited out and
#               replaced, a focus drift refocused, the planned 12 good frames stacked; then dawn
#               ending a session early
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
    # The Android 16 image's graphics crash its system_server on screencap (see DEBUGGING.md).
    if ($script:apiLevel -ge 36) { return }
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
$script:apiLevel = 0
$sdkProp = ((A shell getprop ro.build.version.sdk 2>$null) -join '').Trim()
if ($sdkProp -match '^\d+$') { $script:apiLevel = [int]$sdkProp }
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
    & (Join-Path $PSScriptRoot 'make-test-session.ps1') -Name $name -Rotation 90 -Push -Serial $Serial | Out-Null
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
        # The page scrolls up to the new photo and its "Done" notice when the stack lands.
        $done = WaitFor 'Done: the photo above is updated' 600
        Shot 'stack-done'
    }
    $outFiles = A shell ls "/sdcard/Android/media/com.wm.starcam/StarCam/sessions/$name/out/"
    $jpg = $outFiles -match "starcam_$name.jpg"
    # "1 frame vs stacked": one frame developed like the stack, beside the photo.
    $single = $outFiles -match 'single_frame.jpg'
    $local = Join-Path $shots 'session.json'
    A pull "/sdcard/Android/media/com.wm.starcam/StarCam/sessions/$name/meta/session.json" $local 2>&1 | Out-Null
    $used = try { (Get-Content $local -Raw | ConvertFrom-Json).result.framesUsed } catch { $null }
    # 8 frames, one recorded as bumped: 7 stacked. The sensor frames are landscape; recorded as
    # shot upright, the photo is turned to portrait.
    $jpgLocal = Join-Path $shots 'stack.jpg'
    A pull "/sdcard/Android/media/com.wm.starcam/StarCam/sessions/$name/out/starcam_$name.jpg" $jpgLocal 2>&1 | Out-Null
    $portrait = $false
    if (Test-Path $jpgLocal) {
        Add-Type -AssemblyName System.Drawing
        $im = [System.Drawing.Image]::FromFile($jpgLocal)
        $portrait = $im.Height -gt $im.Width
        $im.Dispose()
    }
    Check 'stack' ($done -and [bool]$jpg -and $used -eq 7 -and $portrait -and [bool]$single) "done=$done jpeg=$([bool]$jpg) framesUsed=$used portrait=$portrait single=$([bool]$single)"
}

# ---- the editing studio on that session: preview, a change, saved as a new photo
if (Wanted 'studio') {
    if (-not $name) {
        Check 'studio' $false 'needs the stack check in the same run'
    } else {
        # The Edit card sits under the photo: down from the top, or back up from the Stack button.
        for ($k = 0; $k -lt 4 -and -not ((AllText) -match '\|Edit\|'); $k++) { A shell input swipe 636 2000 636 1200 300; Start-Sleep -Milliseconds 700 }
        for ($k = 0; $k -lt 6 -and -not ((AllText) -match '\|Edit\|'); $k++) { A shell input swipe 636 900 636 2200 300; Start-Sleep -Milliseconds 700 }
        Tap 'Edit' | Out-Null
        $opened = WaitFor 'HOLD TO COMPARE' 90
        Start-Sleep -Seconds 2
        Shot 'studio-open'
        Tap 'Colour' | Out-Null
        Tap 'Nightfall' | Out-Null
        Start-Sleep -Seconds 3
        Shot 'studio-nightfall'
        Tap 'Save as new photo' | Out-Null
        $saved = WaitFor 'Saved to the gallery as a new photo' 180
        $edit = (A shell ls "/sdcard/Android/media/com.wm.starcam/StarCam/sessions/$name/out/") -match "starcam_${name}_edit.jpg"
        Back
        Check 'studio' ($opened -and $saved -and [bool]$edit) "opened=$opened saved=$saved file=$([bool]$edit)"
    }
}

# ---- the replay video of that session: made on the phone, in the session and the gallery
if (Wanted 'replay') {
    $sessions = '/sdcard/Android/media/com.wm.starcam/StarCam/sessions'
    $rname = $name
    if ($script:apiLevel -ge 36) {
        # Android 16 does not let an app open pictures adb put into its folder, so there the video
        # is made from a live session's own replay pictures (the session check's, from this run
        # or an earlier one).
        $rname = A shell ls $sessions | Where-Object { $_ -match '^\d{8}_\d{6}_astro$' } |
            Where-Object { ((A shell ls "$sessions/$_") -join ',') -match 'lights.*replay|replay.*lights' } |
            Sort-Object | Select-Object -Last 1
        if ($rname) {
            Launch | Out-Null
            Tap 'Sessions gallery' | Out-Null
            Start-Sleep -Seconds 2
            $t = [datetime]::ParseExact($rname.Substring(0, 15), 'yyyyMMdd_HHmmss', $null)
            $tile = Nodes | Where-Object { $_.text -match ('Today . ' + $t.ToString('HH:mm')) } | Select-Object -First 1
            if ($tile) { $c = Center $tile; A shell input tap $c[0] $c[1]; Start-Sleep -Seconds 3 }
        }
    }
    if (-not $rname) {
        Check 'replay' $false 'needs the stack check (on Android 16 a live session) in the same run'
    } else {
        $name = $rname
        # Only frames read back from this video count.
        A shell run-as com.wm.starcam rm -rf cache/replay_check 2>&1 | Out-Null
        for ($k = 0; $k -lt 6 -and -not ((AllText) -match '\|Make video\|'); $k++) { A shell input swipe 636 2000 636 1200 300; Start-Sleep -Milliseconds 700 }
        $card = Tap 'Make video'
        $made = WaitFor 'Saved to the gallery \(Movies' 240
        Shot 'replay'
        $mp4 = (A shell ls -l "$sessions/$name/out/stack_replay.mp4") -join ''
        $size = if ($mp4 -match '\s(\d+)\s+\d{4}-\d\d-\d\d') { [int64]$matches[1] } else { 0 }
        $gallery = ((A shell content query --uri content://media/external/video/media --projection _display_name) -join '|') -match "starcam_${name}_replay"
        # The video and the frames the app read back from it (debug builds keep them in its cache).
        A pull "$sessions/$name/out/stack_replay.mp4" (Join-Path $shots 'stack_replay.mp4') 2>&1 | Out-Null
        $check = Join-Path $shots 'replay-check'
        New-Item -ItemType Directory -Force $check | Out-Null
        $readBack = 0
        foreach ($f in (A shell run-as com.wm.starcam ls cache/replay_check)) {
            if ($f -match '^at_\d+ms\.jpg$') {
                # Copied by the device's shell: PowerShell would mangle binary output.
                A shell "run-as com.wm.starcam cat cache/replay_check/$f > /data/local/tmp/$f" 2>&1 | Out-Null
                A pull "/data/local/tmp/$f" (Join-Path $check $f) 2>&1 | Out-Null
                $readBack++
            }
        }
        Check 'replay' ($card -and $made -and $size -gt 20000 -and $gallery -and $readBack -eq 3) "card=$card made=$made bytes=$size gallery=$gallery read back=$readBack"
    }
}

# ---- the Tonight planner from the viewfinder chip, with the emulator placed in Amsterdam
if (Wanted 'tonight') {
    A shell pm grant com.wm.starcam android.permission.ACCESS_COARSE_LOCATION 2>&1 | Out-Null
    & $adbExe -s $Serial emu geo fix 4.90 52.37 2>&1 | Out-Null
    Launch | Out-Null
    $chip = Nodes | Where-Object { $_.'content-desc' -like 'Tonight planner*' } | Select-Object -First 1
    $planned = $false
    if ($chip) {
        $c = Center $chip
        A shell input tap $c[0] $c[1]
        $planned = WaitFor 'Show me the sky' 40
        Start-Sleep -Seconds 1
        Shot 'tonight'
        Back
    }
    Check 'tonight' ([bool]$chip -and $planned) "chip=$([bool]$chip) planned=$planned"
}

# ---- the sky view: from the planner's button onto the viewfinder, closed, and from its own chip
if (Wanted 'sky') {
    A shell pm grant com.wm.starcam android.permission.ACCESS_COARSE_LOCATION 2>&1 | Out-Null
    Launch | Out-Null
    $chip = Nodes | Where-Object { $_.'content-desc' -like 'Tonight planner*' } | Select-Object -First 1
    $fromPlanner = $false
    if ($chip) {
        $c = Center $chip
        A shell input tap $c[0] $c[1]
        if (WaitFor 'Show me the sky' 40) {
            $b = Nodes | Where-Object { $_.text -like 'Show me the sky*' } | Select-Object -First 1
            $c = Center $b
            A shell input tap $c[0] $c[1]
            $fromPlanner = WaitFor 'The sky (now|at)' 20
            Start-Sleep -Seconds 2
            Shot 'sky'
        }
    }
    $closed = $null -ne (Nodes | Where-Object { $_.'content-desc' -eq 'Close the sky view' } | Select-Object -First 1)
    if ($closed) {
        $n = Nodes | Where-Object { $_.'content-desc' -eq 'Close the sky view' } | Select-Object -First 1
        $c = Center $n
        A shell input tap $c[0] $c[1]
        Start-Sleep -Seconds 1
        $closed = -not ((AllText) -match 'The sky (now|at)')
    }
    $fromChip = $false
    if (Tap 'Sky view') {
        $fromChip = WaitFor 'The sky now' 15
        $n = Nodes | Where-Object { $_.'content-desc' -eq 'Close the sky view' } | Select-Object -First 1
        if ($n) { $c = Center $n; A shell input tap $c[0] $c[1] }
    }
    Check 'sky' ($fromPlanner -and $closed -and $fromChip) "planner=$fromPlanner closed=$closed chip=$fromChip"
}

# ---- the autopilot under a simulated sky (camera/SkySim.kt): cloud over light attempts 5-8, the
# lens's focus drifting at attempt 13; then a second session into dawn
function NewestSession {
    $name = ((A shell ls -t /sdcard/Android/media/com.wm.starcam/StarCam/sessions) | Select-Object -First 1).Trim()
    $raw = (A shell cat "/sdcard/Android/media/com.wm.starcam/StarCam/sessions/$name/meta/session.json") -join ''
    try { $raw | ConvertFrom-Json } catch { $null }
}
if (Wanted 'autopilot') {
    Launch | Out-Null
    A shell am start -n com.wm.starcam/.MainActivity --ez session true --es sim "'frames=12;exp=300;clouds=5-8;drift=13:0.06;darks=auto'" 2>&1 | Out-Null
    $done = WaitFor 'Saved to Gallery|Couldn''t stack' 300
    $said = (AllText) -match 'What the autopilot did'
    Shot 'autopilot'
    $s = NewestSession
    $ap = $s.autopilot
    $used = $s.result.framesUsed
    $cloud = [int]$ap.leftOutCloud; $soft = [int]$ap.leftOutSoft; $refocused = [int]$ap.refocused; $checks = [int]$ap.checks
    Back
    # Dawn: the sky brightening 60% a frame from attempt 8 ends a 16-frame plan early.
    Launch | Out-Null
    A shell am start -n com.wm.starcam/.MainActivity --ez session true --es sim "'frames=16;exp=300;dawn=8;darks=auto'" 2>&1 | Out-Null
    WaitFor 'Saved to Gallery|Couldn''t stack' 300 | Out-Null
    $d = NewestSession
    $dawn = [string]$d.autopilot.finished -match 'brighter'
    $dawnUsed = $d.result.framesUsed
    Back
    $ok = $done -and $said -and $used -eq 12 -and $cloud -eq 2 -and $soft -eq 2 -and $refocused -eq 1 -and $checks -ge 2 -and $dawn -and $dawnUsed -lt 16
    Check 'autopilot' $ok "done=$done said=$said used=$used cloud=$cloud soft=$soft refocused=$refocused checks=$checks dawn=$dawn dawnUsed=$dawnUsed"
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
