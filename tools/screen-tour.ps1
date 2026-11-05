# Every screen of the app for a design review, from the emulator: each screen and state it can
# show, a long page shot in parts from top to bottom. Into numbered folders under -Out
# (field-logs\screen-tour by default), with "all screens.html" to look through them in a browser:
#   01 first launch   the intro cards (replayed from Settings > Interface), the camera question
#   02 main screen    each mode with its Adjust sheet, the sky view, Find
#   03 a session      each step of a capture under a simulated sky, the stop question, the photo;
#                     the cover-the-lens question and the stack the emulator's camera cannot make;
#                     the notice after a session was cut off
#   04 photo page     a stacked photo of Orion: 1 frame vs stacked, what is in it, the replay video,
#                     the timelapse, the studio's tabs, the delete question; a meteor watch's meteor
#   05 gallery        (empty, with -Clean)
#   06 tonight        the planner, a place by its coordinates, the sky calendar, a night planned
#   07 light map      the map with its panel at each height, the layers menu
#   08 settings       every tab, a setting explained, the search, Advanced's dialogs and tools
#   09 night vision   the main screens in red
# The status bar is in demo mode (21:30, full battery and signal), the emulator placed in
# Amsterdam. It must be running with a debug build installed (tools\emulator-test.ps1 starts it
# and installs one): the session steps need its adb hook and simulated sky. -Only "04,08" shoots
# those folders again; -Clean deletes the emulator's sessions first. About 40 minutes in all.
#
#   powershell -ExecutionPolicy Bypass -File tools\screen-tour.ps1 -Out "$env:USERPROFILE\Desktop\Stelli screens\all screens"
#
# The emulator's camera shows a test scene (coloured blocks) where a phone shows the sky.

param(
    [string]$Serial = 'emulator-5554',
    [string]$Out = (Join-Path $PSScriptRoot '..\field-logs\screen-tour'),
    [string[]]$Only = @(),
    [switch]$Clean
)
$ErrorActionPreference = 'Continue'
# With -File, "-Only a,b" arrives as one string; from PowerShell, 04 can arrive as 4.
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ } | ForEach-Object { '{0:00}' -f [int]$_ })
$adbExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$sessions = '/sdcard/Android/media/com.wm.stelli/Stelli/sessions'
New-Item -ItemType Directory -Force $Out | Out-Null
$Out = (Resolve-Path $Out).Path
$tabs = 'Exposure', 'Focus', 'Capture', 'Autopilot', 'Stacking', 'Look', 'Motion', 'Interface', 'Advanced'

function A { & $adbExe -s $Serial @args }
function Nodes {
    $null = A shell uiautomator dump /data/local/tmp/ui.xml 2>&1
    $raw = (A shell cat /data/local/tmp/ui.xml) -join ''
    try { ([xml]$raw).SelectNodes('//node') } catch { @() }
}
function AllText { (Nodes | ForEach-Object { $_.text; $_.'content-desc' }) -join '|' }
function Bounds($n) {
    if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        @([int]$matches[1], [int]$matches[2], [int]$matches[3], [int]$matches[4])
    }
}
function Center($n) { $b = Bounds $n; @([int](($b[0] + $b[2]) / 2), [int](($b[1] + $b[3]) / 2)) }
# The first node whose text or description matches the wildcard $like, case and all. A label under
# a button is not the button: tap a button by its description ("Adjust*", "Night vision off").
function FindNode([string]$like) {
    Nodes | Where-Object { $_.text -clike $like -or $_.'content-desc' -clike $like } | Select-Object -First 1
}
function TapNode($n) { $c = Center $n; A shell input tap $c[0] $c[1]; Start-Sleep -Milliseconds 900 }
function Tap([string]$like) {
    $n = FindNode $like
    if (-not $n) { Write-Host "    not found: $like"; return $false }
    TapNode $n
    $true
}
function WaitFor([string]$pattern, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ((AllText) -match $pattern) { return $true }
        Start-Sleep -Milliseconds 600
    }
    $false
}
function Back { A shell input keyevent 4; Start-Sleep -Milliseconds 900 }
function Demo([string[]]$extras) { A shell am broadcast -a com.android.systemui.demo @extras 2>&1 | Out-Null }
function Launch {
    A shell am force-stop com.wm.stelli
    A shell am start -n com.wm.stelli/com.wm.starcam.MainActivity 2>&1 | Out-Null
    WaitFor 'Start \w+ capture|\|Skip\|' 20 | Out-Null
    if ((AllText) -match '\|Skip\|') { Tap 'Skip' | Out-Null }
    WaitFor 'Start \w+ capture' 15 | Out-Null
}
# A session through the adb hook (debug builds), under a simulated sky when $sim is given.
function StartSession([string]$sim) {
    if ($sim) {
        A shell am start -n com.wm.stelli/com.wm.starcam.MainActivity --ez session true --es sim "'$sim'" 2>&1 | Out-Null
    } else {
        A shell am start -n com.wm.stelli/com.wm.starcam.MainActivity --ez session true 2>&1 | Out-Null
    }
}
function CloseSky { $n = FindNode 'Close the sky view'; if ($n) { TapNode $n } }
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

