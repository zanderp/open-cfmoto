// SPDX-License-Identifier: AGPL-3.0-or-later
// Now-Playing bridge — reads the phone's active media session (whatever app the rider is playing)
// and exposes it as a reactive snapshot for the cockpit music panel. Read-only observation plus the
// three transport verbs; no playback of our own. Gated on the notification-listener grant that
// [NowPlayingListener]'s presence unlocks. Slice 1 of the split-dash (map + music) feature.
package dev.zanderp.opencfmoto.cockpit

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable snapshot of the current media session for the UI to render. */
data class NowPlayingState(
    val hasAccess: Boolean = false,
    val hasSession: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,
    val playing: Boolean = false,
)

/**
 * Process-global observer of the active media session, in the spirit of [dev.zanderp.opencfmoto.ConnectionState].
 *
 * Lifecycle contract: [start] registers the platform listeners and [stop] removes them — call them
 * in pairs (a Compose `DisposableEffect`). Everything runs on the main thread: we register the
 * [MediaSessionManager] listener and the [MediaController.Callback] without a Handler, so both are
 * delivered on the thread that registered them (the UI thread), which also owns start/stop and the
 * transport verbs. No cross-thread mutation, no leaks — we only ever hold the *application* context
 * and unbind the current controller before rebinding.
 */
object NowPlaying {

    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state.asStateFlow()

    // All mutated on the main thread; @Volatile is cheap insurance for the reference reads.
    @Volatile private var appContext: Context? = null
    @Volatile private var manager: MediaSessionManager? = null
    @Volatile private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    @Volatile private var controller: MediaController? = null
    @Volatile private var controllerCallback: MediaController.Callback? = null
    @Volatile private var hasAccess: Boolean = false

    /** True when this app is an enabled notification listener (the grant that unlocks session reads). */
    fun hasAccess(ctx: Context): Boolean {
        // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is @hide; the backing key is stable.
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }

    /**
     * Begin observing. If the grant is missing we publish [NowPlayingState.hasAccess]=false and bail;
     * the UI turns that into the "grant access" prompt. Idempotent: tears down any prior registration
     * first, so a re-entrant start never double-registers.
     */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appContext = app
        teardown() // defensive: drop any stale registrations before wiring fresh ones

        val access = hasAccess(app)
        hasAccess = access
        if (!access) {
            _state.value = NowPlayingState(hasAccess = false)
            return
        }

        val mgr = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        manager = mgr
        if (mgr == null) {
            _state.value = NowPlayingState(hasAccess = true, hasSession = false)
            return
        }

        val component = ComponentName(app, NowPlayingListener::class.java)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> bind(controllers) }
        sessionsListener = listener
        runCatching { mgr.addOnActiveSessionsChangedListener(listener, component) }

        val active = runCatching { mgr.getActiveSessions(component) }.getOrNull()
        bind(active)
    }

    /** Stop observing and unregister everything. Keeps [hasAccess] so the UI can still reflect it. */
    fun stop(ctx: Context) {
        teardown()
        _state.value = NowPlayingState(hasAccess = hasAccess, hasSession = false)
    }

    fun playPause() {
        val c = controller ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        runCatching { if (playing) c.transportControls.pause() else c.transportControls.play() }
    }

    fun next() {
        runCatching { controller?.transportControls?.skipToNext() }
    }

    fun prev() {
        runCatching { controller?.transportControls?.skipToPrevious() }
    }

    // --- internals -----------------------------------------------------------------------------

    /** Bind to the best controller in [controllers] (prefer one that is PLAYING, else the first). */
    private fun bind(controllers: List<MediaController>?) {
        val best = pickBest(controllers)

        // Detach the previous controller's callback before swapping, so we never leak or double-fire.
        controller?.let { old -> controllerCallback?.let { cb -> runCatching { old.unregisterCallback(cb) } } }
        controllerCallback = null
        controller = best

        if (best != null) {
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onSessionDestroyed() = rebindFromManager()
            }
            controllerCallback = cb
            runCatching { best.registerCallback(cb) }
        }
        publish()
    }

    /** Re-query active sessions and rebind — used when the bound session is destroyed. */
    private fun rebindFromManager() {
        val app = appContext ?: return
        val mgr = manager ?: return
        val component = ComponentName(app, NowPlayingListener::class.java)
        val active = runCatching { mgr.getActiveSessions(component) }.getOrNull()
        bind(active)
    }

    private fun pickBest(controllers: List<MediaController>?): MediaController? {
        if (controllers.isNullOrEmpty()) return null
        return controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.first()
    }

    private fun publish() {
        _state.value = snapshotOf(controller)
    }

    private fun snapshotOf(c: MediaController?): NowPlayingState {
        if (c == null) return NowPlayingState(hasAccess = hasAccess, hasSession = false)
        val md = runCatching { c.metadata }.getOrNull()
        val ps = runCatching { c.playbackState }.getOrNull()
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        return NowPlayingState(
            hasAccess = hasAccess,
            hasSession = true,
            title = title,
            artist = artist,
            art = art,
            playing = playing,
        )
    }

    /** Remove listeners/callbacks. Does not clear [hasAccess]; leaves state untouched. */
    private fun teardown() {
        sessionsListener?.let { l -> manager?.let { m -> runCatching { m.removeOnActiveSessionsChangedListener(l) } } }
        sessionsListener = null
        controller?.let { c -> controllerCallback?.let { cb -> runCatching { c.unregisterCallback(cb) } } }
        controllerCallback = null
        controller = null
    }
}
