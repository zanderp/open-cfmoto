package dev.zanderp.opencfmoto

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coarse, process-global view of what the app is currently doing, so the UI can show a single
 * human-readable status line (instead of making users read the raw log) and so the reconnect
 * logic ([EasyConnProber] / [MainActivity]) can react to link drops.
 *
 * Lives as a process global like [AaVideoBridge] / [BikeProfileHolder]: the foreground service,
 * the prober, and the activity all publish transitions here and the activity observes them.
 *
 * [labelRes] is what the UI shows (localized). [logLabel] stays English for shared logs / support.
 */
enum class Phase(@StringRes val labelRes: Int, val logLabel: String, val busy: Boolean) {
    IDLE(R.string.conn_ready, "Ready", false),
    STARTING_AA(R.string.conn_starting_aa, "Starting Android Auto…", true),
    AA_VIDEO_LIVE(R.string.conn_aa_video_live, "Android Auto live — joining bike…", true),
    JOINING_WIFI(R.string.conn_joining_wifi, "Connecting to bike Wi-Fi…", true),
    PXC_CONNECTING(R.string.conn_pxc_connecting, "Linking to dashboard…", true),
    STREAMING(R.string.conn_streaming, "Connected — projecting to dash", false),
    MIRRORING(R.string.conn_mirroring, "Mirroring screen to dash", false),
    RECONNECTING(R.string.conn_reconnecting, "Link dropped — reconnecting…", true),
    WAITING_FOR_BIKE(R.string.conn_waiting_for_bike, "Bike out of range — waiting…", true),
    STOPPED(R.string.conn_stopped, "Stopped", false),
    ERROR(R.string.conn_error, "Error — see logs", false),
}

object ConnectionState {
    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Extra detail appended to the phase label (e.g. bike name, retry count). */
    @Volatile
    var detail: String = ""
        private set

    /** Observer (legacy MainActivity) — receives (phase, detail) on every transition, on any thread. */
    @Volatile
    var listener: ((Phase, String) -> Unit)? = null

    /** Reactive stream for the Compose shell / ViewModels — mirrors [listener] without replacing it. */
    private val _flow = MutableStateFlow(ConnSnapshot(Phase.IDLE, ""))
    val flow: StateFlow<ConnSnapshot> = _flow.asStateFlow()

    /**
     * Move to [newPhase]. Pass [newDetail] to change the trailing detail, or leave it null to keep
     * the existing detail (e.g. the bike name set when the connection started, so a background
     * component can flip to [Phase.STREAMING] without knowing which bike it is).
     */
    fun set(newPhase: Phase, newDetail: String? = null) {
        phase = newPhase
        if (newDetail != null) detail = newDetail
        val d = detail
        _flow.value = ConnSnapshot(newPhase, d)
        val text = if (d.isBlank()) newPhase.logLabel else "${newPhase.logLabel} — $d"
        LogBus.log("[state] $text")
        try {
            listener?.invoke(newPhase, d)
        } catch (_: Exception) {
        }
    }
}

/** Immutable connection snapshot for reactive (Compose) observers. */
data class ConnSnapshot(val phase: Phase, val detail: String)
