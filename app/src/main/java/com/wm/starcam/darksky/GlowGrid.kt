package com.wm.starcam.darksky

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.Inflater
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The world's artificial sky brightness (assets/darksky/glow.bin, made by
 * tools/darksky/Skyglow.java): the Duriscoe et al. 2018 all-sky model over the 2025 VIIRS night
 * lights, at 1/30 deg (about 3.7 km) from 75 N to 60 S, in 5 deg tiles that are inflated only
 * when something reads them.
 *
 * A cell's value is 0 for water and 1..255 on land: log10 of the all-sky artificial luminance
 * ratio (ALR, artificial light over 250 ucd/m2) in steps of 1/40 decade from 10^-2.5.
 */
class GlowGrid(private val file: ByteArray) {
    val cellsPerDeg: Int
    val latMax: Double
    val latMin: Double
    private val tile: Int
    private val tilesX: Int
    private val tilesY: Int
    private val qLogMin: Double
    private val qPerDecade: Double
    private val offsets: IntArray
    private val lengths: IntArray
    private val dataStart: Int
    private val cache = object : LinkedHashMap<Int, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > MAX_TILES
    }

    init {
        val d = DataInputStream(ByteArrayInputStream(file))
        val magic = ByteArray(4).also { d.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == "SCGL") { "not a glow grid" }
        require(d.readInt() == 1) { "unknown glow grid version" }
        cellsPerDeg = d.readInt()
        latMax = d.readFloat().toDouble()
        latMin = d.readFloat().toDouble()
        tile = d.readInt()
        tilesX = d.readInt()
        tilesY = d.readInt()
        qLogMin = d.readFloat().toDouble()
        qPerDecade = d.readFloat().toDouble()
        offsets = IntArray(tilesX * tilesY)
        lengths = IntArray(tilesX * tilesY)
        for (k in 0 until tilesX * tilesY) {
            offsets[k] = d.readInt()
            lengths[k] = d.readInt()
        }
        dataStart = 4 + 4 * 9 + 8 * tilesX * tilesY
    }

    val width: Int get() = 360 * cellsPerDeg
    val height: Int get() = ((latMax - latMin) * cellsPerDeg).toInt()

    /** Cell row and column of a place; row is null outside the map's latitudes. */
    fun row(lat: Double): Int? = floor((latMax - lat) * cellsPerDeg).toInt().takeIf { it in 0 until height }
    fun col(lon: Double): Int = Math.floorMod(floor((lon + 180.0) * cellsPerDeg).toInt(), width)

    /** Centre of a cell. */
    fun cellLat(row: Int): Double = latMax - (row + 0.5) / cellsPerDeg
    fun cellLon(col: Int): Double = -180.0 + (col + 0.5) / cellsPerDeg

    /** 0 water, 1..255 land; 0 too outside the map. */
    @Synchronized
    fun value(row: Int, col: Int): Int {
        if (row !in 0 until height) return 0
        val c = Math.floorMod(col, width)
        val t = (row / tile) * tilesX + c / tile
        if (lengths[t] == 0) return 0
        val cells = cache[t] ?: inflate(t).also { cache[t] = it }
        return cells[(row % tile) * tile + (c % tile)].toInt() and 0xff
    }

    fun value(lat: Double, lon: Double): Int = row(lat)?.let { value(it, col(lon)) } ?: 0

    /** All-sky artificial luminance ratio of a land value (0 for water). */
    fun alr(value: Int): Double = if (value <= 0) 0.0 else 10.0.pow((value - 1) / qPerDecade + qLogMin)

    fun alr(lat: Double, lon: Double): Double = alr(value(lat, lon))

    private fun inflate(t: Int): ByteArray {
        val inf = Inflater()
        val cells = ByteArray(tile * tile)
        try {
            inf.setInput(file, dataStart + offsets[t], lengths[t])
            inf.inflate(cells)
        } finally {
            inf.end()
        }
        // The cells are stored as running differences, row by row.
        var prev = 0
        for (i in cells.indices) {
            prev = (prev + cells[i]) and 0xff
            cells[i] = prev.toByte()
        }
        return cells
    }

    companion object {
        private const val MAX_TILES = 48

        /** The value a given ALR is stored as (for tests and the legend). */
        fun valueOf(alr: Double, qLogMin: Double = -2.5, qPerDecade: Double = 40.0): Int =
            if (alr <= 0) 1 else (1 + qPerDecade * (log10(alr) - qLogMin)).toInt().coerceIn(1, 255)
    }
}
