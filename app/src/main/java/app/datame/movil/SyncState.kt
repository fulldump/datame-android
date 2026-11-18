package app.datame.movil

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase {
    IDLE, // nothing to do, or not yet run
    CONNECTING,
    SCANNING,
    UPLOADING,
    DONE,
    NO_NAS, // not at home, or the NAS is off
    UNAUTHORIZED, // the phone was removed in the web
    STANDBY, // the NAS is a standby machine
    FULL,
    NO_PERMISSION,
    ERROR,
}

/** What the screen shows: kept by the worker, read by the screen, even across restarts. */
data class SyncStatus(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val currentName: String = "",
    val currentSent: Long = 0,
    val currentSize: Long = 0,
    val lastRun: Long = 0, // millis
    val lastSuccess: Long = 0,
    val sentTotal: Long = 0, // files in the house, from this phone
    val pending: Int = 0,
    val failures: Int = 0,
)

class SyncState(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _status.value = read() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun read() = SyncStatus(
        phase = runCatching { Phase.valueOf(prefs.getString("phase", "IDLE")!!) }.getOrDefault(Phase.IDLE),
        message = prefs.getString("message", "") ?: "",
        done = prefs.getInt("done", 0),
        total = prefs.getInt("total", 0),
        currentName = prefs.getString("currentName", "") ?: "",
        currentSent = prefs.getLong("currentSent", 0),
        currentSize = prefs.getLong("currentSize", 0),
        lastRun = prefs.getLong("lastRun", 0),
        lastSuccess = prefs.getLong("lastSuccess", 0),
        sentTotal = prefs.getLong("sentTotal", 0),
        pending = prefs.getInt("pending", 0),
        failures = prefs.getInt("failures", 0),
    )

    fun update(change: (SyncStatus) -> SyncStatus) {
        val next = change(read())
        prefs.edit()
            .putString("phase", next.phase.name)
            .putString("message", next.message)
            .putInt("done", next.done)
            .putInt("total", next.total)
            .putString("currentName", next.currentName)
            .putLong("currentSent", next.currentSent)
            .putLong("currentSize", next.currentSize)
            .putLong("lastRun", next.lastRun)
            .putLong("lastSuccess", next.lastSuccess)
            .putLong("sentTotal", next.sentTotal)
            .putInt("pending", next.pending)
            .putInt("failures", next.failures)
            .apply()
        _status.value = next
    }

    fun clear() {
        prefs.edit().clear().apply()
        _status.value = SyncStatus()
    }
}