# ---- the pictures: one folder per part of the app, numbered in the order they were taken
$script:dir = $Out
$script:count = 0
$script:started = @{}
function Wanted([string]$section) { $Only.Count -eq 0 -or $Only -contains $section.Substring(0, 2) }
function Section([string]$name) {
    $script:dir = Join-Path $Out $name
    New-Item -ItemType Directory -Force $script:dir | Out-Null
    # A folder shot again replaces its pictures.
    if (-not $script:started[$name]) {
        Get-ChildItem $script:dir -Filter *.png | Remove-Item
        $script:started[$name] = $true
        $script:count = 0
    } else {
        $script:count = @(Get-ChildItem $script:dir -Filter *.png).Count
    }
    Write-Host $name
}
function Grab { A shell screencap -p /data/local/tmp/shot.png 2>&1 | Out-Null }
function Keep([string]$what) {
    $script:count++
    A pull /data/local/tmp/shot.png (Join-Path $script:dir ('{0:00} {1}.png' -f $script:count, $what)) 2>&1 | Out-Null
    Write-Host "  $what"
}
function Shot([string]$what) { Start-Sleep -Milliseconds 1000; Grab; Keep $what }

# ---- long pages: the largest part that scrolls, moved most of its height at a time
function Scroller {
    Nodes | Where-Object { $_.scrollable -eq 'true' } |
        Where-Object { $b = Bounds $_; ($b[3] - $b[1]) -ge 500 } |
        Sort-Object { $b = Bounds $_; ($b[2] - $b[0]) * ($b[3] - $b[1]) } -Descending |
        Select-Object -First 1
}
function Layout { (Nodes | ForEach-Object { $_.bounds }) -join '' }
# Down slowly, so the page does not fling on past what the next picture shows; up fast.
function Swipe($s, [switch]$Up) {
    $b = Bounds $s
    $x = [int](($b[0] + $b[2]) / 2)
    $low = [int]($b[1] + 0.85 * ($b[3] - $b[1]))
    $high = [int]($b[1] + 0.2 * ($b[3] - $b[1]))
    if ($Up) { A shell input swipe $x $high $x $low 250 } else { A shell input swipe $x $low $x $high 1500 }
    Start-Sleep -Milliseconds 900
}
# A page from where it is to its end: a picture, then further down, until nothing moves.
function Page([string]$what, [int]$max = 6) {
    $s = Scroller
    $before = Layout
    for ($i = 1; $i -le $max; $i++) {
        Shot $(if ($i -eq 1) { $what } else { "$what $i" })
        if (-not $s) { break }
        Swipe $s
        $after = Layout
        if ($after -eq $before) { break }
        $before = $after
    }
}
function Top {
    $s = Scroller
    if (-not $s) { return }
    $before = Layout
    for ($k = 0; $k -lt 12; $k++) {
        Swipe $s -Up
        $after = Layout
        if ($after -eq $before) { break }
        $before = $after
    }
}
# Down until $like is on screen, then back up if it was above.
function ScrollTo([string]$like) {
    $s = Scroller
    for ($k = 0; $k -lt 10; $k++) {
        if (FindNode $like) { return $true }
        if (-not $s) { return $false }
        Swipe $s
    }
    for ($k = 0; $k -lt 12; $k++) {
        if (FindNode $like) { return $true }
        Swipe $s -Up
    }
    $false
}
# A Settings tab's chip, its row swiped along until it shows.
function TapTab([string]$tab) {
    for ($k = 0; $k -lt 8; $k++) {
        $all = Nodes
        $n = $all | Where-Object { $_.text -ceq $tab } | Select-Object -First 1
        if ($n) { TapNode $n; return $true }
        $chip = $all | Where-Object { $tabs -contains $_.text } | Select-Object -First 1
        if (-not $chip) { return $false }
        $y = (Center $chip)[1]
        if ($k -lt 4) { A shell input swipe 1100 $y 200 $y 400 } else { A shell input swipe 200 $y 1100 $y 400 }
        Start-Sleep -Milliseconds 700
    }
    $false
}
# A session's page from the gallery: its tile is named for the time it was made.
function OpenSession([datetime]$at) {
    Launch
    Tap 'Sessions gallery' | Out-Null
    Start-Sleep -Seconds 2
    $tile = Nodes | Where-Object { $_.text -match ('Today . ' + $at.ToString('HH:mm')) } | Select-Object -First 1
    if (-not $tile) { Write-Host "    no session from $($at.ToString('HH:mm')) in the gallery"; return $false }
    TapNode $tile
    Start-Sleep -Seconds 3
    $true
}
function NewSession([string]$suffix, [string[]]$extra) {
    $at = Get-Date
    $name = $at.ToString('yyyyMMdd_HHmmss') + "_$suffix"
    powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'make-test-session.ps1') -Name $name -Rotation 90 -Push -Serial $Serial @extra | Out-Null
    @{ at = $at; name = $name }
}
function StackHere {
    if (-not ((ScrollTo 'Stack') -and (Tap 'Stack'))) { return $false }
    WaitFor 'Done: the photo above is updated' 600
}
# A Settings switch turned on or off, by its row, when its value ("On ...", "Off ...") says otherwise.
function SetSwitch([string]$tab, [string]$label, [bool]$on) {
    Launch
    if (-not ((Tool 'Settings') -and (TapTab $tab) -and (ScrollTo $label))) { return }
    $isOn = (AllText) -match ([regex]::Escape($label) + '\|+On\b')
    if ($isOn -ne $on) { Tap $label | Out-Null }
}
# The step a session is at, from the last "state -> ..." line its log has written. The screen is too
# busy for the UI dump to keep up with the short steps.
function SessionStep([string]$logFile) {
    $line = Get-Content $logFile -Tail 60 -ErrorAction SilentlyContinue | Where-Object { $_ -match 'state -> ' } | Select-Object -Last 1
    if ($line -match 'state -> (start delay|waiting for tripod)') { return 'hold still' }
    if ($line -match 'state -> probing') { return 'reading the sky' }
    if ($line -match 'state -> star focus') { return 'star focus' }
    if ($line -match 'state -> light') { return 'shooting' }
    if ($line -match 'state -> waiting for the lens') { return 'cover the lens' }
    if ($line -match 'state -> dark') { return 'dark frames' }
    if ($line -match 'state -> stacking') { return 'stacking' }
    if ($line -match 'state -> (DONE|ERROR|idle)') { return 'the end' }
    $null
}

