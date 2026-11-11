package com.wm.starcam.camera

import com.wm.starcam.engine.AnalyzeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exposure decision and its check on the first frame, with the numbers of the night of
 * 31 Oct 2026 (CORE_REVIEW.md, finding 1): a OnePlus CPH2769 at 22.5 s, ISO 100..6400.
 */
class AutoExposureTest {

    /** The phone of the real sessions: 79 µs..30 s, ISO 100..6400. */
    private val phone = AutoExposure.Limits(isoMin = 100, isoMax = 6400, exposureMinNs = 79_000L, exposureMaxNs = 30_000_000_000L)
    private val second = 1_000_000_000L
    private val trailCap = 22.5

    private fun probe(bgFrac: Double, stars: Int = 40) = AnalyzeStats(bgFrac.toFloat(), stars, 2.5f, 0.001f)

    private fun decision(exposureSec: Double, iso: Int, frames: Int = 20) =
        AutoExposure.Decision((exposureSec * second).toLong(), iso, frames, "plan")

    // ---- decide()

    @Test
    fun theRealNightGetsIso800AtTheTrailLimit() {
        // Probe 0.89% of white at 1 s / ISO 3200: scaled to 22.5 s that is ISO 800, a planned 5.0%.
        val d = AutoExposure.decide(probe(0.0089), second, 3200, phone, baseFrames = 20, trailCapSec = trailCap)
        assertEquals(22_500_000_000L, d.exposureNs)
        assertEquals(800, d.iso)
        assertEquals(20, d.frames)
        assertTrue(d.reasoning, d.reasoning.contains("ISO 800"))
    }

    @Test
    fun aLearntProbeOffsetComesOffBeforeScaling() {
        // 0.6% of the 0.89% is the probe's own offset: the sky is 0.29%, worth ISO 1600 at 22.5 s.
        val d = AutoExposure.decide(probe(0.0089), second, 3200, phone, baseFrames = 20, trailCapSec = trailCap,
            probeOffset = 0.006)
        assertEquals(1600, d.iso)
        assertTrue(d.reasoning, d.reasoning.contains("probe offset"))
    }

    @Test
    fun anOffsetNeverTakesMoreThanNinetyPercentOfTheProbe() {
        // An offset learnt bigger than tonight's whole probe leaves a tenth of it: 0.1% here, which
        // asks for ISO 5689 -> the 6400 stop.
        val d = AutoExposure.decide(probe(0.01), second, 3200, phone, baseFrames = 20, trailCapSec = trailCap,
            probeOffset = 0.05)
        assertEquals(6400, d.iso)
    }

    @Test
    fun noOffsetIsTheOldDecisionAtTheNewTarget() {
        val plain = AutoExposure.decide(probe(0.0089), second, 3200, phone, baseFrames = 20, trailCapSec = trailCap)
        val zero = AutoExposure.decide(probe(0.0089), second, 3200, phone, baseFrames = 20, trailCapSec = trailCap,
            probeOffset = 0.0)
        assertEquals(plain, zero)
        assertFalse(plain.reasoning.contains("offset"))
        assertTrue(plain.reasoning, plain.reasoning.contains("→4%"))
    }

    @Test
    fun aBrightSkyShortensTheExposureAtTheLowestIso() {
        // A probe of 30% at 1 s / ISO 3200 needs ISO 19 at 22.5 s: the lowest stop and a shorter frame.
        val d = AutoExposure.decide(probe(0.30), second, 3200, phone, baseFrames = 20, trailCapSec = trailCap)
        assertEquals(200, d.iso)
        assertTrue(d.exposureNs < 22_500_000_000L)
        assertTrue(d.frames > 20)
    }

    // ---- refine()

    @Test
    fun aPilotInsideTheBandIsKeptAsFrameOne() {
        val planned = decision(22.5, 800)
        val r = AutoExposure.refine(planned, 0.041f, 300, phone)
        assertTrue(r.keepPilot)
        assertEquals(planned.exposureNs, r.decision.exposureNs)
        assertEquals(800, r.decision.iso)
        assertEquals(20, r.decision.frames)
        assertTrue(r.decision.reasoning, r.decision.reasoning.startsWith("plan · sky measured"))
        // Both ends of the band count as inside.
        assertTrue(AutoExposure.refine(planned, 0.025f, 300, phone).keepPilot)
        assertTrue(AutoExposure.refine(planned, 0.0799f, 300, phone).keepPilot)
    }

