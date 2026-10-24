# Writes a synthetic StarCam session - drifting starfield frames as .sraw cache files, a master dark
# with hot pixels, and session.json - so stacking, saving and re-stacking can be exercised without a
# night sky, on the emulator (whose camera shows no stars) or on a phone.
#
# The frames are 1856 x 1392 BGGR, 20 s at ISO 800: 450 stars drifting and rotating from frame to
# frame, a satellite trail across frame 5 (the stack must reject it), the same 300 hot pixels in
# every frame and in the dark, and frame 3 recorded as gyro-bumped (a re-stack must leave it out).
# Stacked from the session screen it should report 7 of 8 frames. The live stack's pictures for
# the replay video (replay/) are drawn too, one per stacked frame.
#
#   powershell -ExecutionPolicy Bypass -File tools\make-test-session.ps1 -Push
#   powershell -ExecutionPolicy Bypass -File tools\make-test-session.ps1 -Push -Serial emulator-5554
#
# Without -Push the session is only written to field-logs\test-sessions\.
#
# -Sky orion (or -Sky "ra,dec" in degrees) draws the real sky instead of a random field: the star
# catalogue's stars around that place at a 1500 px focal length (63 x 50 deg), with fainter stars
# it does not have, and records the focal length - for "What's in this photo".
#
# -Mode METEOR records the session as a meteor watch and puts one meteor in frame 6 (a short bright
# streak, in that frame only) in place of the satellite - for the meteor highlights.

param(
    [string]$Name = (Get-Date -Format 'yyyyMMdd') + '_030000_astro',
    [int]$Frames = 8,
    # Recorded as the clockwise turn that makes the photo upright (-1: not recorded, as before 0.4).
    [int]$Rotation = -1,
    [switch]$Push,
    [string]$Serial = '',
    [string]$Sky = '',
    [ValidateSet('ASTRO', 'METEOR')]
    [string]$Mode = 'ASTRO'
)
$ErrorActionPreference = 'Stop'

$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $project "field-logs\test-sessions\$Name"

Add-Type -TypeDefinition @"
using System;
using System.IO;

public static class StarCamTestSession {
    const int W = 1856, H = 1392;
    const uint WHITE = 1023;
    const float BLACK = 64f;

    // The real sky's stars (-Sky): RAW px and brightness; null for a random field.
    public static double[] SkyX, SkyY, SkyA;
    // A meteor watch (-Mode METEOR): a meteor in frame 6 instead of the satellite in frame 5.
    public static bool Meteor;

    // Header layout: app/src/main/cpp/sraw.h.
    static void Header(BinaryWriter w, long expNs, int iso, long ts, float[] wb) {
        w.Write(new byte[] { (byte)'S', (byte)'R', (byte)'A', (byte)'W' });
        w.Write((uint)1); w.Write((uint)W); w.Write((uint)H);
        w.Write((uint)3);              // BGGR, like the OnePlus 15
        w.Write(WHITE);
        for (int i = 0; i < 4; i++) w.Write(BLACK);
        for (int i = 0; i < 4; i++) w.Write(wb[i]);
        float[] ccm = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
        for (int i = 0; i < 9; i++) w.Write(ccm[i]);
        w.Write((ulong)expNs); w.Write((uint)iso); w.Write((ulong)ts);
        w.Write(new byte[160 - w.BaseStream.Position]);
    }

    static double Gauss(Random r) {
        double u1 = 1.0 - r.NextDouble(), u2 = r.NextDouble();
        return Math.Sqrt(-2.0 * Math.Log(u1)) * Math.Cos(2.0 * Math.PI * u2);
    }

