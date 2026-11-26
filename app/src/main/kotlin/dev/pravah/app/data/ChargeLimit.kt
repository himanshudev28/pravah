package dev.pravah.app.data

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Where charging will stop. The ETA counts down to this, not to 100 %.
 *
 * Sources, in priority order:
 *  1. MANUAL — the user picked a limit in Pravah's settings.
 *  2. SYSTEM — the phone's own battery protection (Samsung One UI: "Battery protection → Maximum"
 *     stops at `battery_protection_threshold`, 80–95 %). Basic/Adaptive charge to 100 %.
 *  3. LEARNED — a non-Samsung phone was seen holding below 100 % while plugged in.
 *  4. NONE — charges to 100 %.
 */
data class ChargeLimit(val percent: Int, val source: Source) {
    enum class Source { MANUAL, SYSTEM, LEARNED, NONE }

    val isLimited: Boolean get() = percent < 100

    companion object {
        val NONE = ChargeLimit(100, Source.NONE)
    }
}

/**
 * Reads Samsung's battery-protection settings. These are plain Settings.Global keys that
 * ordinary apps can read (verified on an S21 FE, One UI / Android 16), and they change the
 * moment the user flips the option, so a ContentObserver keeps the ETA in sync.
 *
 * protect_battery (verified on One UI): 1 = Maximum (stop at threshold), 3 = Basic. Any other value (Adaptive, off) charges to 100 %.
 */
class SystemChargeLimitReader(private val resolver: ContentResolver) {

    /** Returns the OS-enforced limit, 100 when protection charges fully, or null on non-Samsung phones. */
    fun read(): Int? {
        val mode = global(KEY_MODE)?.toIntOrNull() ?: return null
        if (mode != MODE_MAXIMUM) return 100
        val threshold = global(KEY_THRESHOLD)?.toIntOrNull()?.takeIf { it in 50..100 } ?: DEFAULT_THRESHOLD
        return threshold
    }

    fun observe(onChange: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        for (k in listOf(KEY_MODE, KEY_THRESHOLD)) {
            runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(k), false, observer) }
        }
        return observer
    }

    private fun global(key: String): String? = runCatching { Settings.Global.getString(resolver, key) }.getOrNull()

    companion object {
        private const val KEY_MODE = "protect_battery"
        private const val KEY_THRESHOLD = "battery_protection_threshold"
        private const val MODE_MAXIMUM = 1
        private const val DEFAULT_THRESHOLD = 85
    }
}