    @Test
    fun theRealNightIsCorrectedToIso1600() {
        // ISO 800 at 22.5 s was meant to give 5%; the frame has 1.55%. ISO 1600 gives 3.1%.
        val r = AutoExposure.refine(decision(22.5, 800), 0.0155f, 300, phone)
        assertFalse(r.keepPilot)
        assertEquals(1600, r.decision.iso)
        assertEquals(22_500_000_000L, r.decision.exposureNs)
        assertEquals(20, r.decision.frames)
        assertEquals(0.031, r.predictedBg, 1e-6)
        assertEquals(0.0155, r.pilotBg, 1e-6)
        assertEquals(300, r.pilotStars)
        assertTrue(r.note, r.note.contains("→ ISO 1600"))
        assertTrue(r.decision.reasoning, r.decision.reasoning.endsWith(r.note))
    }

    @Test
    fun aDarkSiteGoesToTheTopStop() {
        // ISO 1600 and a sky of 0.15%: even ISO 6400 only reaches 0.6%. The top stop it is.
        val r = AutoExposure.refine(decision(22.5, 1600), 0.0015f, 600, phone)
        assertFalse(r.keepPilot)
        assertEquals(6400, r.decision.iso)
        assertEquals(0.006, r.predictedBg, 1e-6)
    }

    @Test
    fun headroomKeepsToTheLowestStopThatWillDo() {
        // ISO 800 and 1.5%: ISO 1600 (3%) and ISO 3200 (6%) are both in the band; 1600 leaves the
        // star cores their headroom.
        val r = AutoExposure.refine(decision(22.5, 800), 0.015f, 300, phone)
        assertEquals(1600, r.decision.iso)
        // From ISO 400 and 0.6%: 1600 gives 2.4% (just short), 3200 gives 4.8%. Above 1600 only
        // because 1600 will not do.
        assertEquals(3200, AutoExposure.refine(decision(22.5, 400), 0.006f, 300, phone).decision.iso)
    }

    @Test
    fun aTooBrightPilotLowersTheIso() {
        // ISO 1600 and 12%: ISO 800 gives 6%, the highest stop in the band.
        val r = AutoExposure.refine(decision(22.5, 1600), 0.12f, 300, phone)
        assertFalse(r.keepPilot)
        assertEquals(800, r.decision.iso)
        assertEquals(22_500_000_000L, r.decision.exposureNs)
        assertEquals(20, r.decision.frames)
        assertEquals(0.06, r.predictedBg, 1e-6)
    }

    @Test
    fun atTheLowestStopTooBrightShortensTheExposureForMoreFrames() {
        // ISO 400 and 40%: ISO 200 still gives 20%. 22.5 s cut to 4.5 s puts it at 4%, and five
        // times the frames keep the total time.
        val r = AutoExposure.refine(decision(22.5, 400), 0.40f, 300, phone)
        assertFalse(r.keepPilot)
        assertEquals(200, r.decision.iso)
        assertEquals(4.5e9, r.decision.exposureNs.toDouble(), 1e3)  // 0.40f is not quite 0.4
        assertEquals(99, r.decision.frames)  // 100 frames, at most 99
        assertEquals(0.04, r.predictedBg, 1e-6)
        val few = AutoExposure.refine(decision(22.5, 400, frames = 8), 0.40f, 300, phone)
        assertEquals(40, few.decision.frames)
    }

    @Test
    fun theExposureIsNeverCutBelowOneSecond() {
        // 2 s at the floor ISO and 90%: 0.09 s would be the aim, 1 s is the floor.
        val r = AutoExposure.refine(decision(2.0, 200, frames = 10), 0.90f, 300, phone)
        assertEquals(1_000_000_000L, r.decision.exposureNs)
        assertEquals(20, r.decision.frames)
        assertEquals(200, r.decision.iso)
    }

