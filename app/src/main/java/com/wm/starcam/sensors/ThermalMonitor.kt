package com.wm.starcam.sensors

import android.content.Context
import android.os.PowerManager
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thermal status watcher: HUD indicator + automatic inter-frame cooldown (PLAN.md §3.3). */
class ThermalMonitor(context: Context) {

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _status = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    val status: StateFlow<Int> = _status

    private val listener = PowerManager.OnThermalStatusChangedListener { s ->
        if (s != _status.value) {
            val msg = "thermal status ${Diagnostics.thermalName(_status.value)} -> ${Diagnostics.thermalName(s)}"
            if (s >= PowerManager.THERMAL_STATUS_SEVERE) AppLog.w("thermal", msg) else AppLog.i("thermal", msg)
        }
        _status.value = s
    }

    fun start() {
        runCatching {
            _status.value = power.currentThermalStatus
            power.addThermalStatusListener(listener)
        }
    }

    fun stop() {
        runCatching { power.removeThermalStatusListener(listener) }
    }

    val needsCooldown: Boolean
        get() = _status.value >= PowerManager.THERMAL_STATUS_SEVERE

    /** The system is about to throttle hard or shut apps down. */
    val isCritical: Boolean
        get() = _status.value >= PowerManager.THERMAL_STATUS_CRITICAL

    fun label(): String = when (_status.value) {
        PowerManager.THERMAL_STATUS_NONE -> "cool"
        PowerManager.THERMAL_STATUS_LIGHT -> "warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "warm+"
        PowerManager.THERMAL_STATUS_SEVERE -> "hot"
        else -> "throttling"
    }
}
