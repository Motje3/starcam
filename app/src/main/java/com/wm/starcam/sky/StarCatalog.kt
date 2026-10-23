package com.wm.starcam.sky

import android.content.Context

/** A catalogue star: J2000 place (deg), V magnitude, B-V colour (NaN unknown), Bayer designation. */
data class Star(
    val raDeg: Double,
    val decDeg: Double,
    val mag: Float,
    val bv: Float,
    val designation: String?,
) {
    /** The star's proper name, for the ones people know. */
    val name: String? get() = designation?.let { StarCatalog.NAMES[it] }
}

/**
 * The sky view's stars: the Yale Bright Star Catalogue down to V 5 (assets/sky/stars.txt, made by
 * tools/make-star-table.ps1), the names of the brightest, and stick figures for the
 * constellations a night photographer steers by - the Sagittarius teapot beside the Milky Way's
 * core above all.
 */
class StarCatalog(val stars: List<Star>) {

    private val byDesignation: Map<String, Int> =
        stars.withIndex().filter { it.value.designation != null }.associate { it.value.designation!! to it.index }

    /** Star indices of the figures' segments, two per segment; segments with a missing star left out. */
    val lines: IntArray = buildList {
        for ((con, spec) in FIGURES) {
            for (seg in spec.split(' ')) {
                val ends = seg.split('-')
                if (ends.size != 2) continue
                val a = byDesignation[designation(ends[0], con)] ?: continue
                val b = byDesignation[designation(ends[1], con)] ?: continue
                add(a)
                add(b)
            }
        }
    }.toIntArray()

    /** Segments [FIGURES] names that the catalogue does not have (a test keeps this empty). */
    internal fun missingFigureStars(): List<String> = FIGURES.flatMap { (con, spec) ->
        spec.split(' ').flatMap { it.split('-') }.map { designation(it, con) }.filter { it !in byDesignation }
    }

