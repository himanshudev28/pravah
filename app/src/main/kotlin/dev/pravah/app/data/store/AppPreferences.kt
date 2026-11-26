package dev.pravah.app.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

class AppPreferences(private val context: Context) {

    private object Keys {
        val ALARM_PERCENT       = intPreferencesKey("alarm_percent")
        val ALARM_ENABLED       = booleanPreferencesKey("alarm_enabled")
        val USE_FAHRENHEIT      = booleanPreferencesKey("use_fahrenheit")
        val USE_MILLIAMPS       = booleanPreferencesKey("use_milliamps")
        val AMOLED_THEME        = booleanPreferencesKey("amoled_theme")
        val NOTIFICATION_LIVE   = booleanPreferencesKey("notification_live")
        val DESIGN_CAPACITY_MAH = floatPreferencesKey("design_capacity_mah")  // 0 = not set
        val CAPACITY_EST_MAH    = floatPreferencesKey("capacity_est_mah")
        val CAPACITY_EST_COUNT  = intPreferencesKey("capacity_est_count")
        // Calibration profile
        val CALIB_UNIT_DIVISOR  = longPreferencesKey("calib_unit_divisor")
        val CALIB_SIGN_MULT     = intPreferencesKey("calib_sign_mult")
        val CALIB_CONFIDENCE    = intPreferencesKey("calib_confidence")  // 0=UNKNOWN,1=LOW,2=HIGH
        val THEME_MODE          = intPreferencesKey("theme_mode")        // 0=system, 1=light, 2=dark
        val CURRENT_MICRO       = booleanPreferencesKey("current_unit_micro")
        val CURRENT_INVERTED    = booleanPreferencesKey("current_sign_inverted")
        val OPEN_SESSION        = stringPreferencesKey("open_session")
        val CHARGE_LIMIT_MANUAL = intPreferencesKey("charge_limit_manual")  // 0 = auto (follow the phone)
        val CHARGE_LIMIT_LEARNED = intPreferencesKey("charge_limit_learned") // 0 = none seen
    }

