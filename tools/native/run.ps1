# The native engine on the PC: builds it for Linux inside a small Docker image and runs it there,
# with the repo mounted. No emulator, no adb: a self-test takes about 15 s, a re-stack of a real
# session about 40 s. Needs Docker Desktop running (Linux containers).
#
#   powershell -ExecutionPolicy Bypass -File tools\native\run.ps1 -SelfTest
#   powershell -ExecutionPolicy Bypass -File tools\native\run.ps1 -Session <sessionDir>
#   powershell -ExecutionPolicy Bypass -File tools\native\run.ps1 -Session <sessionDir> -Mode align
#   powershell -ExecutionPolicy Bypass -File tools\native\run.ps1 -Session <sessionDir> -Options my.json -Keep
#
# -SelfTest  the engine's own end-to-end check (selftest.cpp); prints each check, exit code 1 on a failure.
# -Session   a session folder as the phone keeps it (cache\frame_*.sraw, meta\session.json): what
#            tools\stacklab\run.ps1 does on the emulator, here. Results go to
#            field-logs\stacklab\<session>_<time>\ (or -Out): align.txt, stack.txt, sheet.png,
#            final.png (the finished photo, upright) and stack_report.json.
# -Options   a JSON file of stacking options instead of the session's own (meta\session.json).
# -Set       options to change on top of those, as key=value pairs: -Set foregroundComposite=true,detail=0.3
# -Keep      also keep the big outputs (master_linear16.tiff, final_rgb.raw, single_rgb.raw).
# -NoBuild   skip the build (nothing changed since the last run).

param([switch]$SelfTest, [string]$Session, [ValidateSet('align', 'stack', 'both')][string]$Mode = 'both',
      [string]$Options, [string[]]$Set, [string]$Out, [switch]$Keep, [switch]$NoBuild)
$ErrorActionPreference = 'Continue'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$image = 'stelli-native:1'
$build = Join-Path $project 'build\native'
New-Item -ItemType Directory -Force $build | Out-Null

& docker image inspect $image *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'building the Docker image (once)...'
    & docker build -q -t $image $PSScriptRoot | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'docker build failed (is Docker Desktop running?)' }
}

function InBox([string[]]$mounts, [string]$command) {
    $argv = @('run', '--rm', '-v', "${project}:/src:ro", '-v', "${build}:/build")
    foreach ($m in $mounts) { $argv += @('-v', $m) }
    $argv += @($image, 'sh', '-c', $command)
    & docker @argv
}

if (-not $NoBuild) {
    $log = InBox @() 'cmake -S /src/tools/native -B /build -G Ninja -DCMAKE_BUILD_TYPE=Release > /build/cmake.log 2>&1; cmake --build /build 2>&1'
    $bad = $log | Select-String -Pattern 'error|FAILED|build stopped'
    if ($bad) { $log | Select-Object -Last 40 | ForEach-Object { Write-Host $_ }; throw 'native build failed' }
    $log | Select-String -Pattern 'warning' | Select-Object -First 20 | ForEach-Object { Write-Host $_.Line }
}

if ($SelfTest) {
    $report = (InBox @() 'mkdir -p /tmp/st && /build/selftest /tmp/st 2>/build/selftest.err') -join ''
    $ok = $report.StartsWith('{"pass":true')
    try {
        $r = $report | ConvertFrom-Json
        foreach ($c in $r.checks) {
            Write-Host ('{0,-14} {1}  value {2}  limit {3}' -f $c.name, $(if ($c.pass) { 'PASS' } else { 'FAIL' }), $c.value, $c.limit)
        }
    } catch { Write-Host $report }
    Write-Host $(if ($ok) { 'self-test passed' } else { 'self-test FAILED (build\native\selftest.err has the engine log)' })
    if (-not $ok) { exit 1 }
}