    public static void Make(string dir, int frames) {
        Directory.CreateDirectory(Path.Combine(dir, "cache"));
        var rs = new Random(42);
        int nStars = 450;
        double[] sx = new double[nStars], sy = new double[nStars], sa = new double[nStars];
        for (int i = 0; i < nStars; i++) {
            sx[i] = 40 + rs.NextDouble() * (W - 80);
            sy[i] = 40 + rs.NextDouble() * (H - 80);
            sa[i] = 25 + 900 * Math.Pow(rs.NextDouble(), 3.0);   // a few bright, many faint
        }
        if (SkyX != null) {
            // The catalogue's stars, and fainter ones it does not have scattered among them.
            int faint = 300, n = SkyX.Length + faint;
            double[] x2 = new double[n], y2 = new double[n], a2 = new double[n];
            for (int i = 0; i < SkyX.Length; i++) { x2[i] = SkyX[i]; y2[i] = SkyY[i]; a2[i] = SkyA[i]; }
            for (int i = SkyX.Length; i < n; i++) {
                x2[i] = sx[i % nStars]; y2[i] = sy[i % nStars]; a2[i] = 8 + 14 * rs.NextDouble();
            }
            nStars = n; sx = x2; sy = y2; sa = a2;
        }
        int nHot = 300;
        int[] hx = new int[nHot], hy = new int[nHot];
        for (int i = 0; i < nHot; i++) { hx[i] = rs.Next(W); hy[i] = rs.Next(H); }
        // White balance in raster order (B, G, G, R); the raw sky is tinted so that it comes out
        // roughly neutral after these gains.
        float[] wb = { 1.59f, 1.0f, 1.0f, 2.45f };
        double[] skyTint = { 1.0 / 1.59, 1.0, 1.0, 1.0 / 2.45 };
        double sigma = 1.35;                          // PSF sigma, RAW px

        for (int f = 0; f < frames; f++) {
            var r = new Random(1000 + f);
            var img = new double[W * H];
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    int pos = ((y & 1) << 1) | (x & 1);
                    img[y * W + x] = BLACK + (45 + 12.0 * y / H) * skyTint[pos] + 3.0 * Gauss(r);
                }
            // Drift: rotation about the centre plus a translation, growing per frame.
            double th = f * 0.02 * Math.PI / 180.0, c = Math.Cos(th), s = Math.Sin(th);
            double tx = f * 1.6, ty = -f * 0.9;
            for (int i = 0; i < nStars; i++) {
                double dx = sx[i] - W / 2.0, dy = sy[i] - H / 2.0;
                double px = c * dx - s * dy + W / 2.0 + tx, py = s * dx + c * dy + H / 2.0 + ty;
                int x0 = (int)(px - 6), x1 = (int)(px + 6), y0 = (int)(py - 6), y1 = (int)(py + 6);
                for (int y = Math.Max(0, y0); y <= Math.Min(H - 1, y1); y++)
                    for (int x = Math.Max(0, x0); x <= Math.Min(W - 1, x1); x++) {
                        double d2 = (x - px) * (x - px) + (y - py) * (y - py);
                        // Tinted like the sky, so the stars come out white after white balance
                        // (untinted they were raw-white, i.e. magenta once balanced).
                        img[y * W + x] += sa[i] * Math.Exp(-d2 / (2 * sigma * sigma)) *
                            skyTint[((y & 1) << 1) | (x & 1)];
                    }
            }
            if (f == 5 && !Meteor)
                for (int x = 0; x < W; x++) {
                    double yy = 200 + 0.45 * x;
                    for (int y = (int)yy - 2; y <= (int)yy + 2; y++)
                        if (y >= 0 && y < H) img[y * W + x] += 400;
                }
            if (f == 6 && Meteor)
                // 320 px from (600, 500), fading in and out along its way.
                for (int k = 0; k <= 320; k++) {
                    double t = k / 320.0, mx = 600 + 300 * t, my = 500 + 110 * t;
                    double amp = 420 * Math.Sin(Math.PI * t);
                    for (int y = (int)my - 3; y <= (int)my + 3; y++)
                        for (int x = (int)mx - 1; x <= (int)mx + 1; x++) {
                            double d2 = (y - my) * (y - my);
                            // Tinted like the stars, so it comes out white, not magenta.
                            img[y * W + x] += amp * Math.Exp(-d2 / 2.0) / 3.0 * skyTint[((y & 1) << 1) | (x & 1)];
                        }
                }
            for (int i = 0; i < nHot; i++) img[hy[i] * W + hx[i]] += 220;
            var path = Path.Combine(dir, "cache", string.Format("frame_{0:D4}.sraw", f));
            using (var w = new BinaryWriter(File.Create(path))) {
                Header(w, 20000000000L, 800, 1000000000L * (f + 1) * 21, wb);
                for (int k = 0; k < W * H; k++) {
                    double v = Math.Min(WHITE, Math.Max(0, img[k]));
                    w.Write((ushort)Math.Round(v));
                }
            }
        }
        var rd = new Random(7);
        using (var w = new BinaryWriter(File.Create(Path.Combine(dir, "cache", "master_dark.sraw")))) {
            Header(w, 0, 0, 0, new float[] { 1, 1, 1, 1 });
            var dark = new double[W * H];
            for (int k = 0; k < W * H; k++) dark[k] = BLACK + 2 + 1.0 * Gauss(rd);
            for (int i = 0; i < nHot; i++) dark[hy[i] * W + hx[i]] += 220;
            for (int k = 0; k < W * H; k++) w.Write((ushort)Math.Round(Math.Max(0, dark[k])));
        }
    }

    // The live stack as capture keeps it for the replay video (replay/0001.jpg, ...): one picture
    // per stacked frame, the noise falling as frames add up. Drawn, not stacked: only the files
    // and their sizes matter to the app.
    public static void Replay(string dir, int frames, bool portrait) {
        string rdir = Path.Combine(dir, "replay");
        Directory.CreateDirectory(rdir);
        int w = portrait ? 720 : 960, h = portrait ? 960 : 720;
        var rs = new Random(42);
        int nStars = 450;
        double[] sx = new double[nStars], sy = new double[nStars], sa = new double[nStars];
        for (int i = 0; i < nStars; i++) {
            sx[i] = rs.NextDouble() * w;
            sy[i] = rs.NextDouble() * h;
            sa[i] = 20 + 230 * Math.Pow(rs.NextDouble(), 3.0);
        }
        int k = 0;
        for (int f = 0; f < frames; f++) {
            if (f == 3) continue;  // recorded as bumped: never stacked
            k++;
            var r = new Random(500 + f);
            double noise = 26.0 / Math.Sqrt(k);
            var v = new double[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) v[y * w + x] = 22 + 10.0 * y / h + noise * Gauss(r);
            for (int i = 0; i < nStars; i++) {
                int x0 = (int)sx[i] - 3, y0 = (int)sy[i] - 3;
                for (int y = Math.Max(0, y0); y <= Math.Min(h - 1, y0 + 6); y++)
                    for (int x = Math.Max(0, x0); x <= Math.Min(w - 1, x0 + 6); x++) {
                        double d2 = (x - sx[i]) * (x - sx[i]) + (y - sy[i]) * (y - sy[i]);
                        v[y * w + x] += sa[i] * Math.Exp(-d2 / 1.6);
                    }
            }
            using (var bmp = new System.Drawing.Bitmap(w, h, System.Drawing.Imaging.PixelFormat.Format24bppRgb)) {
                var data = bmp.LockBits(new System.Drawing.Rectangle(0, 0, w, h),
                    System.Drawing.Imaging.ImageLockMode.WriteOnly, bmp.PixelFormat);
                var bytes = new byte[data.Stride * h];
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        byte b = (byte)Math.Min(255, Math.Max(0, v[y * w + x]));
                        int o = y * data.Stride + x * 3;
                        bytes[o] = b; bytes[o + 1] = b; bytes[o + 2] = b;
                    }
                System.Runtime.InteropServices.Marshal.Copy(bytes, 0, data.Scan0, bytes.Length);
                bmp.UnlockBits(data);
                bmp.Save(Path.Combine(rdir, string.Format("{0:D4}.jpg", k)), System.Drawing.Imaging.ImageFormat.Jpeg);
            }
        }
    }
}
"@ -ReferencedAssemblies System.Drawing