# ---- the emulator
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
Launch
Tool 'Night vision on' -Switch | Out-Null
if ($Clean) {
    if ($Serial -notlike 'emulator-*') { throw '-Clean is for the emulator only: it deletes every session' }
    A shell rm -rf $sessions 2>&1 | Out-Null
    if (Wanted '05') {
        Section '05 gallery'
        Launch
        if (Tap 'Sessions gallery') { Start-Sleep -Seconds 2; Shot 'empty' }
    }
}

# ---- 01: the intro cards, replayed from Settings; the camera question, its permission taken back
if (Wanted '01') {
    Section '01 first launch'
    Launch
    if ((Tool 'Settings') -and (TapTab 'Interface') -and (ScrollTo 'Show the intro again') -and (Tap 'Show the intro again')) {
        for ($i = 1; $i -le 6; $i++) {
            WaitFor '\|Next\||\|Got it\|' 10 | Out-Null
            Shot "intro $i"
            if (-not (Tap 'Next')) { Tap 'Got it' | Out-Null; break }
        }
    }
    # Taking the permission back stops the app.
    A shell pm revoke com.wm.stelli android.permission.CAMERA 2>&1 | Out-Null
    A shell am start -n com.wm.stelli/com.wm.starcam.MainActivity 2>&1 | Out-Null
    if (WaitFor 'Allow camera access' 15) { Shot 'the camera question' }
    A shell pm grant com.wm.stelli android.permission.CAMERA 2>&1 | Out-Null
}

