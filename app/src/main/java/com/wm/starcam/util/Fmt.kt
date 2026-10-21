package com.wm.starcam.util

import java.util.Locale

object Fmt {
    /** 8000000000 -> "8s", 500000000 -> "0.5s", 8130 -> "1/123s" style. */
    fun exposure(ns: Long): String {
        val s = ns / 1e9
        return when {
            s >= 1.0 -> if (s == s.toLong().toDouble()) "${s.toLong()}s"
            else String.format(Locale.US, "%.1fs", s)
            s >= 0.1 -> String.format(Locale.US, "%.1fs", s)
            s > 0 -> "1/${(1.0 / s).toInt()}s"
            else -> "0s"
        }
    }

    /** Fixed-width m:ss for live clocks, so ticking digits do not shift the layout. */
    fun clock(totalSec: Int): String {
        val t = if (totalSec < 0) 0 else totalSec
        return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
    }

    fun seconds(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
