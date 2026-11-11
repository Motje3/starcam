package com.wm.starcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The simulated sky's scenarios, and how bright it is at a frame's exposure and ISO. */
class SkySimTest {

    private val ms = 1_000_000L

    @Test
    fun theOldScenariosParseAsBefore() {
        val s = SkySim.parse("frames=12;exp=300;clouds=5-8,14;drift=13:0.06;dawn=20;darks=skip")
        assertEquals(12, s.frames)
        assertEquals(300L, s.exposureMs)
        assertEquals(listOf(5..8, 14..14), s.clouds)
        assertEquals(13, s.driftAt)
        assertEquals(0.06f, s.driftDpt, 1e-6f)
        assertEquals(20, s.dawnAt)
        assertEquals(false, s.autoDarks)
        assertNull(s.skyPct)
        assertEquals(0.0, s.pedestalDn, 0.0)
        assertNull(s.probeMs)
    }

    @Test
    fun skyPedestalAndProbeParse() {
        val s = SkySim.parse(" frames=4 ; exp=300; sky=0.25 ;ped=6;probe=100")
        assertEquals(4, s.frames)
        assertEquals(0.25, s.skyPct!!, 1e-12)
        assertEquals(6.0, s.pedestalDn, 1e-12)
        assertEquals(100L, s.probeMs)
        // Nonsense is ignored, as for the other keys.
        val bad = SkySim.parse("sky=lots;ped=-3;probe=x")
        assertNull(bad.skyPct)
        assertEquals(0.0, bad.pedestalDn, 0.0)
        assertNull(bad.probeMs)
    }

    @Test
    fun theOldSkyIsUnchangedAtTheSettingsItWasWrittenFor() {
        // 0.3 s at ISO 1600: the emulator's longest exposure at its highest ISO.
        val old = SkySim.Scenario()
        assertEquals(0.035, SkySim.skyLevel(old, 300 * ms, 1600), 1e-12)
        assertEquals(1.0, SkySim.starGain(300 * ms, 1600), 1e-12)
        assertEquals(0.0, SkySim.pedestal(old, 1600), 0.0)
    }

    @Test
    fun skyAndStarsFollowExposureTimesIso() {
        val old = SkySim.Scenario()
        // Half the ISO, half the light; a third of the exposure, a third of it.
        assertEquals(0.0175, SkySim.skyLevel(old, 300 * ms, 800), 1e-12)
        assertEquals(0.035 / 3, SkySim.skyLevel(old, 100 * ms, 1600), 1e-12)
        assertEquals(0.5, SkySim.starGain(300 * ms, 800), 1e-12)
        // A sky given at 1 s x ISO 3200 is that there, and 0.15 of it at 0.3 s x ISO 1600.
        val given = SkySim.Scenario(skyPct = 20.0)
        assertEquals(0.20, SkySim.skyLevel(given, 1000 * ms, 3200), 1e-12)
        assertEquals(0.03, SkySim.skyLevel(given, 300 * ms, 1600), 1e-12)
    }

    @Test
    fun thePedestalScalesWithIsoOnly() {
        val s = SkySim.Scenario(pedestalDn = 80.0)
        assertEquals(80.0, SkySim.pedestal(s, 3200), 1e-12)
        assertEquals(40.0, SkySim.pedestal(s, 1600), 1e-12)
        assertEquals(20.0, SkySim.pedestal(s, 800), 1e-12)
    }
}