# ---- 02: the viewfinder in each mode, each mode's Adjust sheet, the sky view and Find
if (Wanted '02') {
    Section '02 main screen'
    Launch
    # The planner opened once first, so tonight's card has the place and tonight to show.
    if (Planner) { WaitFor 'Show me the sky' 40 | Out-Null; Launch; WaitFor 'Bortle' 15 | Out-Null }
    Tap 'Stars' | Out-Null
    if (Tap 'Session length*') { Tap 'Balanced*' | Out-Null }
    foreach ($m in @(@('Stars', 'stars'), @('Star trails', 'star trails'), @('Meteor', 'meteor watch'), @('Manual', 'manual'))) {
        Tap $m[0] | Out-Null
        Start-Sleep -Seconds 1
        Shot $m[1]
        if (Tap 'Adjust*') {
            Start-Sleep -Seconds 1
            Page "$($m[1]), adjust" 4
            # In Manual, the folded options opened. (A swipe down at the sheet's top closes it, so
            # it is opened again for the shutter.)
            if ($m[0] -eq 'Manual' -and (Tap 'More options')) { Start-Sleep -Seconds 1; Page 'manual, adjust, more options' 2 }
            Back
            if ($m[0] -eq 'Manual' -and (Tap 'Adjust*') -and (Tap 'SHUTTER')) {
                Start-Sleep -Seconds 1
                Shot 'manual, adjust, the shutter'
                Back
            }
        }
    }
    # Quick tools, and the session lengths (Star trails'), each as it opens.
    if (Tap 'Quick tools') { Shot 'quick tools'; Back }
    Tap 'Star trails' | Out-Null
    if (Tap 'Session length*') { Shot 'star trails, session length'; Back }
    Tap 'Stars' | Out-Null
    if (Tool 'Sky view' -Switch) {
        WaitFor 'The sky now' 15 | Out-Null
        Start-Sleep -Seconds 2
        Shot 'sky view'
        CloseSky
    }
    if (Tap 'Find in the sky*') {
        WaitFor 'Find in the sky\|' 20 | Out-Null
        Shot 'find'
        for ($k = 0; $k -lt 12 -and -not (FindNode 'Polaris, the North Star'); $k++) {
            A shell input swipe 636 1900 636 1000 300
            Start-Sleep -Milliseconds 600
        }
        if (Tap 'Polaris, the North Star') {
            WaitFor 'Find: Polaris' 10 | Out-Null
            Start-Sleep -Seconds 2
            Shot 'find, the north star found'
        }
        CloseSky
    }
}

