package com.wm.starcam.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.wm.starcam.sky.GeoLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Where the Tonight planner works it out for: the phone's own coarse location, or a place typed
 * in. Only ever used on the phone; StarCam has no internet access.
 */
data class SkyPlace(val loc: GeoLocation, val manual: Boolean, val atMs: Long)

/** The last place, kept between launches so the planner opens without waiting for a fix. */
class SkyPlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("starcam_sky", Context.MODE_PRIVATE)

    fun load(): SkyPlace? {
        if (!prefs.contains("lat")) return null
        return SkyPlace(
            GeoLocation(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble()),
            manual = prefs.getBoolean("manual", false),
            atMs = prefs.getLong("at", 0L),
        )
    }

    fun save(p: SkyPlace) {
        prefs.edit()
            .putFloat("lat", p.loc.latDeg.toFloat())
            .putFloat("lon", p.loc.lonDeg.toFloat())
            .putBoolean("manual", p.manual)
            .putLong("at", p.atMs)
            .apply()
    }
}

object DeviceLocation {

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A coarse fix (a few kilometres is plenty for the sky), or the newest one the phone already
     * has when no provider answers within [timeoutMs]. Every enabled provider is asked at once
     * and the first answer wins: the fused one can sit silent without network or Wi-Fi scanning
     * while satellite positioning would have answered. Null without permission or with location
     * off and nothing cached.
     */
    suspend fun current(context: Context, timeoutMs: Long = 15_000L): GeoLocation? {
        if (!permitted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val fresh = if (providers.isEmpty()) null else withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val first = CompletableDeferred<Location?>()
                val silent = AtomicInteger(providers.size)
                val asks = providers.map { provider ->
                    launch {
                        val loc = fromProvider(context, lm, provider)
                        if (loc != null) first.complete(loc) else if (silent.decrementAndGet() == 0) first.complete(null)
                    }
                }
                first.await().also { asks.forEach { it.cancel() } }
            }
        }
        val best = fresh ?: providers.mapNotNull { p ->
            try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
        return best?.let { GeoLocation(it.latitude, it.longitude) }
    }

    private suspend fun fromProvider(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
            } catch (e: SecurityException) {
                cont.resume(null)
            } catch (e: IllegalArgumentException) {
                cont.resume(null)  // a provider that went away between the check and the call
            }
        }
}
