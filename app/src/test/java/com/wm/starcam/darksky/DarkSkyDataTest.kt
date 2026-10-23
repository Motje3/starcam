package com.wm.starcam.darksky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The dark-sky map's data as the app reads it from its assets: the light-pollution grid against
 * places whose skies are known, the base map, and the spot finder.
 */
class DarkSkyDataTest {

    companion object {
        lateinit var data: DarkSkyData

        @BeforeClass
        @JvmStatic
        fun load() {
            data = DarkSkyData.read({ File("src/main/assets/darksky/$it").inputStream() })
        }
    }

    @Test
    fun citiesAreBrightAndDarkPlacesAreDark() {
        val g = data.glow
        // City centres: tens to a hundred times the natural all-sky brightness.
        assertTrue("Amsterdam ${g.alr(52.373, 4.893)}", g.alr(52.373, 4.893) in 8.0..60.0)
        assertTrue("Times Square ${g.alr(40.758, -73.985)}", g.alr(40.758, -73.985) > 50.0)
        assertTrue("Paris ${g.alr(48.857, 2.352)}", g.alr(48.857, 2.352) > 20.0)
        // Certified dark-sky places and deserts.
        assertTrue("Lauwersmeer ${g.alr(53.38, 6.22)}", g.alr(53.38, 6.22) < 1.5)
        assertTrue("Death Valley ${g.alr(36.457, -116.866)}", g.alr(36.457, -116.866) < 0.5)
        assertTrue("Natural Bridges ${g.alr(37.601, -110.010)}", g.alr(37.601, -110.010) < 0.1)
        // Bortle classes that follow: inner-city to pristine.
        assertTrue(SkyQuality.bortle(SkyQuality.sqm(g.alr(40.758, -73.985))) >= 6)
        assertTrue(SkyQuality.bortle(SkyQuality.sqm(g.alr(37.601, -110.010))) <= 2)
    }

    @Test
    fun waterIsWater() {
        val g = data.glow
        assertEquals(0, g.value(54.0, 3.0))     // North Sea
        assertEquals(0, g.value(52.75, 5.40))   // IJsselmeer, a lake in Natural Earth
        assertEquals(0, g.value(0.0, -150.0))   // Pacific
        assertTrue(g.value(52.0, 5.5) > 0)      // the Veluwe, land
        // Outside the map's latitudes: no data, read as water.
        assertEquals(0, g.value(80.0, 10.0))
    }

    @Test
    fun skyQualityScale() {
        assertEquals(22.0, SkyQuality.sqm(0.0), 1e-9)
        assertEquals(1, SkyQuality.bortle(22.0))
        assertEquals(4, SkyQuality.bortle(21.0))
        assertEquals(9, SkyQuality.bortle(17.0))
        // Stored values round-trip within a step (1/40 decade).
        for (alr in listOf(0.01, 0.3, 3.0, 30.0, 300.0)) {
            val back = data.glow.alr(GlowGrid.valueOf(alr))
            assertEquals(alr, back, alr * 0.06)
        }
    }

    @Test
    fun theBaseMapIsComplete() {
        assertTrue(data.coast.size > 4000)
        assertTrue(data.lakes.size > 1000)
        assertTrue(data.borders.size > 5000)
        assertTrue(data.roads.size > 20000)
        assertTrue(data.towns.size > 7000)
        assertTrue(data.darkSites.size > 100)
        // Largest first; Amsterdam is there.
        assertTrue(data.towns.first().value > 10_000_000)
        assertNotNull(data.towns.firstOrNull { it.name == "Amsterdam" })
        assertNotNull(data.darkSites.firstOrNull { "Lauwersmeer" in it.name })
        // Every line's box holds its points.
        val c = data.coast
        for (k in 0 until c.size step 97) {
            for (i in c.starts[k] until c.starts[k + 1]) {
                assertTrue(c.coords[2 * i] >= c.bbox[4 * k] && c.coords[2 * i] <= c.bbox[4 * k + 2])
                assertTrue(c.coords[2 * i + 1] >= c.bbox[4 * k + 1] && c.coords[2 * i + 1] <= c.bbox[4 * k + 3])
            }
        }
    }

    @Test
    fun fromAmsterdamTheFinderPointsToDarkerSkies() {
        val r = SpotFinder.find(data, 52.373, 4.893)
        assertTrue("here Bortle ${r.hereBortle}", r.hereBortle >= 5)
        val darker = r.nearestDarker
        assertNotNull(darker)
        assertTrue(darker!!.bortle < r.hereBortle)
        assertTrue(darker.sqm >= r.hereSqm + 0.5)
        assertTrue(darker.distanceKm < 150)
        assertTrue(r.darkest.isNotEmpty())
        // Each farther suggestion is darker than the ones before it.
        var prev = darker.sqm
        for (s in r.darkest) {
            assertTrue("${s.title}: ${s.sqm} after $prev", s.sqm >= prev + 0.149)
            prev = s.sqm
        }
        // The Dutch dark-sky places are within reach.
        val site = r.darkSite
        assertNotNull(site)
        assertTrue(site!!.distanceKm < 250)
        assertTrue(site.near == null || site.near!!.contains(" of ") || site.near!!.startsWith("at "))
    }

    @Test
    fun underAPristineSkyThereIsNothingDarkerToFind() {
        val r = SpotFinder.find(data, -25.0, 16.0)  // the Namib
        assertTrue(r.hereBortle <= 2)
        assertNull(r.nearestDarker)
        assertTrue(r.darkest.isEmpty())
    }

    /** Not a check: what the finder says for a few places, in the test report's output. */
    @Test
    fun printSuggestions() {
        for ((name, lat, lon) in listOf(
            Triple("Amsterdam", 52.373, 4.893), Triple("Utrecht", 52.090, 5.121), Triple("London", 51.507, -0.128),
            Triple("Madrid", 40.417, -3.704), Triple("Denver", 39.739, -104.990), Triple("Sydney", -33.869, 151.209),
        )) {
            val r = SpotFinder.find(data, lat, lon)
            println("$name: Bortle ${r.hereBortle}, SQM %.2f".format(r.hereSqm))
            r.all.forEach { s ->
                println("   %-28s Bortle %d SQM %.2f %4.0f km %-2s %s".format(s.title, s.bortle, s.sqm, s.distanceKm,
                    SpotFinder.compass(s.bearingDeg), s.near ?: ""))
            }
        }
    }

    @Test
    fun distancesAndBearings() {
        // Amsterdam to Utrecht: 35 km, south-east.
        assertEquals(35.0, SpotFinder.distanceKm(52.373, 4.893, 52.090, 5.121), 1.5)
        assertEquals("SE", SpotFinder.compass(SpotFinder.bearingDeg(52.373, 4.893, 52.090, 5.121)))
        assertEquals("N", SpotFinder.compass(SpotFinder.bearingDeg(0.0, 0.0, 1.0, 0.0)))
        assertEquals("W", SpotFinder.compass(SpotFinder.bearingDeg(0.0, 0.0, 0.0, -1.0)))
    }
}
