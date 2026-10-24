package com.wm.starcam.sky

/**
 * A nebula, cluster or galaxy a phone's long exposure can show: J2000 place (deg), how big it
 * looks (deg across) and how far its light has come (light years; null for a chance grouping).
 */
data class DeepSkyObject(
    val name: String,
    /** Its catalogue number, when it is better known by that. */
    val id: String?,
    val kind: String,
    val raDeg: Double,
    val decDeg: Double,
    val sizeDeg: Double,
    val distanceLy: Double?,
)

/**
 * The deep-sky objects worth a label on a wide-field photo: the bright ones, the big ones, and
 * the famous ones of both hemispheres. Places from SIMBAD, distances the commonly quoted values.
 */
object DeepSky {
    val ALL: List<DeepSkyObject> = listOf(
        DeepSkyObject("Andromeda Galaxy", "M31", "galaxy", 10.6846, 41.2692, 3.0, 2_500_000.0),
        DeepSkyObject("Triangulum Galaxy", "M33", "galaxy", 23.4621, 30.6603, 1.0, 2_730_000.0),
        DeepSkyObject("Bode's Galaxy", "M81", "galaxy", 148.8882, 69.0653, 0.4, 11_800_000.0),
        DeepSkyObject("Large Magellanic Cloud", null, "galaxy", 80.894, -69.756, 10.0, 160_000.0),
        DeepSkyObject("Small Magellanic Cloud", null, "galaxy", 13.187, -72.829, 5.0, 200_000.0),
        DeepSkyObject("Orion Nebula", "M42", "nebula", 83.8221, -5.3911, 1.1, 1_344.0),
        DeepSkyObject("Lagoon Nebula", "M8", "nebula", 270.904, -24.387, 1.5, 4_100.0),
        DeepSkyObject("Trifid Nebula", "M20", "nebula", 270.596, -23.030, 0.5, 4_100.0),
        DeepSkyObject("Omega Nebula", "M17", "nebula", 275.108, -16.177, 0.6, 5_500.0),
        DeepSkyObject("Eagle Nebula", "M16", "nebula", 274.700, -13.817, 0.6, 7_000.0),
        DeepSkyObject("Carina Nebula", "NGC 3372", "nebula", 161.2654, -59.8678, 2.0, 8_500.0),
        DeepSkyObject("North America Nebula", "NGC 7000", "nebula", 314.821, 44.529, 2.0, 2_600.0),
        DeepSkyObject("Rho Ophiuchi clouds", null, "nebula", 246.396, -23.447, 4.0, 460.0),
        DeepSkyObject("Coalsack", null, "dark nebula", 192.5, -62.5, 6.0, 600.0),
        DeepSkyObject("Sagittarius Star Cloud", "M24", "star cloud", 274.225, -18.483, 1.5, 10_000.0),
        DeepSkyObject("Pleiades", "M45", "star cluster", 56.85, 24.1167, 1.8, 444.0),
        DeepSkyObject("Hyades", null, "star cluster", 66.725, 15.867, 5.5, 153.0),
        DeepSkyObject("Beehive Cluster", "M44", "star cluster", 130.10, 19.683, 1.6, 577.0),
        DeepSkyObject("Double Cluster", "NGC 869/884", "star cluster", 35.16, 57.13, 1.0, 7_500.0),
        DeepSkyObject("Ptolemy Cluster", "M7", "star cluster", 268.4633, -34.7928, 1.3, 980.0),
        DeepSkyObject("Butterfly Cluster", "M6", "star cluster", 265.083, -32.253, 0.4, 1_600.0),
        DeepSkyObject("Jewel Box", "NGC 4755", "star cluster", 193.4, -60.367, 0.17, 6_400.0),
        DeepSkyObject("Southern Pleiades", "IC 2602", "star cluster", 160.742, -64.40, 1.0, 480.0),
        DeepSkyObject("Wishing Well Cluster", "NGC 3532", "star cluster", 166.4125, -58.7533, 0.9, 1_300.0),
        DeepSkyObject("M35", null, "star cluster", 92.225, 24.333, 0.47, 2_800.0),
        DeepSkyObject("M41", null, "star cluster", 101.50, -20.733, 0.6, 2_300.0),
        DeepSkyObject("Coathanger", null, "asterism", 291.35, 20.183, 1.0, null),
        DeepSkyObject("Hercules Cluster", "M13", "globular cluster", 250.4218, 36.4599, 0.33, 22_200.0),
        DeepSkyObject("M22", null, "globular cluster", 279.0998, -23.9048, 0.5, 10_600.0),
        DeepSkyObject("M4", null, "globular cluster", 245.8968, -26.5258, 0.6, 7_200.0),
        DeepSkyObject("Omega Centauri", "NGC 5139", "globular cluster", 201.697, -47.4795, 0.6, 17_000.0),
        DeepSkyObject("47 Tucanae", "NGC 104", "globular cluster", 6.0236, -72.0813, 0.5, 15_000.0),
    )

    /** The centre of the Milky Way (Sagittarius A*), J2000, and its distance. */
    const val CORE_RA = 266.4168
    const val CORE_DEC = -29.0078
    const val CORE_LY = 26_000.0
}