if ($Sky) {
    $spec = @{ orion = '84,2'; cygnus = '305,38'; scorpius = '255,-30' }[$Sky.ToLower()]
    if (-not $spec) { $spec = $Sky }
    $ra0, $dec0 = $spec -split ',' | ForEach-Object { [double]$_ * [Math]::PI / 180 }
    $f = 1500.0
    $xs = New-Object 'System.Collections.Generic.List[double]'
    $ys = New-Object 'System.Collections.Generic.List[double]'
    $amps = New-Object 'System.Collections.Generic.List[double]'
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $project 'app\src\main\assets\sky\stars.txt'))) {
        if ($line.StartsWith('#') -or -not $line.Trim()) { continue }
        $p = $line.Trim() -split ' '
        $ra = [double]$p[1] * [Math]::PI / 180
        $dec = [double]$p[2] * [Math]::PI / 180
        $cosc = [Math]::Sin($dec0) * [Math]::Sin($dec) + [Math]::Cos($dec0) * [Math]::Cos($dec) * [Math]::Cos($ra - $ra0)
        if ($cosc -lt 0.3) { continue }
        # Gnomonic, as a camera sees the sky from inside it: east to the left, north up.
        $xi = [Math]::Cos($dec) * [Math]::Sin($ra - $ra0) / $cosc
        $eta = ([Math]::Cos($dec0) * [Math]::Sin($dec) - [Math]::Sin($dec0) * [Math]::Cos($dec) * [Math]::Cos($ra - $ra0)) / $cosc
        $x = 928 - $f * $xi
        $y = 696 - $f * $eta
        if ($x -lt 10 -or $y -lt 10 -or $x -gt 1846 -or $y -gt 1382) { continue }
        $xs.Add($x); $ys.Add($y)
        $amps.Add([Math]::Min(950, [Math]::Max(10, 900 * [Math]::Pow(10, -0.4 * ([double]$p[3] - 0.5)))))
    }
    [StarCamTestSession]::SkyX = $xs.ToArray()
    [StarCamTestSession]::SkyY = $ys.ToArray()
    [StarCamTestSession]::SkyA = $amps.ToArray()
    Write-Host "sky: $($xs.Count) catalogue stars around $spec"
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
foreach ($d in 'lights', 'darks', 'out', 'meta') { New-Item -ItemType Directory -Force (Join-Path $out $d) | Out-Null }
[StarCamTestSession]::Meteor = ($Mode -eq 'METEOR')
[StarCamTestSession]::Make($out, $Frames)
[StarCamTestSession]::Replay($out, $Frames, ($Rotation -eq 90 -or $Rotation -eq 270))

