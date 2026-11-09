package com.wm.starcam.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.wm.starcam.sky.GeoLocation
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val prefs = context.applicationContext.getSharedPreferences("starcam_sky", Context.MODE_PRIVATE)

    /** The saved place as it changes: a search that lands reaches every open screen at once. */
    val place: StateFlow<SkyPlace?> = shared { load() }

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
        shared { p }.value = p
    }

    private companion object {
        private var live: MutableStateFlow<SkyPlace?>? = null

        @Synchronized
        fun shared(first: () -> SkyPlace?): MutableStateFlow<SkyPlace?> =
            live ?: MutableStateFlow(first()).also { live = it }
    }
}

/** A position the phone already holds, and how old it is. */
internal data class KnownPlace(val loc: GeoLocation, val ageMs: Long)

/** A position this recent is as good as a new one: a few kilometres is plenty for the sky. */
internal const val FRESH_PLACE_MS = 30 * 60_000L

/** A position this recent is used at once, and replaced if the phone comes up with a newer one. */
internal const val USABLE_PLACE_MS = 6 * 3_600_000L

/**
 * One search, Android left out. [found] is told every position worth showing, [searching] only
 * when there is nothing to show until the answer comes. False: no position at all.
 */
internal suspend fun searchPlace(
    known: KnownPlace?,
    ask: suspend () -> GeoLocation?,
    found: (GeoLocation) -> Unit,
    searching: () -> Unit,
): Boolean {
    val usable = known?.takeIf { it.ageMs <= USABLE_PLACE_MS }
    if (usable != null) found(usable.loc)
    if (usable != null && usable.ageMs <= FRESH_PLACE_MS) return true
    if (usable == null) searching()
    val answer = ask()
    if (answer != null) found(answer) else if (known != null && usable == null) found(known.loc)
    return answer != null || known != null
}

/** How the one search for the phone's place is going. */
enum class PlaceSearch { Idle, Finding, NotFound }

/**
 * The one search for the phone's place, for the whole app. It belongs to no screen: leaving the
 * screen that started it used to end it, and the next screen knew nothing of it.
 */
object PlaceFinder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow(PlaceSearch.Idle)
    private var running: Job? = null

    val status: StateFlow<PlaceSearch> = _status.asStateFlow()

    /** Starts the search, or leaves the one already going. */
    fun find(context: Context) {
        if (running?.isActive == true) return
        val app = context.applicationContext
        running = scope.launch {
            val store = SkyPlaceStore(app)
            val before = store.load()
            val t0 = SystemClock.elapsedRealtime()
            val known = DeviceLocation.known(app)
            val ok = runCatching {
                searchPlace(
                    known,
                    ask = { DeviceLocation.fresh(app) },
                    found = { loc ->
                        // A place typed in while this ran is the one wanted.
                        val now = store.load()
                        if (now == before || now?.manual != true) {
                            store.save(SkyPlace(loc, manual = false, atMs = System.currentTimeMillis()))
                        }
                        _status.value = PlaceSearch.Idle
                    },
                    searching = { _status.value = PlaceSearch.Finding },
                )
            }.onFailure { AppLog.e("place", "the search failed", it) }.getOrDefault(false)
            _status.value = if (ok) PlaceSearch.Idle else PlaceSearch.NotFound
            AppLog.i("place", "search done in ${SystemClock.elapsedRealtime() - t0} ms: found=$ok, " +
                "the phone's own position ${known?.let { "${it.ageMs / 60_000L} min old" } ?: "none"}")
        }
    }

    /** The location permission was refused. */
    fun notAllowed() {
        _status.value = PlaceSearch.NotFound
    }
}

object DeviceLocation {

    private const val SEARCH_MS = 25_000L

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The newest position the phone already holds, from any app's fix. This comes first because a
     * new one may never come: with approximate location Android turns a request into a low-power
     * one, every ten minutes at best, and on a OnePlus 15 indoors no provider answered in 20 s.
     */
    internal fun known(context: Context): KnownPlace? {
        if (!permitted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val newest = listOf(
            LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { p ->
            try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null  // a provider this phone does not have
            }
        }.maxByOrNull { it.elapsedRealtimeNanos } ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - newest.elapsedRealtimeNanos) / 1_000_000L
        return KnownPlace(GeoLocation(newest.latitude, newest.longitude), ageMs)
    }

    /**
     * A new coarse fix, or null when no provider answers in time. Every enabled provider is asked
     * at once and the first answer wins: the fused one can sit silent without network or Wi-Fi
     * scanning while satellite positioning would have answered.
     */
    internal suspend fun fresh(context: Context): GeoLocation? {
        if (!permitted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        val fix = withTimeoutOrNull(SEARCH_MS) {
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
        return fix?.let { GeoLocation(it.latitude, it.longitude) }
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
