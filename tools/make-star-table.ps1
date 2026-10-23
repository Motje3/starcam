# Builds app\src\main\assets\sky\stars.txt - the sky view's stars - from the Yale Bright Star
# Catalogue, 5th revised edition (Hoffleit & Warren 1991; public domain, NASA ADC / CDS V/50).
#
#   curl -o bsc5.dat.gz http://tdc-www.harvard.edu/catalogs/bsc5.dat.gz   (then gunzip)
#   powershell -ExecutionPolicy Bypass -File tools\make-star-table.ps1 -Catalog <path>\bsc5.dat
#
# One line per star down to -MaxMag: J2000 right ascension and declination (degrees), V
# magnitude, B-V colour (blank when not catalogued) and the Bayer designation ("Alp Ori", "Alp1 Cen")
# when it has one. Byte positions: the catalogue's ReadMe.

# The constellation figures need stars to V 5 (Eta UMi is 4.95).
param([Parameter(Mandatory = $true)][string]$Catalog, [double]$MaxMag = 5.0)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outDir = Join-Path $project 'app\src\main\assets\sky'
New-Item -ItemType Directory -Force $outDir | Out-Null
$inv = [Globalization.CultureInfo]::InvariantCulture

$out = New-Object System.Collections.Generic.List[string]
$out.Add('# StarCam sky view: stars to V ' + $MaxMag.ToString('0.0', $inv) + ' from the Yale Bright Star Catalogue, 5th rev. ed.')
$out.Add('# (Hoffleit & Warren 1991, public domain). Columns: HR, RA J2000 (deg), Dec J2000 (deg), V, B-V, Bayer.')
foreach ($line in [IO.File]::ReadLines($Catalog)) {
    if ($line.Length -lt 107) { continue }
    $raH = $line.Substring(75, 2).Trim()
    if ($raH -eq '') { continue }  # no J2000 position (novae and the like)
    $vmag = $line.Substring(102, 5).Trim()
    if ($vmag -eq '') { continue }
    $v = [double]::Parse($vmag, $inv)
    if ($v -gt $MaxMag) { continue }
    $hr = [int]$line.Substring(0, 4).Trim()
    $ra = ([int]$raH + [int]$line.Substring(77, 2) / 60.0 + [double]::Parse($line.Substring(79, 4), $inv) / 3600.0) * 15.0
    $sign = if ($line.Substring(83, 1) -eq '-') { -1.0 } else { 1.0 }
    $dec = $sign * ([int]$line.Substring(84, 2) + [int]$line.Substring(86, 2) / 60.0 + [int]$line.Substring(88, 2) / 3600.0)
    $bv = if ($line.Length -ge 114) { $line.Substring(109, 5).Trim() } else { '' }
    # Name field (bytes 5-14): Flamsteed number (5-7), Bayer letter (8-10), its index (11),
    # constellation (12-14).
    $bayer = $line.Substring(7, 3).Trim()
    $idx = $line.Substring(10, 1).Trim()
    $con = $line.Substring(11, 3).Trim()
    $desig = if ($bayer -ne '' -and $con -ne '') { "$bayer$idx $con" } else { '' }
    $out.Add(('{0} {1} {2} {3} {4} {5}' -f $hr, $ra.ToString('0.0000', $inv), $dec.ToString('0.0000', $inv),
        $v.ToString('0.00', $inv), $(if ($bv -ne '') { $bv } else { '-' }), $desig).TrimEnd())
}
$target = Join-Path $outDir 'stars.txt'
[IO.File]::WriteAllText($target, ($out -join "`n") + "`n")
Write-Host "$($out.Count - 2) stars -> $target"