$startedAt = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
$framesJson = (0..($Frames - 1) | ForEach-Object {
    "{`"index`":$_,`"exposureNs`":20000000000,`"iso`":800,`"starCount`":300,`"fwhm`":2.4,`"aligned`":true," +
        "`"gyroPeak`":0.001,`"bumped`":$(if ($_ -eq 3) { 'true' } else { 'false' })}"
}) -join ','
$json = '{"header":{"mode":"' + $Mode + '","quality":"FAST","stackConfig":{"sigmaFirst":3.5,"sigmaFinal":2.8,' +
    '"hotPixelFix":true,"lensShading":true,"gradientRemoval":true,"stretchStrength":1,"saturation":1.25,' +
    '"chromaDenoise":true,"starSharpen":false,"foregroundComposite":false,"featherPx":40,"style":0' +
    $(if ($Rotation -ge 0) { ",`"rotation`":$Rotation" } else { '' }) + '},' +
    "`"startedAt`":$startedAt,`"rawWidth`":1856,`"rawHeight`":1392,`"exposureNs`":20000000000,`"iso`":800," +
    $(if ($Sky) { '"focalPx":1500,' } else { '' }) +
    "`"plannedFrames`":$Frames,`"reasoning`":`"synthetic test session (tools\\make-test-session.ps1)`"}," +
    "`"frames`":[$framesJson]}"
[IO.File]::WriteAllText((Join-Path $out 'meta\session.json'), $json)
Write-Host "written: $out"

if ($Push) {
    # Continue, not Stop: adb reports success on stderr, which PowerShell 5.1 turns into an error.
    $ErrorActionPreference = 'Continue'
    $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    $target = '/sdcard/Android/media/com.wm.starcam/StarCam/sessions/'
    $sel = if ($Serial) { @('-s', $Serial) } else { @() }
    & $adb @sel push $out $target 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "adb push failed" }
    # Note: Android 16 does not let the app open pictures adb put into its folder, so there the
    # replay pictures are listed but cannot be read (emulator-test.ps1 uses a live session's).
    Write-Host "pushed to $target$Name - open Sessions in the app and tap Stack"
}
