package dev.pravah.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pravah.app.data.BatteryTelemetry
import dev.pravah.app.data.CurrentPoint
import dev.pravah.app.data.db.ChargeSession
import dev.pravah.app.data.repository.BatteryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val telemetry: BatteryTelemetry? = null,
    /** Last ~10 minutes of signed current, oldest first. */
    val currentHistory: List<CurrentPoint> = emptyList(),
    val sessions: List<ChargeSession> = emptyList(),
    /** Learned full-charge capacity from completed sessions, 0 = not enough data. */
    val estimatedCapacityMah: Float = 0f,
    /** User-entered design capacity, 0 = not set (fall back to telemetry.designCapacityMah). */
    val designCapacityMah: Float = 0f,
    val capacitySessionCount: Int = 0,
    val alarmPercent: Int = 80,
    val alarmEnabled: Boolean = false,
    val useFahrenheit: Boolean = false,
    val amoledTheme: Boolean = false,
    val notificationLive: Boolean = true,
    val themeMode: Int = 0,       // 0=system, 1=light, 2=dark
    /** 0 = Auto (follow the phone's battery protection), else a fixed limit. */
    val chargeLimitManual: Int = 0,
    /** What the phone's battery protection currently enforces (100 = none); null if unreadable. */
    val systemChargeLimit: Int? = null,
) {
    /** Design capacity: user override wins, else auto-detected. */
    val effectiveDesignMah: Double?
        get() = designCapacityMah.takeIf { it > 0 }?.toDouble() ?: telemetry?.designCapacityMah

    val healthPercent: Float? get() {
        val est = estimatedCapacityMah.takeIf { it > 0 } ?: return null
        val design = effectiveDesignMah?.toFloat() ?: return null
        return (est / design * 100f).coerceIn(0f, 100f)
    }
}

class BatteryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BatteryRepository.get(app)

    private val live = combine(repo.telemetry, repo.currentHistory) { t, h -> t to h }

    private val stored = combine(
        repo.sessions, repo.capacityEstMah, repo.designCapacityMah, repo.capacityEstCount,
    ) { s, est, design, count -> StoredData(s, est, design, count) }

    private val prefs = combine(
        repo.alarmPercent, repo.alarmEnabled, repo.useFahrenheit, repo.amoledTheme,
        combine(repo.notificationLive, repo.themeMode, repo.chargeLimitManual, repo.systemChargeLimit) { a, b, c, d -> Extra(a, b, c, d) },
    ) { pct, en, f, amoled, x -> Prefs(pct, en, f, amoled, x.live, x.theme, x.manual, x.system) }

    val uiState: StateFlow<UiState> = combine(live, stored, prefs) { (t, h), s, p ->
        UiState(
            telemetry = t, currentHistory = h,
            sessions = s.sessions, estimatedCapacityMah = s.estMah,
            designCapacityMah = s.designMah, capacitySessionCount = s.count,
            alarmPercent = p.alarmPercent, alarmEnabled = p.alarmEnabled,
            useFahrenheit = p.useFahrenheit, amoledTheme = p.amoled,
            notificationLive = p.notificationLive, themeMode = p.themeMode,
            chargeLimitManual = p.chargeLimitManual, systemChargeLimit = p.systemChargeLimit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Same as [uiState] minus the 1 Hz live fields — for screens that don't show live data. */
    val settledState: StateFlow<UiState> = uiState
        .map { it.copy(telemetry = null, currentHistory = emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Call from Activity onStart/onStop — the sampler runs at 1 Hz only while the UI is visible. */
    fun setUiVisible(visible: Boolean) = repo.setUiVisible(visible)

    fun setAlarmPercent(v: Int)          = viewModelScope.launch { repo.setAlarmPercent(v) }
    fun setAlarmEnabled(v: Boolean)      = viewModelScope.launch { repo.setAlarmEnabled(v) }
    fun setUseFahrenheit(v: Boolean)     = viewModelScope.launch { repo.setUseFahrenheit(v) }
    fun setAmoledTheme(v: Boolean)       = viewModelScope.launch { repo.setAmoledTheme(v) }
    fun setNotificationLive(v: Boolean)  = viewModelScope.launch { repo.setNotificationLive(v) }
    fun setDesignCapacity(mah: Float)    = viewModelScope.launch { repo.setDesignCapacityMah(mah) }
    fun resetData()                      = viewModelScope.launch { repo.resetAllData() }
    fun setThemeMode(v: Int)             = viewModelScope.launch { repo.setThemeMode(v) }
    fun setChargeLimitManual(v: Int)     = viewModelScope.launch { repo.setChargeLimitManual(v) }
}

private data class StoredData(val sessions: List<ChargeSession>, val estMah: Float, val designMah: Float, val count: Int)
private data class Prefs(
    val alarmPercent: Int, val alarmEnabled: Boolean, val useFahrenheit: Boolean,
    val amoled: Boolean, val notificationLive: Boolean, val themeMode: Int, val chargeLimitManual: Int,
    val systemChargeLimit: Int?,
)
private data class Extra(val live: Boolean, val theme: Int, val manual: Int, val system: Int?)
