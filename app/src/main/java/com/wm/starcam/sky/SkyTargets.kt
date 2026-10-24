package com.wm.starcam.sky

import kotlin.math.sqrt

/**
 * Something the sky view can lead the phone to: the Milky Way's core, the Moon, a planet, a
 * nebula or galaxy, a constellation, a bright star. The fixed ones carry their J2000 place; the
 * Moon and the planets are worked out for the moment asked.
 */
class SkyTarget(
    val name: String,
    val kind: Kind,
    val raDeg: Double = 0.0,
    val decDeg: Double = 0.0,
    /** What it is, in a few words ("galaxy · 2.5 million light years"). */
    val about: String? = null,
) {
    enum class Kind { CORE, MOON, PLANET, DEEP_SKY, CONSTELLATION, STAR }

    /** Where it is at [epochMs] from [loc]. */
    fun horizontal(epochMs: Long, loc: GeoLocation): Horizontal = when (kind) {
        Kind.MOON -> SkyMath.moon(epochMs, loc)
        Kind.PLANET -> {
            val p = Planets.places(epochMs).first { it.name == name }
            SkyMath.j2000ToHorizontal(p.raDeg, p.decDeg, epochMs, loc)
        }
        else -> SkyMath.j2000ToHorizontal(raDeg, decDeg, epochMs, loc)
    }

    /**
     * When it next climbs past [minAltDeg] after [fromMs], within a day: null when it is already
     * up, or does not come up that high in the next day.
     */
    fun rises(fromMs: Long, loc: GeoLocation, minAltDeg: Double = 5.0): Long? {
        if (horizontal(fromMs, loc).altDeg >= minAltDeg) return null
        val step = 10 * 60_000L
        var t = fromMs
        while (t < fromMs + 24 * 3_600_000L) {
            val next = t + step
            if (horizontal(next, loc).altDeg >= minAltDeg) {
                // To the minute between the two steps.
                var lo = t
                var hi = next
                while (hi - lo > 60_000L) {
                    val mid = (lo + hi) / 2
                    if (horizontal(mid, loc).altDeg >= minAltDeg) hi = mid else lo = mid
                }
                return hi
            }
            t = next
        }
        return null
    }
}

/** A target at its best in a night: when, and where it stands then. */
class BestTime(val target: SkyTarget, val atMs: Long, val altDeg: Double, val azDeg: Double)

/** Every target the sky view offers, in the order the list shows them. */
object SkyTargets {

    /**
     * How much a target is worth a phone's long exposure, 1-10: the core, the big bright nebulae
     * and galaxies, the bright planets and the famous figures first. What is not listed counts 3;
     * single stars do not make the list.
     */
    private val WORTH = mapOf(
        "Milky Way core" to 10, "Andromeda Galaxy" to 9, "Orion Nebula" to 9, "Large Magellanic Cloud" to 9,
        "Carina Nebula" to 9, "Pleiades" to 8, "Lagoon Nebula" to 8, "Jupiter" to 8, "Saturn" to 7,
        "Small Magellanic Cloud" to 7, "Omega Centauri" to 7, "North America Nebula" to 7,
        "Rho Ophiuchi clouds" to 7, "Orion" to 7, "Scorpius" to 7, "Southern Cross" to 7, "Mars" to 6,
        "Double Cluster" to 6, "Coalsack" to 6, "Sagittarius Star Cloud" to 6, "Cygnus" to 6,
        "Sagittarius" to 6, "Big Dipper (Ursa Major)" to 6, "Venus" to 6, "Hyades" to 5, "Beehive Cluster" to 5,
        "Ptolemy Cluster" to 5, "Cassiopeia" to 5, "Triangulum Galaxy" to 5, "47 Tucanae" to 5,
        "Hercules Cluster" to 4, "Mercury" to 2,
    )

