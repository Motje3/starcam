# Writes a synthetic StarCam session - drifting starfield frames as .sraw cache files, a master dark
# with hot pixels, and session.json - so stacking, saving and re-stacking can be exercised without a
# night sky, on the emulator (whose camera shows no stars) or on a phone.
#
# The frames are 1856 x 1392 BGGR, 20 s at ISO 800: 450 stars drifting and rotating from frame to
# frame, a satellite trail across frame 5 (the stack must reject it), the same 300 hot pixels in
# every frame and in the dark, and frame 3 recorded as gyro-bumped (a re-stack must leave it out).
# Stacked from the session screen it should report 7 of 8 frames.
#
#   powershell -ExecutionPolicy Bypass -File tools\make-test-session.ps1 -Push
#   powershell -ExecutionPolicy Bypass -File tools\make-test-session.ps1 -Push -Serial emulator-5554
#
# Without -Push the session is only written to field-logs\test-sessions\.

param(
    [string]$Name = (Get-Date -Format 'yyyyMMdd') + '_030000_astro',
    [int]$Frames = 8,
    [switch]$Push,
    [string]$Serial = ''
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
                        img[y * W + x] += sa[i] * Math.Exp(-d2 / (2 * sigma * sigma));
                    }
            }
            if (f == 5)
                for (int x = 0; x < W; x++) {
                    double yy = 200 + 0.45 * x;
                    for (int y = (int)yy - 2; y <= (int)yy + 2; y++)
                        if (y >= 0 && y < H) img[y * W + x] += 400;
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
}
"@

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
foreach ($d in 'lights', 'darks', 'out', 'meta') { New-Item -ItemType Directory -Force (Join-Path $out $d) | Out-Null }
[StarCamTestSession]::Make($out, $Frames)

$startedAt = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
$framesJson = (0..($Frames - 1) | ForEach-Object {
    "{`"index`":$_,`"exposureNs`":20000000000,`"iso`":800,`"starCount`":300,`"fwhm`":2.4,`"aligned`":true," +
        "`"gyroPeak`":0.001,`"bumped`":$(if ($_ -eq 3) { 'true' } else { 'false' })}"
}) -join ','
$json = '{"header":{"mode":"ASTRO","quality":"FAST","stackConfig":{"sigmaFirst":3.5,"sigmaFinal":2.8,' +
    '"hotPixelFix":true,"lensShading":true,"gradientRemoval":true,"stretchStrength":1,"saturation":1.25,' +
    '"chromaDenoise":true,"starSharpen":false,"foregroundComposite":false,"featherPx":40,"style":0},' +
    "`"startedAt`":$startedAt,`"rawWidth`":1856,`"rawHeight`":1392,`"exposureNs`":20000000000,`"iso`":800," +
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
    Write-Host "pushed to $target$Name - open Sessions in the app and tap Stack"
}
