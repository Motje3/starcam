package com.wm.starcam.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class SkyTargetsTest {

    private val catalog = StarCatalog.parse(File("src/main/assets/sky/stars.txt").readText())
    private val targets = SkyTargets.all(catalog)
    private val amsterdam = GeoLocation(52.37, 4.90)

    private fun target(name: String) = targets.first { it.name == name }

    @Test
    fun theListHasWhatPeopleLookFor() {
        val names = targets.map { it.name }
        for (n in listOf("Milky Way core", "Moon", "Jupiter", "Andromeda Galaxy", "Orion Nebula", "Pleiades", "Orion",
            "Big Dipper (Ursa Major)", "Cassiopeia", "Southern Cross", "Polaris, the North Star", "Sirius", "Vega")) {
            assertTrue(n, n in names)
        }
        assertEquals(names.size, names.toSet().size)
        assertEquals(SkyTarget.Kind.CORE, targets.first().kind)
    }

    @Test
    fun polarisStandsAtTheLatitude() {
        val polaris = target("Polaris, the North Star")
        for (h in listOf(0L, 6L, 12L, 18L)) {
            val at = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli() + h * 3_600_000L
            val pos = polaris.horizontal(at, amsterdam)
            assertEquals(52.37, pos.altDeg, 1.0)
            assertEquals(0.0, SkyMath.wrap180(pos.azDeg), 1.5)
        }
    }

    @Test
    fun orionComesUpLateOnAnOctoberEvening() {
        val orion = target("Orion")
        val evening = Instant.parse("2026-10-24T18:00:00Z").toEpochMilli()
        assertTrue(orion.horizontal(evening, amsterdam).altDeg < 0)
        val rises = orion.rises(evening, amsterdam)
        assertNotNull(rises)
        val utcHour = (rises!! - Instant.parse("2026-10-24T00:00:00Z").toEpochMilli()) / 3_600_000.0
        assertTrue("rises at $utcHour UT", utcHour in 20.0..22.5)
        // Up, it gets no "rises".
        assertNull(orion.rises(rises + 3_600_000L, amsterdam))
    }

    @Test
    fun theSouthernCrossNeverRisesInAmsterdam() {
        val now = Instant.parse("2026-10-24T18:00:00Z").toEpochMilli()
        assertNull(target("Southern Cross").rises(now, amsterdam))
        assertNull(target("Large Magellanic Cloud").rises(now, amsterdam))
        // From Sydney it is up at some hour of the day.
        val sydney = GeoLocation(-33.87, 151.21)
        val cross = target("Southern Cross")
        assertTrue((0 until 24).any { cross.horizontal(now + it * 3_600_000L, sydney).altDeg > 10 })
    }

    @Test
    fun worthAShotOnAnAutumnNightInAmsterdam() {
        // 24-25 October 2026, astronomical night about 19:20 to 05:15 UT.
        val from = Instant.parse("2026-10-24T19:30:00Z").toEpochMilli()
        val to = Instant.parse("2026-10-25T05:00:00Z").toEpochMilli()
        val best = SkyTargets.bestOf(targets, from, to, amsterdam)
        val names = best.map { it.target.name }
        println("worth a shot, Amsterdam 24-25 Oct 2026: " + best.joinToString { "${it.target.name} ${it.altDeg.toInt()} deg at ${Instant.ofEpochMilli(it.atMs)}" })
        assertEquals(5, best.size)
        // Autumn's showpieces, high in the dark.
        assertTrue(names.toString(), "Andromeda Galaxy" in names)
        assertTrue(names.toString(), "Pleiades" in names || "Orion Nebula" in names)
        // Nothing that never gets up from here, and no single stars.
        assertTrue(names.none { it in listOf("Southern Cross", "Large Magellanic Cloud", "Carina Nebula") })
        assertTrue(best.none { it.target.kind == SkyTarget.Kind.STAR })
        // Each at its best: Andromeda near the zenith, before midnight.
        val m31 = best.first { it.target.name == "Andromeda Galaxy" }
        assertTrue("${m31.altDeg}", m31.altDeg > 75)
        assertTrue(m31.atMs in from..to)
    }

    @Test
    fun theCoreCountsFromLowerDown() {
        // Sydney in June: the core high in the middle of the night, first on the list.
        val sydney = GeoLocation(-33.87, 151.21)
        val from = Instant.parse("2026-06-15T09:00:00Z").toEpochMilli()
        val to = Instant.parse("2026-06-15T19:00:00Z").toEpochMilli()
        val best = SkyTargets.bestOf(targets, from, to, sydney)
        assertEquals("Milky Way core", best.first().target.name)
    }

    @Test
    fun aConstellationIsWhereItsStarsAre() {
        val orion = target("Orion")
        assertEquals(84.0, orion.raDeg, 4.0)
        assertEquals(1.0, orion.decDeg, 6.0)
        val cassiopeia = target("Cassiopeia")
        assertTrue(cassiopeia.decDeg > 55)
    }
}