    val alarmPercent: Flow<Int> = context.dataStore.data.map { it[Keys.ALARM_PERCENT] ?: 80 }
    val alarmEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALARM_ENABLED] ?: false }
    val useFahrenheit: Flow<Boolean> = context.dataStore.data.map { it[Keys.USE_FAHRENHEIT] ?: false }
    val useMilliamps: Flow<Boolean> = context.dataStore.data.map { it[Keys.USE_MILLIAMPS] ?: false }
    val amoledTheme: Flow<Boolean> = context.dataStore.data.map { it[Keys.AMOLED_THEME] ?: false }
    val notificationLive: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIFICATION_LIVE] ?: true }
    val designCapacityMah: Flow<Float> = context.dataStore.data.map { it[Keys.DESIGN_CAPACITY_MAH] ?: 0f }
    val capacityEstMah: Flow<Float> = context.dataStore.data.map { it[Keys.CAPACITY_EST_MAH] ?: 0f }
    val capacityEstCount: Flow<Int> = context.dataStore.data.map { it[Keys.CAPACITY_EST_COUNT] ?: 0 }

    // Calibration profile stored as three flat fields
    val calibUnitDivisor: Flow<Long> = context.dataStore.data.map { it[Keys.CALIB_UNIT_DIVISOR] ?: 1L }
    val calibSignMult: Flow<Int> = context.dataStore.data.map { it[Keys.CALIB_SIGN_MULT] ?: 1 }
    val calibConfidence: Flow<Int> = context.dataStore.data.map { it[Keys.CALIB_CONFIDENCE] ?: 0 }
    val currentUnitMicro: Flow<Boolean> = context.dataStore.data.map { it[Keys.CURRENT_MICRO] ?: false }
    /** Null until learned at least once — lets sign learning start neutral on first run. */
    val currentSignInverted: Flow<Boolean?> = context.dataStore.data.map { it[Keys.CURRENT_INVERTED] }

    /** 0 = Auto (follow the phone's battery protection), otherwise a fixed limit 50–100. */
    val chargeLimitManual: Flow<Int> = context.dataStore.data.map { it[Keys.CHARGE_LIMIT_MANUAL] ?: 0 }
    suspend fun setChargeLimitManual(v: Int) = context.dataStore.edit { it[Keys.CHARGE_LIMIT_MANUAL] = v }
    /** Limit observed on phones without a readable protection setting; 0 = none. */
    val chargeLimitLearned: Flow<Int> = context.dataStore.data.map { it[Keys.CHARGE_LIMIT_LEARNED] ?: 0 }
    suspend fun setChargeLimitLearned(v: Int) = context.dataStore.edit { it[Keys.CHARGE_LIMIT_LEARNED] = v }

    /**
     * In-progress charge session, survives process death:
     * "startMillis|startPercent|startCounterUah|plugType" plus, while waiting to see whether the
     * cable comes back, "|unplugMillis|unplugPercent|unplugCounterUah".
     */
    val openSession: Flow<String?> = context.dataStore.data.map { it[Keys.OPEN_SESSION] }
    suspend fun setOpenSession(v: String?) = context.dataStore.edit {
        if (v == null) it.remove(Keys.OPEN_SESSION) else it[Keys.OPEN_SESSION] = v
    }
    suspend fun saveCurrentNormalisation(micro: Boolean, inverted: Boolean) = context.dataStore.edit {
        it[Keys.CURRENT_MICRO] = micro
        it[Keys.CURRENT_INVERTED] = inverted
    }

    val themeMode: Flow<Int> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: 0 }

    suspend fun setAlarmPercent(value: Int) = context.dataStore.edit { it[Keys.ALARM_PERCENT] = value }
    suspend fun setAlarmEnabled(value: Boolean) = context.dataStore.edit { it[Keys.ALARM_ENABLED] = value }
    suspend fun setUseFahrenheit(v: Boolean) = context.dataStore.edit { it[Keys.USE_FAHRENHEIT] = v }
    suspend fun setUseMilliamps(v: Boolean) = context.dataStore.edit { it[Keys.USE_MILLIAMPS] = v }
    suspend fun setAmoledTheme(v: Boolean) = context.dataStore.edit { it[Keys.AMOLED_THEME] = v }
    suspend fun setNotificationLive(v: Boolean) = context.dataStore.edit { it[Keys.NOTIFICATION_LIVE] = v }
    suspend fun setDesignCapacityMah(v: Float) = context.dataStore.edit { it[Keys.DESIGN_CAPACITY_MAH] = v }

    suspend fun saveCapacityEstimate(mah: Float, count: Int) = context.dataStore.edit {
        it[Keys.CAPACITY_EST_MAH] = mah
        it[Keys.CAPACITY_EST_COUNT] = count
    }

    suspend fun setThemeMode(v: Int) = context.dataStore.edit { it[Keys.THEME_MODE] = v }

    suspend fun saveCalibration(unitDivisor: Long, signMult: Int, confidence: Int) = context.dataStore.edit {
        it[Keys.CALIB_UNIT_DIVISOR] = unitDivisor
        it[Keys.CALIB_SIGN_MULT] = signMult
        it[Keys.CALIB_CONFIDENCE] = confidence
    }

    /** Clears learned data only; user settings are kept (as the reset dialog promises). */
    suspend fun resetAll() = context.dataStore.edit {
        it.remove(Keys.CAPACITY_EST_MAH); it.remove(Keys.CAPACITY_EST_COUNT)
        it.remove(Keys.CALIB_UNIT_DIVISOR); it.remove(Keys.CALIB_SIGN_MULT); it.remove(Keys.CALIB_CONFIDENCE)
        it.remove(Keys.CURRENT_MICRO); it.remove(Keys.CURRENT_INVERTED)
        it.remove(Keys.OPEN_SESSION)
        it.remove(Keys.CHARGE_LIMIT_LEARNED)
    }
}
