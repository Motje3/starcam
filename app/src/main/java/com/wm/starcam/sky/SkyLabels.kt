package com.wm.starcam.sky

import kotlin.math.atan
import kotlin.math.pow

/** Something named on the photo: where (px), what, and how it is drawn. */
class PhotoLabel(
    val kind: Kind,
    val text: String,
    val x: Float,
    val y: Float,
    /** Its size on the photo (px), for an object bigger than a point. */
    val radiusPx: Float = 0f,
    val detail: String? = null,
) {
    enum class Kind { CONSTELLATION, STAR, PLANET, DEEP_SKY, CORE }
}

/** A named star in the photo, for the list under it. */
class NamedStar(val name: String, val mag: Float, val distanceLy: Double?)

/**
 * What a solved photo shows, in its own pixels: the constellation figures, the names to draw
 * (most important first) and the facts to list - which constellations, which named stars and how
 * far, the planets that night, the nebulae, clusters and galaxies, the Milky Way's centre.
 */
class SkyLabels(
    val width: Int,
    val height: Int,
    /** Constellation figures: x0, y0, x1, y1 per segment (px). */
    val segments: FloatArray,
    /** Drawing order: whatever does not fit beside an earlier label is left off. */
    val labels: List<PhotoLabel>,
    /** The constellations in the photo, the most prominent first. */
    val constellations: List<String>,
    val stars: List<NamedStar>,
    val planets: List<String>,
    val deepSky: List<DeepSkyObject>,
    val hasCore: Boolean,
    val fovAcrossDeg: Double,
    val fovUpDeg: Double,
    val raDeg: Double,
    val decDeg: Double,
    val matched: Int,
) {
    /** The farthest thing in the photo with a known distance: its name and light years. */
    fun farthest(): Pair<String, Double>? {
        val all = deepSky.mapNotNull { o -> o.distanceLy?.let { o.name to it } } +
            stars.mapNotNull { s -> s.distanceLy?.let { s.name to it } } +
            (if (hasCore) listOf("the centre of the Milky Way" to DeepSky.CORE_LY) else emptyList())
        return all.maxByOrNull { it.second }
    }

    companion object {

        /**
         * Labels for a [width] x [height] photo solved as [sol]. [epochMs], when the photo was
         * taken, places the planets; without it they are left out.
         */
        fun build(sol: PlateSolution, width: Int, height: Int, catalog: StarCatalog, epochMs: Long?): SkyLabels {
            fun inside(p: Pair<Double, Double>, margin: Double = 0.0) =
                p.first >= -margin && p.second >= -margin && p.first <= width + margin && p.second <= height + margin
            val reach = 0.5 * maxOf(width, height).toDouble()

            val segs = ArrayList<Float>()
            val lines = catalog.lines
            for (i in 0 until lines.size step 2) {
                val a = catalog.stars[lines[i]]
                val b = catalog.stars[lines[i + 1]]
                val pa = sol.project(a.raDeg, a.decDeg) ?: continue
                val pb = sol.project(b.raDeg, b.decDeg) ?: continue
                // Both ends near enough to be trusted, one of them in the photo.
                if (!inside(pa, reach) || !inside(pb, reach) || (!inside(pa) && !inside(pb))) continue
                segs += pa.first.toFloat()
                segs += pa.second.toFloat()
                segs += pb.first.toFloat()
                segs += pb.second.toFloat()
            }

            // Constellations by their stars in the photo - the many and the central over one
            // brilliant star in a corner (Orion's photo is not Canis Major's for Sirius) - and
            // where those stars are.
            class Con(var weight: Double = 0.0, var sx: Double = 0.0, var sy: Double = 0.0, var n: Int = 0)
            val cons = HashMap<String, Con>()
            val named = ArrayList<Pair<Star, Pair<Double, Double>>>()
            val half2 = (width * width + height * height) / 4.0
            for (s in catalog.stars) {
                if (s.mag > 4.5f) continue
                val p = sol.project(s.raDeg, s.decDeg) ?: continue
                if (!inside(p)) continue
                if (s.name != null) named += s to p
                val abbr = s.designation?.substringAfterLast(' ') ?: continue
                val c = cons.getOrPut(abbr) { Con() }
                val r2 = (p.first - width / 2.0).pow(2) + (p.second - height / 2.0).pow(2)
                c.weight += 10.0.pow(-0.2 * s.mag) * (1 - 0.5 * r2 / half2)
                c.sx += p.first
                c.sy += p.second
                c.n++
            }
            val shown = cons.entries.filter { it.value.n >= 2 && CONSTELLATIONS[it.key] != null }
                .sortedByDescending { it.value.weight }
            val labels = ArrayList<PhotoLabel>()
            for ((abbr, c) in shown.take(8)) {
                labels += PhotoLabel(PhotoLabel.Kind.CONSTELLATION, CONSTELLATIONS.getValue(abbr),
                    (c.sx / c.n).toFloat(), (c.sy / c.n).toFloat())
            }

            val core = sol.project(DeepSky.CORE_RA, DeepSky.CORE_DEC)?.takeIf { inside(it) }
            if (core != null) {
                labels += PhotoLabel(PhotoLabel.Kind.CORE, "Centre of the Milky Way", core.first.toFloat(),
                    core.second.toFloat(), detail = "26,000 light years")
            }

            val planets = ArrayList<String>()
            if (epochMs != null) {
                for (pl in Planets.places(epochMs)) {
                    val p = sol.project(pl.raDeg, pl.decDeg) ?: continue
                    if (!inside(p)) continue
                    planets += pl.name
                    labels += PhotoLabel(PhotoLabel.Kind.PLANET, pl.name, p.first.toFloat(), p.second.toFloat())
                }
            }

            val pxPerDeg = sol.scale * Math.PI / 180
            val deep = ArrayList<DeepSkyObject>()
            for (o in DeepSky.ALL) {
                val p = sol.project(o.raDeg, o.decDeg) ?: continue
                if (!inside(p)) continue
                deep += o
                labels += PhotoLabel(PhotoLabel.Kind.DEEP_SKY, o.name, p.first.toFloat(), p.second.toFloat(),
                    radiusPx = (o.sizeDeg / 2 * pxPerDeg).toFloat(), detail = o.distanceLy?.let { lightYears(it) })
            }

            named.sortBy { it.first.mag }
            for ((s, p) in named.take(16)) {
                labels += PhotoLabel(PhotoLabel.Kind.STAR, s.name!!, p.first.toFloat(), p.second.toFloat())
            }

            val (ra, dec) = PlateSolver.pixelToSky(width / 2.0, height / 2.0, sol)
            return SkyLabels(
                width = width,
                height = height,
                segments = segs.toFloatArray(),
                labels = labels,
                constellations = shown.map { CONSTELLATIONS.getValue(it.key) },
                stars = named.map { (s, _) -> NamedStar(s.name!!, s.mag, STAR_LY[s.name]) },
                planets = planets,
                deepSky = deep,
                hasCore = core != null,
                fovAcrossDeg = Math.toDegrees(2 * atan(width / 2.0 / sol.scale)),
                fovUpDeg = Math.toDegrees(2 * atan(height / 2.0 / sol.scale)),
                raDeg = ra,
                decDeg = dec,
                matched = sol.matched,
            )
        }

        /** "1,344 light years", "2.5 million light years". */
        fun lightYears(ly: Double): String = when {
            ly >= 1e6 -> String.format(java.util.Locale.US, "%.1f million light years", ly / 1e6).replace(".0 ", " ")
            ly >= 100 -> String.format(java.util.Locale.US, "%,d light years", Math.round(ly))
            ly >= 10 -> "${Math.round(ly)} light years"
            else -> String.format(java.util.Locale.US, "%.1f light years", ly)
        }

        /** The 88 constellations by their IAU abbreviation. */
        val CONSTELLATIONS: Map<String, String> = mapOf(
            "And" to "Andromeda", "Ant" to "Antlia", "Aps" to "Apus", "Aqr" to "Aquarius", "Aql" to "Aquila",
            "Ara" to "Ara", "Ari" to "Aries", "Aur" to "Auriga", "Boo" to "Boötes", "Cae" to "Caelum",
            "Cam" to "Camelopardalis", "Cnc" to "Cancer", "CVn" to "Canes Venatici", "CMa" to "Canis Major",
            "CMi" to "Canis Minor", "Cap" to "Capricornus", "Car" to "Carina", "Cas" to "Cassiopeia",
            "Cen" to "Centaurus", "Cep" to "Cepheus", "Cet" to "Cetus", "Cha" to "Chamaeleon", "Cir" to "Circinus",
            "Col" to "Columba", "Com" to "Coma Berenices", "CrA" to "Corona Australis", "CrB" to "Corona Borealis",
            "Crv" to "Corvus", "Crt" to "Crater", "Cru" to "Southern Cross", "Cyg" to "Cygnus", "Del" to "Delphinus",
            "Dor" to "Dorado", "Dra" to "Draco", "Equ" to "Equuleus", "Eri" to "Eridanus", "For" to "Fornax",
            "Gem" to "Gemini", "Gru" to "Grus", "Her" to "Hercules", "Hor" to "Horologium", "Hya" to "Hydra",
            "Hyi" to "Hydrus", "Ind" to "Indus", "Lac" to "Lacerta", "Leo" to "Leo", "LMi" to "Leo Minor",
            "Lep" to "Lepus", "Lib" to "Libra", "Lup" to "Lupus", "Lyn" to "Lynx", "Lyr" to "Lyra",
            "Men" to "Mensa", "Mic" to "Microscopium", "Mon" to "Monoceros", "Mus" to "Musca", "Nor" to "Norma",
            "Oct" to "Octans", "Oph" to "Ophiuchus", "Ori" to "Orion", "Pav" to "Pavo", "Peg" to "Pegasus",
            "Per" to "Perseus", "Phe" to "Phoenix", "Pic" to "Pictor", "Psc" to "Pisces", "PsA" to "Piscis Austrinus",
            "Pup" to "Puppis", "Pyx" to "Pyxis", "Ret" to "Reticulum", "Sge" to "Sagitta", "Sgr" to "Sagittarius",
            "Sco" to "Scorpius", "Scl" to "Sculptor", "Sct" to "Scutum", "Ser" to "Serpens", "Sex" to "Sextans",
            "Tau" to "Taurus", "Tel" to "Telescopium", "Tri" to "Triangulum", "TrA" to "Triangulum Australe",
            "Tuc" to "Tucana", "UMa" to "Ursa Major", "UMi" to "Ursa Minor", "Vel" to "Vela", "Vir" to "Virgo",
            "Vol" to "Volans", "Vul" to "Vulpecula",
        )

        /** How far the named stars are (light years, the commonly quoted values). */
        val STAR_LY: Map<String, Double> = mapOf(
            "Sirius" to 8.6, "Canopus" to 310.0, "Alpha Centauri" to 4.4, "Arcturus" to 37.0, "Vega" to 25.0,
            "Capella" to 43.0, "Rigel" to 860.0, "Procyon" to 11.5, "Achernar" to 139.0, "Betelgeuse" to 550.0,
            "Hadar" to 390.0, "Altair" to 16.7, "Acrux" to 320.0, "Aldebaran" to 65.0, "Antares" to 550.0,
            "Spica" to 250.0, "Pollux" to 34.0, "Fomalhaut" to 25.0, "Deneb" to 2600.0, "Mimosa" to 280.0,
            "Regulus" to 79.0, "Adhara" to 430.0, "Castor" to 51.0, "Shaula" to 570.0, "Bellatrix" to 250.0,
            "Elnath" to 134.0, "Alnilam" to 2000.0, "Alnitak" to 1260.0, "Mintaka" to 1200.0, "Saiph" to 650.0,
            "Polaris" to 430.0, "Dubhe" to 123.0, "Merak" to 79.0, "Phecda" to 83.0, "Alioth" to 81.0,
            "Mizar" to 83.0, "Alkaid" to 104.0, "Kochab" to 130.0, "Mirfak" to 510.0, "Algol" to 90.0,
            "Alpheratz" to 97.0, "Mirach" to 200.0, "Hamal" to 66.0, "Schedar" to 230.0, "Caph" to 55.0,
            "Alcyone" to 440.0, "Denebola" to 36.0, "Algieba" to 130.0, "Albireo" to 430.0, "Rasalhague" to 49.0,
            "Eltanin" to 154.0, "Alhena" to 109.0, "Menkalinan" to 81.0, "Kaus Australis" to 143.0,
            "Nunki" to 228.0, "Alnair" to 101.0, "Alphard" to 180.0, "Alphecca" to 75.0, "Markab" to 133.0,
            "Scheat" to 196.0, "Alderamin" to 49.0, "Peacock" to 180.0, "Sadr" to 1800.0,
        )
    }
}