if ($Session) {
    $Session = (Resolve-Path $Session).Path
    $name = Split-Path $Session -Leaf
    $dest = if ($Out) { $Out } else { Join-Path $project ("field-logs\stacklab\${name}_" + (Get-Date -Format 'yyyyMMdd_HHmmss')) }
    New-Item -ItemType Directory -Force $dest | Out-Null
    $dest = (Resolve-Path $dest).Path

    # The stacking options the session was made with (its turn included), or the given ones.
    $cfg = $null
    $rotation = 0
    $meta = Join-Path $Session 'meta\session.json'
    if ($Options) {
        $cfg = Get-Content $Options -Raw | ConvertFrom-Json
    } elseif (Test-Path $meta) {
        $cfg = (Get-Content $meta -Raw | ConvertFrom-Json).header.stackConfig
    }
    if ($null -eq $cfg) { $cfg = New-Object psobject }
    foreach ($pair in $Set) {
        if ($pair -notmatch '^([^=]+)=(.*)$') { throw "-Set wants key=value, got '$pair'" }
        $key = $matches[1]; $raw = $matches[2]
        $value = if ($raw -eq 'true') { $true } elseif ($raw -eq 'false') { $false }
                 elseif ($raw -match '^-?\d+$') { [int]$raw }
                 elseif ($raw -match '^-?\d*\.\d+$') { [double]::Parse($raw, [Globalization.CultureInfo]::InvariantCulture) }
                 else { $raw }
        $cfg | Add-Member -NotePropertyName $key -NotePropertyValue $value -Force
    }
    if ($null -ne $cfg.rotation) { $rotation = [int]$cfg.rotation }
    [IO.File]::WriteAllText((Join-Path $dest 'options.json'), ($cfg | ConvertTo-Json -Compress))

    $phone = if (Test-Path (Join-Path $Session 'out\master_linear16.tiff')) { '/session/out/master_linear16.tiff' } else { '' }
    $mounts = @("${Session}:/session:ro", "${dest}:/out")
    if ($Mode -ne 'stack') {
        InBox $mounts '/build/stacklab align /session/cache /out 2>&1' | Tee-Object (Join-Path $dest 'align.txt')
    }
    if ($Mode -ne 'align') {
        InBox $mounts "/build/stacklab stack /session/cache /out /out/options.json $rotation $phone 2>&1" |
            Where-Object { $_ -notmatch '^\s+add \d+:' } | Tee-Object (Join-Path $dest 'stack.txt')
        $stackOut = Join-Path $dest 'stack\out'
        $raw = Join-Path $stackOut 'final_rgb.raw'
        $result = Get-Content (Join-Path $dest 'stack.txt') | Where-Object { $_ -like 'result:*' } | Select-Object -First 1
        if ((Test-Path $raw) -and $result -match '"width":(\d+),"height":(\d+)') {
            $w = [int]$matches[1]; $h = [int]$matches[2]
            $bytes = [IO.File]::ReadAllBytes($raw)
            Add-Type -AssemblyName System.Drawing
            $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
            $data = $bmp.LockBits((New-Object System.Drawing.Rectangle 0, 0, $w, $h),
                [System.Drawing.Imaging.ImageLockMode]::WriteOnly, $bmp.PixelFormat)
            $row = New-Object byte[] ($w * 3)
            for ($y = 0; $y -lt $h; $y++) {
                [Array]::Copy($bytes, $y * $w * 3, $row, 0, $w * 3)
                for ($x = 0; $x -lt $w * 3; $x += 3) { $t = $row[$x]; $row[$x] = $row[$x + 2]; $row[$x + 2] = $t }
                [Runtime.InteropServices.Marshal]::Copy($row, 0, [IntPtr]($data.Scan0.ToInt64() + $y * $data.Stride), $w * 3)
            }
            $bmp.UnlockBits($data)
            $bmp.Save((Join-Path $dest 'final.png'), [System.Drawing.Imaging.ImageFormat]::Png)
            $bmp.Dispose()
        }
        $report = Join-Path $stackOut 'stack_report.json'
        if (Test-Path $report) { Copy-Item $report (Join-Path $dest 'stack_report.json') -Force }
        if (-not $Keep) {
            Get-ChildItem $dest -Recurse -Include *.raw, *.tiff, *.sraw | Remove-Item -Force
        }
    }
    Add-Type -AssemblyName System.Drawing
    Get-ChildItem $dest -Filter *.bmp | ForEach-Object {
        $bmp = [System.Drawing.Bitmap]::FromFile($_.FullName)
        $bmp.Save([IO.Path]::ChangeExtension($_.FullName, '.png'), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        Remove-Item $_.FullName
    }
    Write-Host "results: $dest"
}
