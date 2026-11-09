package com.wm.starcam.sensors

import com.wm.starcam.sky.GeoLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchTest {

    private val old = GeoLocation(52.0, 4.0)
    private val answer = GeoLocation(53.0, 5.0)

    private class Run(val found: List<GeoLocation>, val asked: Boolean, val searching: Boolean, val ok: Boolean)

    private fun run(known: KnownPlace?, reply: GeoLocation?): Run = runBlocking {
        val found = mutableListOf<GeoLocation>()
        var asked = false
        var searching = false
        val ok = searchPlace(known, { asked = true; reply }, { found += it }, { searching = true })
        Run(found, asked, searching, ok)
    }

    @Test
    fun aPositionFromTheLastHalfHourAnswersAtOnceWithoutAsking() {
        val r = run(KnownPlace(old, 29 * 60_000L), answer)
        assertEquals(listOf(old), r.found)
        assertFalse(r.asked)
        assertFalse(r.searching)
        assertTrue(r.ok)
    }

    @Test
    fun aPositionFromTodayIsShownFirstAndImprovedOnQuietly() {
        val r = run(KnownPlace(old, 5 * 3_600_000L), answer)
        assertEquals(listOf(old, answer), r.found)
        assertTrue(r.asked)
        assertFalse(r.searching)
        assertTrue(r.ok)
    }

    @Test
    fun aPositionFromTodayStandsWhenNothingBetterComes() {
        val r = run(KnownPlace(old, 5 * 3_600_000L), null)
        assertEquals(listOf(old), r.found)
        assertTrue(r.ok)
    }

    @Test
    fun anOldPositionWaitsForTheSearchAndIsTheFallback() {
        val better = run(KnownPlace(old, 2 * 86_400_000L), answer)
        assertEquals(listOf(answer), better.found)
        assertTrue(better.searching)
        val none = run(KnownPlace(old, 2 * 86_400_000L), null)
        assertEquals(listOf(old), none.found)
        assertTrue(none.ok)
    }

    @Test
    fun nothingKnownAndNoAnswerIsNotFound() {
        val r = run(null, null)
        assertTrue(r.found.isEmpty())
        assertTrue(r.searching)
        assertFalse(r.ok)
    }
}