    @Test
    fun theCamerasIsoRangeLimitsTheCorrection() {
        // A camera that stops at ISO 1000: from 800 and 1.55% it can only go to 1000 (1.9%).
        val upTo1000 = phone.copy(isoMax = 1000)
        val r = AutoExposure.refine(decision(22.5, 800), 0.0155f, 300, upTo1000)
        assertEquals(1000, r.decision.iso)
        assertFalse(r.keepPilot)
        // Already at its highest: nothing to change, the pilot stays frame 1.
        val atTop = AutoExposure.refine(decision(22.5, 1000), 0.0015f, 300, upTo1000)
        assertTrue(atTop.keepPilot)
        assertEquals(1000, atTop.decision.iso)
        // The emulator's camera: 0.3 s at most, ISO 100..1600. Too bright at ISO 200 and an
        // exposure under 1 s: nothing to shorten, the ISO stays at the floor stop.
        val emulator = AutoExposure.Limits(100, 1600, 1_000L, 300_000_000L)
        val bright = AutoExposure.refine(AutoExposure.Decision(300_000_000L, 200, 8, "plan"), 0.5f, 10, emulator)
        assertTrue(bright.keepPilot)
        assertEquals(300_000_000L, bright.decision.exposureNs)
        // A camera whose lowest ISO is 400: the floor stop is 400, never 200.
        val from400 = phone.copy(isoMin = 400)
        assertEquals(400, AutoExposure.refine(decision(22.5, 1600), 0.30f, 300, from400).decision.iso)
    }

    @Test
    fun anUnmeasuredPilotChangesNothing() {
        val planned = decision(22.5, 800)
        val r = AutoExposure.refine(planned, 0f, 0, phone)
        assertTrue(r.keepPilot)
        assertEquals(800, r.decision.iso)
        assertTrue(AutoExposure.refine(planned, Float.NaN, 0, phone).keepPilot)
    }

    // ---- the probe's offset

    @Test
    fun thePilotSaysWhatTheProbeReadBeyondTheSky() {
        // Probe 0.89% at 1 s / ISO 3200; pilot 1.55% at 22.5 s / ISO 800, which is 0.276% at the
        // probe's settings: 0.61% of the probe was its own.
        val off = AutoExposure.impliedProbeOffset(0.0089, second, 3200, 0.0155, 22_500_000_000L, 800, 300)
        assertNotNull(off)
        assertEquals(0.0089 - 0.0155 * (3200.0 / (800 * 22.5)), off!!, 1e-9)
        assertEquals(0.00614, off, 1e-4)
    }

    @Test
    fun onlyAClearDarkPilotTeachesTheOffset() {
        val args = { stars: Int, pilotBg: Double ->
            AutoExposure.impliedProbeOffset(0.0089, second, 3200, pilotBg, 22_500_000_000L, 800, stars)
        }
        assertNull(args(19, 0.0155))            // too few stars
        assertNotNull(args(20, 0.0155))
        assertNull(args(300, 0.15))             // a sky too bright to say
        // An offset below zero (the probe darker than the sky) or above 3% is not believed.
        assertNull(AutoExposure.impliedProbeOffset(0.001, second, 3200, 0.0155, 22_500_000_000L, 800, 300))
        assertNull(AutoExposure.impliedProbeOffset(0.05, second, 3200, 0.0155, 22_500_000_000L, 800, 300))
    }

    @Test
    fun theStoredOffsetIsSmoothed() {
        assertEquals(0.006, AutoExposure.learnOffset(null, 0.006), 1e-12)
        assertEquals(0.7 * 0.006 + 0.3 * 0.004, AutoExposure.learnOffset(0.006, 0.004), 1e-12)
        // Repeated measurements of the same offset converge on it.
        var v: Double? = null
        repeat(30) { v = AutoExposure.learnOffset(v, 0.005) }
        assertEquals(0.005, v!!, 1e-9)
    }

    @Test
    fun theLoopClosesOnTheRealNight() {
        // Night one: no offset known. The pilot corrects the ISO and teaches the offset.
        val first = AutoExposure.decide(probe(0.0089), second, 3200, phone, baseFrames = 20, trailCapSec = trailCap)
        val r = AutoExposure.refine(first, 0.0155f, 300, phone)
        assertEquals(1600, r.decision.iso)
        val off = AutoExposure.impliedProbeOffset(0.0089, second, 3200, 0.0155, first.exposureNs, first.iso, 300)!!
        // Night two, the same sky: the first guess is right, the pilot stays frame 1.
        val nightTwo = AutoExposure.decide(probe(0.0089), second, 3200, phone, baseFrames = 20, trailCapSec = trailCap,
            probeOffset = AutoExposure.learnOffset(null, off))
        assertEquals(1600, nightTwo.iso)
        assertTrue(AutoExposure.refine(nightTwo, 0.031f, 300, phone).keepPilot)
    }
}