    /**
     * The [count] targets most worth a shot between [fromMs] and [toMs] (the dark of the night) at
     * [loc]: each at its highest in that time, at least [minAltDeg] up (the core from 10, being
     * low from much of the north), best first - how much it is worth, and how high it gets.
     */
    fun bestOf(
        targets: List<SkyTarget>, fromMs: Long, toMs: Long, loc: GeoLocation, count: Int = 5, minAltDeg: Double = 25.0,
    ): List<BestTime> {
        if (toMs <= fromMs) return emptyList()
        val step = 15 * 60_000L
        val out = ArrayList<Pair<BestTime, Double>>()
        for (t in targets) {
            if (t.kind == SkyTarget.Kind.STAR || t.kind == SkyTarget.Kind.MOON) continue
            var best: BestTime? = null
            var at = fromMs
            while (at <= toMs) {
                val h = t.horizontal(at, loc)
                if (best == null || h.altDeg > best.altDeg) best = BestTime(t, at, h.altDeg, h.azDeg)
                at += step
            }
            val b = best ?: continue
            val floor = if (t.kind == SkyTarget.Kind.CORE) 10.0 else minAltDeg
            if (b.altDeg < floor) continue
            out += b to (WORTH[t.name] ?: 3) + b.altDeg / 30.0
        }
        return out.sortedByDescending { it.second }.take(count).map { it.first }
    }

    /** The constellations with a figure, by their better-known names where they have one. */
    private val POPULAR = mapOf("UMa" to "Big Dipper (Ursa Major)", "UMi" to "Little Dipper (Ursa Minor)")

    fun all(catalog: StarCatalog): List<SkyTarget> {
        val out = ArrayList<SkyTarget>()
        out += SkyTarget("Milky Way core", SkyTarget.Kind.CORE, DeepSky.CORE_RA, DeepSky.CORE_DEC,
            "the centre of our galaxy · 26,000 light years")
        out += SkyTarget("Moon", SkyTarget.Kind.MOON)
        for (p in listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn")) out += SkyTarget(p, SkyTarget.Kind.PLANET)
        for (o in DeepSky.ALL) {
            out += SkyTarget(o.name, SkyTarget.Kind.DEEP_SKY, o.raDeg, o.decDeg,
                o.kind + (o.distanceLy?.let { " · " + SkyLabels.lightYears(it) } ?: ""))
        }
        // A constellation is where its figure's stars are, on average.
        val lines = catalog.lines
        val sums = HashMap<String, DoubleArray>()
        for (i in lines.indices) {
            val s = catalog.stars[lines[i]]
            val abbr = s.designation?.substringAfterLast(' ') ?: continue
            val v = sums.getOrPut(abbr) { DoubleArray(4) }
            val ra = Math.toRadians(s.raDeg)
            val dec = Math.toRadians(s.decDeg)
            v[0] += Math.cos(dec) * Math.cos(ra)
            v[1] += Math.cos(dec) * Math.sin(ra)
            v[2] += Math.sin(dec)
        }
        for (abbr in StarCatalog.FIGURES.keys) {
            val v = sums[abbr] ?: continue
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            val ra = SkyMath.norm360(Math.toDegrees(Math.atan2(v[1], v[0])))
            val dec = Math.toDegrees(Math.asin(v[2] / n))
            val name = POPULAR[abbr] ?: SkyLabels.CONSTELLATIONS[abbr] ?: continue
            out += SkyTarget(name, SkyTarget.Kind.CONSTELLATION, ra, dec, "constellation")
        }
        // The brightest stars, and the one that shows the way north.
        for (s in catalog.stars.filter { it.name != null && (it.mag < 1.5f || it.name == "Polaris") }.sortedBy { it.mag }) {
            val name = if (s.name == "Polaris") "Polaris, the North Star" else s.name!!
            val ly = SkyLabels.STAR_LY[s.name]
            out += SkyTarget(name, SkyTarget.Kind.STAR, s.raDeg, s.decDeg,
                "star" + (ly?.let { " · " + SkyLabels.lightYears(it) } ?: ""))
        }
        return out
    }
}