    companion object {
        @Volatile private var cached: StarCatalog? = null

        /** The catalogue from the app's assets, read once. */
        fun load(context: Context): StarCatalog = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open("sky/stars.txt").bufferedReader().use { it.readText() })
                .also { cached = it }
        }

        fun parse(text: String): StarCatalog = StarCatalog(
            text.lineSequence().mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val f = line.trim().split(' ')
                if (f.size < 5) return@mapNotNull null
                Star(
                    raDeg = f[1].toDoubleOrNull() ?: return@mapNotNull null,
                    decDeg = f[2].toDoubleOrNull() ?: return@mapNotNull null,
                    mag = f[3].toFloatOrNull() ?: return@mapNotNull null,
                    bv = f[4].toFloatOrNull() ?: Float.NaN,
                    designation = if (f.size >= 7) "${f[5]} ${f[6]}" else null,
                )
            }.toList(),
        )

        /** "Alp" in Orion is "Alp Ori"; "Bet.Tau" names another constellation's star. */
        private fun designation(token: String, con: String): String =
            if ('.' in token) token.replace('.', ' ') else "$token $con"

        /** Proper names by Bayer designation (IAU names), for the stars worth a label. */
        val NAMES: Map<String, String> = mapOf(
            "Alp CMa" to "Sirius", "Alp Car" to "Canopus", "Alp1 Cen" to "Alpha Centauri",
            "Alp Boo" to "Arcturus", "Alp Lyr" to "Vega", "Alp Aur" to "Capella", "Bet Ori" to "Rigel",
            "Alp CMi" to "Procyon", "Alp Eri" to "Achernar", "Alp Ori" to "Betelgeuse", "Bet Cen" to "Hadar",
            "Alp Aql" to "Altair", "Alp1 Cru" to "Acrux", "Alp Tau" to "Aldebaran", "Alp Sco" to "Antares",
            "Alp Vir" to "Spica", "Bet Gem" to "Pollux", "Alp PsA" to "Fomalhaut", "Alp Cyg" to "Deneb",
            "Bet Cru" to "Mimosa", "Alp Leo" to "Regulus", "Eps CMa" to "Adhara", "Alp Gem" to "Castor",
            "Lam Sco" to "Shaula", "Gam Cru" to "Gacrux", "Gam Ori" to "Bellatrix", "Bet Tau" to "Elnath",
            "Bet Car" to "Miaplacidus", "Eps Ori" to "Alnilam", "Alp Gru" to "Alnair", "Zet Ori" to "Alnitak",
            "Eps UMa" to "Alioth", "Alp UMa" to "Dubhe", "Alp Per" to "Mirfak", "Del CMa" to "Wezen",
            "The Sco" to "Sargas", "Eps Sgr" to "Kaus Australis", "Eps Car" to "Avior", "Eta UMa" to "Alkaid",
            "Bet Aur" to "Menkalinan", "Alp TrA" to "Atria", "Gam Gem" to "Alhena", "Alp Pav" to "Peacock",
            "Bet CMa" to "Mirzam", "Alp Hya" to "Alphard", "Alp UMi" to "Polaris", "Alp Ari" to "Hamal",
            "Gam1 Leo" to "Algieba", "Bet Cet" to "Diphda", "Sig Sgr" to "Nunki", "Bet And" to "Mirach",
            "Alp And" to "Alpheratz", "Alp Oph" to "Rasalhague", "Bet UMi" to "Kochab", "Kap Ori" to "Saiph",
            "Bet Leo" to "Denebola", "Bet Per" to "Algol", "Del Ori" to "Mintaka", "Bet Cas" to "Caph",
            "Alp Cas" to "Schedar", "Gam Cyg" to "Sadr", "Bet1 Cyg" to "Albireo", "Zet UMa" to "Mizar",
            "Bet UMa" to "Merak", "Gam UMa" to "Phecda", "Eta Tau" to "Alcyone", "Gam Dra" to "Eltanin",
            "Eps Boo" to "Izar", "Alp CrB" to "Alphecca", "Del Sco" to "Dschubba", "Bet1 Sco" to "Acrab",
            "Gam2 Sgr" to "Alnasl", "Del Sgr" to "Kaus Media", "Lam Sgr" to "Kaus Borealis",
            "Zet Sgr" to "Ascella", "Bet Peg" to "Scheat", "Alp Peg" to "Markab", "Gam Peg" to "Algenib",
            "Eps Peg" to "Enif", "Gam Aql" to "Tarazed", "Alp Cep" to "Alderamin", "Bet Lyr" to "Sheliak",
            "Gam Lyr" to "Sulafat", "Eps Cyg" to "Aljanah", "Gam Crv" to "Gienah", "Alp Lep" to "Arneb",
            "Gam2 Vel" to "Regor", "Lam Vel" to "Suhail", "Alp2 Lib" to "Zubenelgenubi",
            "Bet Lib" to "Zubeneschamali", "Eta Oph" to "Sabik", "Alp Col" to "Phact",
        )

        /**
         * Stick figures, per constellation: segments "A-B" of Bayer letters in that constellation;
         * "Bet.Tau" is a star of another one.
         */
        val FIGURES: Map<String, String> = mapOf(
            "Ori" to "Lam-Alp Lam-Gam Alp-Zet Gam-Del Zet-Eps Eps-Del Zet-Kap Del-Bet",
            "UMa" to "Eta-Zet Zet-Eps Eps-Del Del-Alp Alp-Bet Bet-Gam Gam-Del",
            "UMi" to "Alp-Del Del-Eps Eps-Zet Zet-Bet Bet-Gam Gam-Eta Eta-Zet",
            "Cas" to "Eps-Del Del-Gam Gam-Alp Alp-Bet",
            "Cyg" to "Alp-Gam Gam-Eta Eta-Bet1 Del-Gam Gam-Eps",
            "Lyr" to "Alp-Zet1 Zet1-Bet Bet-Gam Gam-Del2 Del2-Zet1",
            "Aql" to "Gam-Alp Alp-Bet Alp-Del Del-Lam",
            "Sco" to "Bet1-Del Del-Pi Del-Sig Sig-Alp Alp-Tau Tau-Eps Eps-Mu1 Mu1-Zet2 Zet2-Eta " +
                "Eta-The The-Iot1 Iot1-Kap Kap-Lam Lam-Ups",
            "Sgr" to "Gam2-Del Gam2-Eps Del-Eps Del-Lam Lam-Phi Del-Phi Phi-Sig Sig-Tau Tau-Zet Zet-Phi Zet-Eps",
            "Cru" to "Alp1-Gam Bet-Del",
            "Cen" to "Alp1-Bet",
            "TrA" to "Alp-Bet Bet-Gam Gam-Alp",
            "Leo" to "Alp-Eta Eta-Gam1 Gam1-Zet Zet-Mu Mu-Eps Gam1-Del Del-Bet Bet-The The-Alp",
            "Gem" to "Alp-Bet Alp-Eps Eps-Mu Bet-Del Del-Gam",
            "Tau" to "Alp-Zet Gam-Alp Gam-Eps Eps-Bet",
            "Aur" to "Alp-Bet Bet-The The-Bet.Tau Bet.Tau-Iot Iot-Alp",
            "Peg" to "Alp-Bet Bet-Alp.And Alp.And-Gam Gam-Alp",
            "And" to "Alp-Del Del-Bet Bet-Gam1",
            "CMa" to "Bet-Alp Alp-Del Del-Eps Del-Eta",
            "Boo" to "Alp-Eps Eps-Del Del-Bet Bet-Gam Gam-Rho Rho-Alp",
            "Per" to "Gam-Alp Alp-Del Del-Eps Eps-Zet Alp-Bet",
            "CrB" to "The-Bet Bet-Alp Alp-Gam Gam-Del Del-Eps",
            "Del" to "Alp-Bet Bet-Del Del-Gam2 Gam2-Alp Bet-Eps",
        )
    }
}