# ---- 03: a capture's steps under a simulated sky (frames rendered from the star catalogue)
if (Wanted '03') {
    Section '03 a session'
    # Every step shown: no focus and no dark frames carried over from an earlier session.
    SetSwitch 'Focus' 'Reuse last focus' $false
    SetSwitch 'Capture' 'Reuse recent darks' $false
    $logFile = Join-Path $env:TEMP 'stelli-tour-logcat.txt'
    $logcat = $null
    try {
        Launch
        Tap 'Stars' | Out-Null
        # Under the simulated sky each step is kept once, named for the step the session's log says
        # it is at before and after the picture; the cover-the-lens question waits for its tap.
        A logcat -c
        $logcat = Start-Process $adbExe -ArgumentList '-s', $Serial, 'logcat', '-s', 'StarCam:I' `
            -RedirectStandardOutput $logFile -NoNewWindow -PassThru
        StartSession 'frames=40;exp=1500;darks=ask'
        $seen = @{}
        $deadline = (Get-Date).AddMinutes(12)
        while ((Get-Date) -lt $deadline) {
            $step = SessionStep $logFile
            if ($step -eq 'the end') { break }
            if ($step -and -not $seen[$step]) {
                # The screen a moment to show it; the focus and shooting steps a few frames in.
                Start-Sleep -Milliseconds $(switch ($step) { 'shooting' { 6000 } 'star focus' { 5000 } default { 1200 } })
                Grab
                if ((SessionStep $logFile) -eq $step) {
                    Keep $step
                    $seen[$step] = $true
                    if ($step -eq 'shooting') {
                        Back
                        if (WaitFor 'Stop this capture' 5) { Shot 'shooting, stop it'; Tap 'Keep going' | Out-Null }
                    }
                    if ($step -eq 'cover the lens') { Tap 'Lens covered' | Out-Null }
                }
            }
            Start-Sleep -Milliseconds 300
        }
        WaitFor 'Saved to Gallery|Photo not saved' 30 | Out-Null
        Start-Sleep -Seconds 2
        Page 'the photo' 4
        Back
        # The emulator's own camera: no stars, so no photo.
        Launch
        StartSession
        if (WaitFor 'Finish & stack now' 240) {
            Start-Sleep -Seconds 4
            Tap 'Finish & stack now' | Out-Null
            if (WaitFor "Couldn't stack|Capture stopped|RAWs saved|Saved to Gallery" 300) { Start-Sleep -Seconds 1; Page 'no photo' 3 }
            Back
        }
    } finally {
        if ($logcat) { Stop-Process -Id $logcat.Id -ErrorAction SilentlyContinue }
        SetSwitch 'Focus' 'Reuse last focus' $true
        SetSwitch 'Capture' 'Reuse recent darks' $true
    }
    # A session cut off: the app stopped mid-session, the notice on the next start.
    Launch
    StartSession
    if (WaitFor 'Finish & stack now' 240) {
        Start-Sleep -Seconds 4
        A shell am force-stop com.wm.stelli
        Launch
        if (WaitFor 'Last session was cut off' 10) { Shot 'after a session was cut off'; Tap 'Dismiss' | Out-Null }
    }
}

# ---- 04: a stacked photo of Orion and everything on its page; a meteor watch
if (Wanted '04') {
    Section '04 photo page'
    $orion = NewSession 'astro' @('-Sky', 'orion')
    if (OpenSession $orion.at) {
        Page 'before stacking' 3
        if (StackHere) {
            Start-Sleep -Seconds 2
            Top
            Page 'stacked' 8
            Top
            if (Tap '1 frame vs stacked') { Start-Sleep -Seconds 2; Shot '1 frame vs stacked'; Tap 'Photo' | Out-Null }
            if ((ScrollTo 'Find out') -and (Tap 'Find out')) {
                WaitFor 'Show all' 90 | Out-Null
                Shot "what's in this photo"
                if (Tap 'Show all') { Shot "what's in this photo, all of it" }
                Top
                Shot "what's in this photo, the labels"
            }
            # Two rows have a Make button: the replay video's comes first, then the timelapse's.
            if ((ScrollTo 'Make') -and (Tap 'Make')) {
                WaitFor 'Saved to the gallery \(Movies' 240 | Out-Null
                Shot 'replay video made'
                if (Tap 'Play') {
                    WaitFor 'Watch it build up' 10 | Out-Null
                    Start-Sleep -Seconds 3
                    Shot 'replay video playing'
                    Back
                }
            }
            if ((ScrollTo 'Make') -and (Tap 'Make')) {
                $deadline = (Get-Date).AddSeconds(300)
                while ((Get-Date) -lt $deadline -and -not ((A shell ls "$sessions/$($orion.name)/out/") -match '^timelapse\.mp4$')) { Start-Sleep -Seconds 3 }
                Start-Sleep -Seconds 4
                Shot 'timelapse made'
            }
            Top
            Page 'with its videos and labels' 8
            # The folded parts, opened.
            if ((ScrollTo 'Re-process') -and (Tap 'Re-process')) { Page 're-process' 2 }
            if ((ScrollTo 'Details') -and (Tap 'Details')) { Page 'details' 2 }
            Top
            if ((ScrollTo 'Edit') -and (Tap 'Edit')) {
                WaitFor 'HOLD TO COMPARE' 90 | Out-Null
                WaitFor 'Look: Mono' 30 | Out-Null
                Start-Sleep -Seconds 3
                Shot 'edit, looks'
                foreach ($t in 'Light', 'Colour', 'Detail', 'Frame') {
                    if (Tap $t) { Start-Sleep -Seconds 2; Shot "edit, $($t.ToLower())" }
                }
                Back
                WaitFor 'Delete session|\|Edit\|' 10 | Out-Null
            }
            if ((ScrollTo 'Delete session') -and (Tap 'Delete session')) { Shot 'delete, the question'; Back }
        }
    }
    $meteor = NewSession 'meteor' @('-Mode', 'METEOR')
    if ((OpenSession $meteor.at) -and (StackHere)) {
        if (ScrollTo '*meteor caught*') {
            WaitFor 'Meteor at \d\d:\d\d' 90 | Out-Null
            Shot 'meteor watch, its meteor'
            $thumb = FindNode 'Meteor at*'
            if ($thumb) { TapNode $thumb; Start-Sleep -Seconds 2; Shot 'meteor watch, the meteor opened'; Back }
        }
    }
}

# ---- 05: the gallery, all and one mode
if (Wanted '05') {
    Section '05 gallery'
    Launch
    if (Tap 'Sessions gallery') {
        Start-Sleep -Seconds 2
        Page 'gallery' 3
        Top
        $chip = Nodes | Where-Object { $_.'content-desc' -match '^Meteor: \d+$' } | Select-Object -First 1
        if ($chip) { TapNode $chip; Start-Sleep -Seconds 1; Shot 'gallery, meteor watches' }
    }
}

# ---- 06: the Tonight planner, a place by its coordinates, the sky calendar
if (Wanted '06') {
    Section '06 tonight'
    Launch
    if (Tap 'Tonight*') { Shot 'tonight, the card'; Back }
    if (Planner) {
        WaitFor 'Show me the sky' 40 | Out-Null
        Start-Sleep -Seconds 2
        Page 'tonight' 8
        if ((ScrollTo 'Details*') -and (Tap 'Details*')) { Start-Sleep -Seconds 1; Page 'tonight, the details' 6 }
        if ((ScrollTo 'Enter by hand') -and (Tap 'Enter by hand')) {
            Start-Sleep -Seconds 1
            Shot 'a place by its coordinates'
            if (-not (Tap 'Cancel')) { Back }
        }
        if ((ScrollTo 'Sky calendar') -and (Tap 'Sky calendar')) {
            WaitFor 'New moon' 40 | Out-Null
            Page 'sky calendar' 3
            Top
            if (Tap 'Meteors') {
                Start-Sleep -Seconds 1
                Shot 'sky calendar, meteors'
                if ((ScrollTo 'Geminids') -and (Tap 'Geminids')) {
                    WaitFor 'From your sky|Radiant in Gemini' 10 | Out-Null
                    Shot 'sky calendar, the geminids'
                    if ((ScrollTo 'Plan night') -and (Tap 'Plan night')) {
                        WaitFor 'Show me the sky' 30 | Out-Null
                        Start-Sleep -Seconds 2
                        Shot 'the night of the geminids'
                    }
                }
            }
        }
    }
    Launch
    if ((Planner) -and (WaitFor 'Show me the sky' 40) -and (Tap 'Show me the sky*')) {
        WaitFor 'The sky (now|at)' 20 | Out-Null
        Start-Sleep -Seconds 2
        Shot 'the sky view from the planner'
        CloseSky
    }
}

# ---- 07: the light map: its panel at each height, the layers menu
if (Wanted '07') {
    Section '07 light map'
    Launch
    if (Tap 'Light map*') {
        WaitFor 'Find darker skies' 30 | Out-Null
        Start-Sleep -Seconds 3
        Shot 'light map'
        $h = FindNode 'Show the whole list'
        if ($h) {
            # Dragged down by its handle: the place's name alone. Then the handle's tap: everything.
            $c = Center $h
            A shell input swipe $c[0] $c[1] $c[0] ($c[1] + 900) 500
            Start-Sleep -Milliseconds 1500
            Shot 'light map, the panel down'
            $h = FindNode 'Show the whole list'
            if ($h) {
                TapNode $h
                Start-Sleep -Milliseconds 1500
                Page 'light map, the panel up' 3
            }
            Back
        }
        if (Tap 'Map layers') {
            Shot 'light map, layers'
            Back
        }
    }
}

# ---- 08: Settings: every tab, an explanation, the search, Advanced's dialogs and tools
if (Wanted '08') {
    Section '08 settings'
    Launch
    if (Tool 'Settings') {
        WaitFor 'Search settings' 10 | Out-Null
        foreach ($t in $tabs) {
            if (TapTab $t) {
                Top
                Page $t.ToLower() 5
                # Then with its More options (Advanced: Troubleshooting) open.
                $more = if ($t -eq 'Advanced') { 'Troubleshooting' } else { 'More options' }
                if ((ScrollTo $more) -and (Tap $more)) { Page "$($t.ToLower()), more" 5 }
            }
        }
        if (TapTab 'Exposure') {
            Top
            if (Tap 'What does Let the app choose do?') {
                Shot 'a setting explained'
                Tap 'What does Let the app choose do?' | Out-Null
            }
        }
        $field = Nodes | Where-Object { $_.class -eq 'android.widget.EditText' } | Select-Object -First 1
        if ($field) {
            TapNode $field
            A shell input text dark
            Start-Sleep -Seconds 1
            Shot 'search'
            Tap 'Clear search' | Out-Null
            Back
        }
        if (TapTab 'Advanced') {
            foreach ($d in @(@('Licences and data', 'licences and data'), @('Privacy policy', 'privacy policy'), @('Terms of use', 'terms of use'))) {
                if ((ScrollTo $d[0]) -and (Tap $d[0])) { Start-Sleep -Seconds 1; Page $d[1] 4; Back }
            }
            if ((ScrollTo 'Reset all settings') -and (Tap 'Reset all settings')) { Shot 'reset all settings'; Back }
            if ((ScrollTo 'Diagnostics') -and (Tap 'Diagnostics')) { Start-Sleep -Seconds 2; Page 'diagnostics' 2; Back }
            # The probe and the self-test are under Troubleshooting, closed again after each visit.
            if ((ScrollTo 'Troubleshooting') -and (Tap 'Troubleshooting') -and (ScrollTo 'Capability probe') -and
                (Tap 'Capability probe')) { Start-Sleep -Seconds 2; Page 'capability probe' 2; Back }
            if ((ScrollTo 'Troubleshooting') -and (Tap 'Troubleshooting') -and (ScrollTo 'Engine self-test') -and
                (Tap 'Engine self-test')) {
                Start-Sleep -Seconds 1
                Shot 'engine self-test'
                if (Tap 'Run self-test') {
                    WaitFor 'Self-test finished' 600 | Out-Null
                    Page 'engine self-test, done' 3
                }
                Back
            }
        }
    }
}

# ---- 09: night vision: the main screens in red
if (Wanted '09') {
    Section '09 night vision'
    Launch
    if (Tool 'Night vision off') {
        Start-Sleep -Seconds 2
        Shot 'quick tools'
        Back
        Shot 'main screen'
        if (Tap 'Adjust*') { Start-Sleep -Seconds 1; Shot 'adjust'; Back }
        if (Tool 'Settings') {
            Start-Sleep -Seconds 1
            if (TapTab 'Interface') { Top }
            Shot 'settings'
            Back
        }
        if (Planner) { WaitFor 'Show me the sky' 40 | Out-Null; Start-Sleep -Seconds 2; Shot 'tonight'; Back }
        if (Tap 'Sessions gallery') {
            Start-Sleep -Seconds 2
            Shot 'gallery'
            $tile = Nodes | Where-Object { $_.text -match 'Today . \d\d:\d\d' } | Select-Object -First 1
            if ($tile) { TapNode $tile; Start-Sleep -Seconds 3; Shot 'photo page' }
        }
        Launch
        Tool 'Night vision on' -Switch | Out-Null
    }
}

A shell am force-stop com.wm.stelli
Demo @('-e', 'command', 'exit')

# ---- the pictures on one page, a browser's way to look through them all
$html = New-Object System.Collections.Generic.List[string]
$html.Add('<!doctype html><meta charset="utf-8"><title>Stelli, every screen</title>')
$html.Add('<style>body{margin:24px;background:#14121c;color:#e8e4f4;font:15px/1.4 system-ui,sans-serif}' +
    'h2{margin:36px 0 10px;font-weight:600}figure{display:inline-block;width:240px;margin:0 14px 18px 0;vertical-align:top}' +
    'img{width:100%;border-radius:10px;border:1px solid #3a3550}figcaption{margin-top:5px;font-size:13px;color:#a8a2bc}</style>')
$html.Add("<h1>Stelli, every screen</h1><p>From the emulator, $(Get-Date -Format 'd MMMM yyyy'). Click a picture to see it full size.</p>")
foreach ($d in Get-ChildItem $Out -Directory | Sort-Object Name) {
    $files = @(Get-ChildItem $d.FullName -Filter *.png | Sort-Object Name)
    if ($files.Count -eq 0) { continue }
    $html.Add("<h2>$([System.Net.WebUtility]::HtmlEncode($d.Name))</h2>")
    foreach ($f in $files) {
        $src = [uri]::EscapeDataString($d.Name) + '/' + [uri]::EscapeDataString($f.Name)
        $html.Add("<figure><a href=""$src""><img src=""$src"" loading=""lazy""></a><figcaption>$([System.Net.WebUtility]::HtmlEncode($f.BaseName))</figcaption></figure>")
    }
}
Set-Content -Path (Join-Path $Out 'all screens.html') -Value $html -Encoding UTF8
Write-Host "screens in $Out"
