package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import android.widget.FrameLayout

/**
 * H.264 video pipeline for MotoPlay mirroring.
 *
 *   VirtualDisplay  ──hosts──▶  Presentation ("Hello World", live clock)
 *        │ output Surface = encoder input
 *        ▼
 *   MediaCodec (video/avc, 800x384)  ──encoded access units──▶  frameQueue
 *
 * The data socket pulls one frame per REQ_RV_DATA_NEXT(114) via [pollFrame].
 * Frames are Annex-B; SPS/PPS (codec config) is prepended to the first keyframe so the
 * decoder on the bike can start.
 *
 * No MediaProjection needed: we render our OWN content onto a private VirtualDisplay
 * (the same approach as MotoPlay's SplitScreenPresentation).
 */
class VideoPipeline(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit,
    /**
     * When true, the pipeline only runs the H.264 encoder and exposes [encoderInputSurface] for
     * an EXTERNAL producer (the Android Auto video decoder) to render into. No Presentation /
     * MediaProjection source is created. See [encoderInputSurface] and AaVideoBridge.
     */
    private val externalSource: Boolean = false,
    /**
     * When true, an [AaCompositor] sits between the AA decoder and the encoder: the decoder renders
     * into [decoderInputSurface] and the compositor letterboxes it (aspect-preserved) into the
     * encoder canvas. The encoder is created lazily in [configureBikeCanvas] once the bike reports
     * its canvas size (so the encoder matches the bike, not a hardcoded resolution).
     */
    private val compositor: Boolean = false,
    /**
     * Optional density for [createOwnVirtualDisplay]. Ride MO OEM map uses **187 dpi** on a
     * 1024×464 `NaviVirtualDisplay` (not the scaled TARGET_DASH_WIDTH_DP heuristic).
     */
    private val ownDisplayDensityDpi: Int? = null,
    // ─────────────── DEV PROBE HOOKS (all default off → production is byte-for-byte untouched) ───────────────
    /**
     * When true, THIS instance dumps encoder output to a .h264 file even with [DUMP_H264] off, tags the
     * file "probe", and uses [PROBE_DUMP_CAP] so a ~60 s run is captured whole. Only [MapLibreVdProbe] sets it.
     */
    private val forceDump: Boolean = false,
    /**
     * When true, the drain loop emits a once-per-second FRAME COUNTER on a distinct logcat tag ([PROBE_TAG])
     * so the MapLibre-VD probe's encoder output RATE can be watched live — the signal that answers the
     * "does it keep producing frames with the screen off?" question. Only [MapLibreVdProbe] sets it.
     */
    private val probeFrameLog: Boolean = false,
    /**
     * DEV PROBE ONLY. When non-null in own-content mode, the VirtualDisplay [Presentation] hosts THIS
     * content (a forced-MapLibre `MapView`) instead of the placeholder — invoked on the main thread with
     * the themed presentation host + its context + an isAlive supplier. This is what forces MapLibre onto
     * the app's REAL, proven VD+encoder pipeline so the renderer is the single isolated variable.
     */
    private val probePresentationContent: ((host: FrameLayout, presCtx: Context, isAlive: () -> Boolean) -> Unit)? = null,
    /**
     * DEV PROBE ONLY. Called on the main thread inside [stop] BEFORE the Presentation/VirtualDisplay are
     * torn down, so the probe can release its MapLibre view + camera animation with no leak.
     */
    private val onProbeStop: (() -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var drainThread: Thread? = null
    private var aaCompositor: AaCompositor? = null
    /** Letterbox scaler for MediaProjection (whole-screen or single-app) → bike encoder. */
    private var mirrorScaler: AaCompositor? = null
    private var projectionCallback: android.media.projection.MediaProjection.Callback? = null
    private var mirrorDisplayListener: DisplayManager.DisplayListener? = null
    private var gpxVoice: GpxVoice? = null
    private var gpxDashUi: GpxDashUi? = null
    private var encoderW = 0
    private var encoderH = 0
    @Volatile private var running = false
    /** True after a successful [start] until [stop]. */
    val isAlive: Boolean get() = running

    // The bitrate the encoder was configured with (the user's target). Adaptive tuning scales DOWN
    // from this; it is the ceiling. 0 until the encoder is created.
    @Volatile private var baseBitrate = 0
    // The bitrate currently applied to the running encoder (via live setParameters).
    @Volatile private var currentBitrate = 0
    // Monotonic count of encoded frames dropped because the queue was full (the bike couldn't pull
    // them fast enough). A rising count is the downstream-congestion signal the adaptive controller
    // uses to back the bitrate off. See [droppedFramesTotal] and [AdaptiveVideoController].
    @Volatile private var droppedFrames = 0L

    private val frameQueue = LinkedBlockingDeque<ByteArray>(8)
    @Volatile private var codecConfig: ByteArray? = null   // SPS/PPS
    // When set, the drain loop discards encoder output until the next keyframe, so the first frame a
    // freshly-attached bike client receives is a full SPS+PPS+IDR. See onBikeDataStart().
    @Volatile private var awaitKeyframe = false

    /** Latest Annex-B SPS/PPS from the encoder (null until [BUFFER_FLAG_CODEC_CONFIG]). */
    fun codecConfigBytes(): ByteArray? = codecConfig

    /** Ask the running encoder for an immediate IDR (no queue flush). */
    fun requestKeyframe(reason: String = "manual") {
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            log("[VIDEO] requested IDR ($reason)")
        } catch (e: Exception) {
            log("[VIDEO] requestKeyframe($reason) failed: $e")
        }
    }

    // Diagnostic: dump the exact Annex-B H.264 access units we send to the bike into a .h264 file so
    // the stream can be inspected off-device (ffprobe/ffmpeg). Bounded to DUMP_CAP frames so it never
    // grows unbounded or stalls the send path for long. Filename is tagged by source (aa/mirror/own)
    // so an AA capture and a mirror capture can be compared directly. See docs/05-DEBUG-KNOWLEDGE.md.
    private var dumpOut: java.io.OutputStream? = null
    private var dumpFrames = 0
    private var dumpPath: String? = null
    private var dumpCap = DUMP_CAP
    // DEV PROBE frame-rate counter state (see [countProbeFrame]).
    private var probeFramesTotal = 0L
    private var probeWindowStart = 0L
    private var probeWindowBase = 0L

    fun start() {
        if (running) return
        running = true

        if (compositor) {
            // Android Auto (letterbox) mode: bring up the compositor now so the AA decoder has an
            // input surface and can reach steady video; the encoder is created later, once the bike
            // tells us its canvas size (see [configureBikeCanvas]).
            aaCompositor = AaCompositor(log).also { it.start() }
            log("[VIDEO] COMPOSITOR mode — decoder input ready; awaiting bike canvas")
            return
        }

        if (!createEncoder(width, height)) { stop(); return }

        if (externalSource) {
            // Android Auto mode: the AA VideoDecoder renders into inputSurface (see
            // encoderInputSurface()). No Presentation/MediaProjection source here.
            log("[VIDEO] EXTERNAL source mode (Android Auto) — encoder input surface ready")
            return
        }

        val projection = ProjectionHolder.projection
        if (projection != null) {
            log("[VIDEO] mirror mode (MediaProjection — whole screen or single app)")
            setupProjectionDisplay(projection)
        } else if (GpxSession.active) {
            log("[VIDEO] GPX viewer mode (Presentation)")
            // Soft AA→Map often runs on the main thread — set up immediately so the first IDR
            // already has map pixels (postponing left the dash on a frozen AA frame).
            if (Looper.myLooper() == Looper.getMainLooper()) setupGpxPresentation()
            else main.post { setupGpxPresentation() }
        } else {
            log("[VIDEO] own-content mode (Presentation)")
            // DEV PROBE: same VD+encoder plumbing, but the Presentation hosts a forced-MapLibre MapView.
            val setup: () -> Unit =
                if (probePresentationContent != null) ::setupProbePresentation else ::setupDisplayAndPresentation
            if (Looper.myLooper() == Looper.getMainLooper()) setup()
            else main.post { setup() }
        }
    }

    /** Create + start the H.264 encoder at [w]x[h] and its drain thread. Returns false on failure. */
    private fun createEncoder(w: Int, h: Int): Boolean {
        try {
            val profile = BikeProfileHolder.active
            // Map / GPX Presentation changes every pan/GPS tick — give H.264 more bits + shorter GOP
            // so motion doesn't smear into block artifacts on the bike (phone preview skips encode).
            // Skip FREE_RIDE 1.35× boost when the profile already targets a low OEM rate (e.g. Morini 10 fps / 2 Mbit).
            val mapBoost = GpxSession.active && profile.videoFrameRate >= 30
            val bitrate = (VideoPrefs.bitrateFor(context, profile) * if (mapBoost) 1.35f else 1f).toInt()
            val iframeSec = if (mapBoost) {
                maxOf(1, profile.videoIFrameIntervalSec / 2)
            } else {
                profile.videoIFrameIntervalSec
            }
            // Ride MO: color 0x7F000789 (= COLOR_FormatSurface) + bitrate-mode=2 (CBR).
            val colorFmt = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            fun baseFormat() = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFmt)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                try {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    )
                } catch (_: Exception) {
                }
                setInteger(MediaFormat.KEY_FRAME_RATE, profile.videoFrameRate)
                // MapLibre GL free-runs (~60-115fps, no default cap) and floods this surface-input encoder; the
                // dash wire is timestamp-less H.264 paced by arrival rate, so its real-time decoder greens out
                // (see MapLibreDashController). Cap the INPUT rate HERE so excess GL frames are dropped at the
                // encoder regardless of MapLibres own governor, which mis-paces on a secondary display vs a 120Hz
                // primary (mapbox-maps-android#2654). Float key, API23+; harmless hint if the encoder ignores it.
                try { setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, if (mapBoost) 24f else profile.videoFrameRate.toFloat()) } catch (_: Exception) {}
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeSec)
                // Surface-input encoders only emit on new buffers; a STATIC screen (e.g. mirror of
                // an idle app) then produces zero frames and the bike times out. Repeat the last
                // frame if nothing new arrives so output is continuous even when the screen is still.
                // The floor is deliberately LOW frequency: AaCompositor already re-emits a static
                // frame every ~2s (IDLE_REDRAW_MS) to hold the bike's ~9s media-socket timeout, so the
                // encoder only needs a backstop below that timeout — not a 10fps re-encode of an
                // unchanging map, which was pure wasted heat/battery (see docs/06-OPTIMIZATION-IDEAS.md
                // idea 5). At real frame rates the compositor delivers new buffers well before this
                // fires, so it never caps motion.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, IDLE_ENCODER_REPEAT_US)
                // Force strictly-ascending output with no B-frame reordering: the bike wire format
                // sends raw access units with NO timestamps, so a decoder that received reordered
                // (B-)frames could never reassemble display order → black/garbage. Baseline already
                // forbids B-frames; this also covers the Main/High fallback path. Hint only — encoders
                // that don't support it ignore it.
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val forceBaseline = BikeProfileHolder.active.forceBaseline
            try {
                if (forceBaseline) {
                    val fmt = baseFormat().apply {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                    }
                    c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    log("[VIDEO] configured Baseline@3.1")
                } else {
                    // No KEY_PROFILE / KEY_LEVEL — matches Ride MO retained MediaFormat (vc61).
                    c.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    log("[VIDEO] configured no explicit AVC profile/level (encoder default)")
                }
            } catch (e: Exception) {
                log("[VIDEO] encoder configure failed ($e) — retrying default profile")
                c.reset()
                c.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = c.createInputSurface()
            c.start()
            codec = c
            baseBitrate = bitrate; currentBitrate = bitrate
            encoderW = w; encoderH = h
            val outFmt = try { c.outputFormat.toString() } catch (_: Exception) { "?" }
            log(
                "[VIDEO] encoder started ${w}x${h} h264 ${profile.videoFrameRate}fps " +
                    "${bitrate / 1000}kbps (${VideoPrefs.get(context).name.lowercase()}) " +
                    "color=0x${Integer.toHexString(colorFmt)} bitrateMode=CBR(2)",
            )
            log("[VIDEO] MediaCodec outputFormat=$outFmt")
            maybeStartDump()
            if (drainThread == null) drainThread = thread(name = "video-drain", isDaemon = true) { drainLoop() }
            return true
        } catch (e: Exception) {
            log("[VIDEO] createEncoder failed: $e")
            return false
        }
    }

    /**
     * Compositor mode only: create the encoder at the bike's canvas size and point the compositor
     * at it. Called when the bike's REQ_CONFIG_CAPTURE dimensions are known. Idempotent; a later
     * different size is not re-applied live (logged instead).
     */
    /**
     * @param force when true, tear down an existing encoder and recreate at [w]×[h]
     *   (Yunmo SoftAP learns the canvas from cmd 0x32 after AA may already have started).
     */
    fun configureBikeCanvas(w: Int, h: Int, force: Boolean = false) {
        if (!compositor) return
        if (codec != null) {
            if (encoderW == w && encoderH == h) return
            if (!force) {
                log(
                    "[VIDEO] bike canvas changed ${encoderW}x$encoderH → ${w}x$h — " +
                        "live resize unsupported, keeping ${encoderW}x$encoderH",
                )
                return
            }
            log("[VIDEO] bike canvas force-resize ${encoderW}x$encoderH → ${w}x$h")
            try {
                codec?.stop()
            } catch (_: Exception) {}
            try {
                codec?.release()
            } catch (_: Exception) {}
            codec = null
            try {
                inputSurface?.release()
            } catch (_: Exception) {}
            inputSurface = null
            encoderW = 0
            encoderH = 0
            codecConfig = null
            frameQueue.clear()
        }
        if (!createEncoder(w, h)) return
        val src = BikeProfileHolder.aaVideo
        val surf = inputSurface
        if (surf != null) {
            val fit = VideoPrefs.fit(context)
            val power = VideoPrefs.power(context)
            // Match-panel-aspect: AA drew its UI into (coded - margins); sample only that sub-rect so
            // the aspect-correct content — not the padded coded frame — is mapped onto the bike canvas.
            val margins = BikeProfileHolder.aaContentMargins
            val usableW = (src.width - margins.marginW).coerceIn(16, src.width)
            val usableH = (src.height - margins.marginH).coerceIn(16, src.height)
            aaCompositor?.setFrameCap(power.fps)
            aaCompositor?.setSourceCrop(usableW.toFloat() / src.width, usableH.toFloat() / src.height)
            aaCompositor?.setOutput(surf, w, h, usableW, usableH, fit)
            log("[VIDEO] bike canvas ${w}x$h configured; AA source ${src.width}x${src.height} " +
                "usable ${usableW}x$usableH (margins ${margins.marginW}x${margins.marginH}) → " +
                "fit=$fit power=${power.fps}fps")
        }
    }

    /**
     * Mirror the phone (whole display or a single app on Android 14+) into the bike encoder.
     *
     * Capture goes into an [AaCompositor] SurfaceTexture so [onCapturedContentResize] can retarget
     * the VirtualDisplay to the real capture size (required for single-app sharing — feeding the
     * encoder surface directly at bike size yields black / no frames). The compositor maps into the
     * fixed bike canvas [width]x[height].
     *
     * Single-app capture only produces frames while that app is visible. Staying in OpenCfMoto after
     * picking an app yields `visible=false` and a black dash — [MainActivity] moves our task back.
     */
    private fun setupProjectionDisplay(projection: android.media.projection.MediaProjection) {
        try {
            val encSurf = inputSurface ?: run {
                log("[VIDEO] projection display failed: no encoder surface"); return
            }
            val metrics = context.resources.displayMetrics
            val density = metrics.densityDpi.coerceAtLeast(160)
            // Start the capture buffer at the physical display size; app-capture resize updates it.
            val initW = metrics.widthPixels.coerceAtLeast(width).let { it - (it % 2) }
            val initH = metrics.heightPixels.coerceAtLeast(height).let { it - (it % 2) }

            val scaler = AaCompositor(log).also { it.start(initW, initH) }
            mirrorScaler = scaler
            val captureSurf = scaler.inputSurface ?: run {
                log("[VIDEO] projection display failed: no compositor input"); return
            }
            // Setup ▸ Screen fit (same preference as AA). Prefer Fit for mirror so tall phone /
            // single-app buffers letterbox instead of cropping to a slice.
            fun currentFit() = VideoPrefs.fit(context)
            fun applyMirrorOutput(srcW: Int, srcH: Int, reason: String) {
                val fit = currentFit()
                scaler.updateCaptureSize(srcW, srcH)
                scaler.setOutput(encSurf, width, height, srcW, srcH, fit)
                log("[MIRROR] $reason src=${srcW}x$srcH → canvas ${width}x$height fit=$fit")
            }
            applyMirrorOutput(initW, initH, "initial")
            scaler.setFrameCap(VideoPrefs.power(context).fps)

            var lastCapW = initW
            var lastCapH = initH

            fun onCaptureSize(w: Int, h: Int, reason: String) {
                // Even dims for SurfaceTexture / encoder math. Must use VirtualDisplay.resize —
                // MediaProjection allows only ONE createVirtualDisplay per consent token.
                val rw = w.coerceAtLeast(2).let { it + (it % 2) }
                val rh = h.coerceAtLeast(2).let { it + (it % 2) }
                if (rw == lastCapW && rh == lastCapH && reason == "resize") return
                lastCapW = rw; lastCapH = rh
                log("[MIRROR] app-capture $reason ${w}x$h → VD ${rw}x$rh → canvas ${width}x$height")
                try {
                    virtualDisplay?.resize(rw, rh, density)
                } catch (e: Exception) {
                    log("[MIRROR] VirtualDisplay.resize failed: $e")
                }
                applyMirrorOutput(rw, rh, reason)
            }

            val cb = object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    log("[MIRROR] MediaProjection stopped")
                    ProjectionService.setKeepScreenOn(context, false)
                }

                override fun onCapturedContentResize(w: Int, h: Int) {
                    onCaptureSize(w, h, "resize")
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    log("[MIRROR] capture visible=$isVisible")
                    if (!isVisible) {
                        log(
                            "[MIRROR] shared app is in the background — Android sends no frames. " +
                                "Prefer Entire screen for riding, or leave the shared app on top.",
                        )
                    }
                }
            }
            projectionCallback = cb
            // Required on API 34+: register before createVirtualDisplay.
            projection.registerCallback(cb, main)

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = projection.createVirtualDisplay(
                "OpenCfMotoMirror", initW, initH, density, flags, captureSurf, null, main,
            )
            ProjectionService.setKeepScreenOn(context, true)
            // Fold / rotate: re-apply fit if metrics change without a content-resize callback.
            val displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (!running) return
                    val m = context.resources.displayMetrics
                    val dw = m.widthPixels.coerceAtLeast(2).let { it - (it % 2) }
                    val dh = m.heightPixels.coerceAtLeast(2).let { it - (it % 2) }
                    // Only nudge when we're still on whole-screen-sized capture (not single-app).
                    if (lastCapW >= dw - 2 || lastCapH >= dh - 2) {
                        onCaptureSize(dw, dh, "display-changed")
                    } else {
                        applyMirrorOutput(lastCapW, lastCapH, "display-changed-refit")
                    }
                }
            }
            mirrorDisplayListener = displayListener
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.registerDisplayListener(displayListener, main)
            log("[VIDEO] mirroring → capture ${initW}x$initH → bike ${width}x$height (fit=${currentFit()})")
        } catch (e: Exception) {
            log("[VIDEO] projection display failed: $e")
        }
    }

    private fun setupDisplayAndPresentation() {
        try {
            val display = createOwnVirtualDisplay() ?: return
            val pres = Presentation(context, display)
            val root = LinearLayout(pres.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0D47A1"))
            }
            val title = TextView(pres.context).apply {
                text = "OpenCfMoto"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }
            val clock = TextView(pres.context).apply {
                setTextColor(Color.parseColor("#80D8FF"))
                textSize = 20f
                gravity = Gravity.CENTER
            }
            root.addView(title)
            root.addView(clock)
            pres.setContentView(root)
            pres.show()
            presentation = pres
            log("[VIDEO] presentation shown on virtual display")

            val ticker = object : Runnable {
                var n = 0
                override fun run() {
                    if (!running) return
                    clock.text = "frame tick ${n++}"
                    main.postDelayed(this, 100)
                }
            }
            main.post(ticker)
        } catch (e: Exception) {
            log("[VIDEO] display/presentation failed: $e")
        }
    }

    /**
     * DEV PROBE ONLY (own-content mode). Identical VirtualDisplay + encoder plumbing as
     * [setupDisplayAndPresentation] / [setupGpxPresentation] — the ONE difference is that the
     * Presentation hosts caller-supplied content ([probePresentationContent], a forced-MapLibre
     * `MapView`) instead of the placeholder. That makes MapLibre-vs-osmdroid the single isolated
     * variable feeding the real H.264 encoder, so the block-artifact question can be answered without
     * confounding it with a hand-rolled parallel pipeline.
     */
    private fun setupProbePresentation() {
        try {
            val factory = probePresentationContent ?: run { setupDisplayAndPresentation(); return }
            val display = createOwnVirtualDisplay() ?: return
            val pres = Presentation(context, display)
            // VirtualDisplay Presentation contexts lack the app theme — Material/AppCompat ?attr/… then
            // fail to inflate. Same wrap the GPX dash presentation uses.
            val themed = ContextThemeWrapper(pres.context, R.style.Theme_OpenCfMoto)
            val host = FrameLayout(themed)
            pres.setContentView(
                host,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            pres.show()
            presentation = pres
            log("[VIDEO] PROBE presentation shown on virtual display → ${width}x$height")
            factory(host, themed) { running }
        } catch (e: Exception) {
            log("[VIDEO] PROBE presentation failed: $e — falling back to placeholder")
            setupDisplayAndPresentation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGpxPresentation() {
        try {
            if (!GpxSession.active) {
                log("[GPX] session inactive — placeholder"); setupDisplayAndPresentation(); return
            }
            if (GpxSession.mode == GpxSession.Mode.GPX && GpxSession.trackFile == null) {
                log("[GPX] no track — placeholder"); setupDisplayAndPresentation(); return
            }
            val display = createOwnVirtualDisplay() ?: return
            val pres = Presentation(context, display)
            // VirtualDisplay Presentation contexts often lack the app theme — AppCompat/Material
            // ?attr/… then fail with InflateException "Error inflating class <unknown>".
            val themed = ContextThemeWrapper(pres.context, R.style.Theme_OpenCfMoto)
            val root = LayoutInflater.from(themed).inflate(R.layout.presentation_gpx, null)
            val ui = GpxDashUi(themed, root, log, isAlive = { running }, projected = true)
            if (!ui.bind()) {
                ui.release()
                setupDisplayAndPresentation()
                return
            }
            gpxDashUi = ui
            gpxVoice = null
            pres.setContentView(root)
            try {
                pres.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Exception) {
            }
            pres.show()
            presentation = pres
            // Screen + FGS wake so phone sleep / background doesn't stall VirtualDisplay.
            AppHttp.ensureCellularUplink()
            AndroidAutoService.setGpxScreenWake(context, true)
            log("[GPX] presentation shown → ${width}x${height}")
        } catch (e: Exception) {
            val cause = generateSequence(e.cause) { it.cause }.lastOrNull()
            log("[GPX] presentation failed: $e${cause?.let { " cause=$it" } ?: ""}")
            try { gpxDashUi?.release() } catch (_: Exception) {}
            gpxDashUi = null
            setupDisplayAndPresentation()
        }
    }

    private fun createOwnVirtualDisplay(): android.view.Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // Match Ride MO dumpsys: PRIVATE (default without PUBLIC) | PRESENTATION | OWN_CONTENT_ONLY.
        // NEVER_BLANK is applied by the system for private virtual displays — not a create flag.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        // Scale density to the bike resolution so controls stay glanceable without crowding the panel.
        // Higher TARGET_DASH_WIDTH_DP → lower dpi → smaller chrome (~20% vs the old 720dp target).
        // OEM Yunmo path overrides via [ownDisplayDensityDpi] (187).
        val densityDpi = ownDisplayDensityDpi
            ?: Math.round(160.0 * width / TARGET_DASH_WIDTH_DP).toInt().coerceIn(160, 640)
        val vd = dm.createVirtualDisplay("OpenCfMoto", width, height, densityDpi, inputSurface, flags)
        virtualDisplay = vd
        log(
            "[VIDEO] own display ${width}x${height} @${densityDpi}dpi " +
                "flags=0x${Integer.toHexString(flags)} " +
                "(PRIVATE|PRESENTATION|OWN_CONTENT_ONLY; NEVER_BLANK via system)",
        )
        return vd?.display ?: run { log("[VIDEO] virtualDisplay.display null"); null }
    }

    private fun drainLoop() {
        val codec = this.codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { codec.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) {
                log("[VIDEO] dequeue failed: $e"); break
            }
            if (idx < 0) continue
            val buf = try { codec.getOutputBuffer(idx) } catch (e: Exception) { null }
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codecConfig = bytes   // SPS/PPS — hold, prepend to next keyframe
                    log("[VIDEO] got codec config (SPS/PPS) ${bytes.size}b")
                } else {
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (probeFrameLog) countProbeFrame()
                    if (awaitKeyframe && !isKey) {
                        // A bike client just attached but this is a P-frame — it references frames the
                        // client never received, so serving it would leave the dash decoder uninitialised
                        // (black). Drop until the next keyframe. (Buffer still released below.)
                    } else {
                        if (isKey) awaitKeyframe = false
                        val out = if (isKey && codecConfig != null) codecConfig!! + bytes else bytes
                        writeDump(out)
                        // Keep the queue fresh: if full, drop oldest so we never lag far behind. A
                        // sustained drop rate means the bike can't pull as fast as we encode
                        // (downstream/Wi-Fi congestion) — counted for the adaptive controller.
                        if (!frameQueue.offerLast(out)) {
                            frameQueue.pollFirst()
                            frameQueue.offerLast(out)
                            droppedFrames++
                        }
                    }
                }
            }
            try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
        }
    }

    /**
     * The encoder's input Surface. In [externalSource] mode the Android Auto video decoder is
     * pointed at this surface (`VideoDecoder.setSurface`), so decoded AA frames are re-encoded to
     * the bike's 800x384 H.264 and pulled by the PXC data socket via [pollFrame]. Valid only
     * after [start].
     */
    fun encoderInputSurface(): android.view.Surface? = inputSurface

    /**
     * The bitrate the encoder was configured with (the user's target for this session). This is the
     * ceiling the adaptive controller scales down from; 0 before the encoder exists.
     */
    fun targetBitrate(): Int = baseBitrate

    /** Monotonic count of encoded frames dropped due to a full queue (downstream congestion signal). */
    fun droppedFramesTotal(): Long = droppedFrames

    /**
     * Live-adjust the running encoder's target bitrate (no codec re-create) via [MediaCodec.setParameters].
     * Used by [AdaptiveVideoController] to shed bits when the phone is hot or the Wi-Fi link is
     * congested, and to restore them as conditions recover. Clamped to (0, [baseBitrate]] so it can
     * never exceed the user's chosen target. No-op if unchanged or the encoder isn't up yet.
     */
    fun setEncoderBitrate(bps: Int) {
        val c = codec ?: return
        if (baseBitrate <= 0) return
        val clamped = bps.coerceIn(1, baseBitrate)
        if (clamped == currentBitrate) return
        try {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped)
            })
            currentBitrate = clamped
            log("[VIDEO] bitrate → ${clamped / 1000}kbps (target ${baseBitrate / 1000}kbps)")
        } catch (e: Exception) {
            log("[VIDEO] setEncoderBitrate failed: $e")
        }
    }

    /**
     * Live-adjust how many frames/sec the compositor pushes into the encoder (compositor mode only).
     * Delegates to [AaCompositor.setFrameCap]; used by [AdaptiveVideoController] to drop the rate as
     * the phone heats up. No-op outside compositor mode.
     */
    fun setFrameCap(fps: Int) {
        aaCompositor?.setFrameCap(fps)
    }

    /** Compositor mode: the surface the AA decoder renders into (letterboxed before the encoder). */
    fun decoderInputSurface(): android.view.Surface? = aaCompositor?.inputSurface

    /** Compositor mode: map a bike-canvas touch point to Android Auto source coords (letterbox-aware);
     *  null if the point is in a black bar. See AaCompositor.mapCanvasToSource. */
    fun mapBikeTouchToSource(cx: Int, cy: Int): Pair<Int, Int>? = aaCompositor?.mapCanvasToSource(cx, cy)

    /** Compositor mode: attach an in-app phone preview surface (HudViewActivity). No-op otherwise. */
    fun setPreviewSurface(surface: Surface, w: Int, h: Int) = aaCompositor?.setPreview(surface, w, h)

    /** Compositor mode: the preview SurfaceView changed size (rotation / relayout). */
    fun updatePreviewSize(w: Int, h: Int) = aaCompositor?.updatePreviewSize(w, h)

    /** Compositor mode: detach the phone preview (blocks until the GL surface is destroyed). */
    fun clearPreviewSurface() = aaCompositor?.clearPreview()

    /** Live update when the rider changes [ScreenMargins] while projecting. */
    fun refreshScreenMargins() = aaCompositor?.refreshMargins()

    /** Called by the data socket on each REQ_RV_DATA_NEXT(114). Returns one access unit. */
    fun pollFrame(timeoutMs: Long): ByteArray? =
        try { frameQueue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }

    /**
     * A bike video client is (re)attaching and about to pull frames (REQ_RV_DATA_START). Guarantee the
     * FIRST access unit it receives is a full keyframe (SPS+PPS+IDR):
     *  1. flush stale frames already in the queue (they may be mid-GOP P-frames),
     *  2. drop further encoder output until the next keyframe (awaitKeyframe), and
     *  3. ask the encoder for an immediate sync frame so that keyframe arrives right away.
     *
     * Without this, Android Auto mode is black: its encoder is created at REQ_CONFIG_CAPTURE — up to a
     * second before the bike opens the data socket — so the initial IDR is long evicted from the small
     * queue and the bike starts on a P-frame with no SPS/PPS, leaving the dash decoder uninitialised.
     * (Mirror mode avoided this only by creating its encoder lazily right as the bike attaches.)
     */
    fun onBikeDataStart() {
        frameQueue.clear()
        awaitKeyframe = true
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            log("[VIDEO] bike attached → flushed queue + requested IDR (first frame will be a keyframe)")
        } catch (e: Exception) {
            log("[VIDEO] requestKeyframe failed: $e")
        }
    }

    private fun maybeStartDump() {
        if (!(DUMP_H264 || forceDump) || dumpOut != null) return
        try {
            val base = context.getExternalFilesDir(null) ?: run { log("[DUMP] no external files dir"); return }
            val dir = java.io.File(base, "video").apply { mkdirs() }
            val tag = when {
                probePresentationContent != null -> "probe"
                compositor -> "aa"
                ProjectionHolder.projection != null -> "mirror"
                else -> "own"
            }
            dumpCap = if (forceDump) PROBE_DUMP_CAP else DUMP_CAP
            val f = java.io.File(dir, "opencfmoto-video-$tag.h264")
            dumpOut = java.io.BufferedOutputStream(java.io.FileOutputStream(f))
            dumpFrames = 0
            dumpPath = f.absolutePath
            log("[DUMP] recording H.264 → ${f.absolutePath} (first $dumpCap frames, then auto-stops)")
        } catch (e: Exception) {
            log("[DUMP] open failed: $e"); dumpOut = null
        }
    }

    /**
     * DEV PROBE: tally every access unit the encoder emits and log the output RATE once per second on a
     * distinct logcat tag ([PROBE_TAG]). This is the instrument for the screen-off question. Reading it:
     *  • rate ≈ encoder fps  → MapLibre is still rendering new frames to the VD;
     *  • rate ≈ 1 fps        → MapLibre STOPPED — only the encoder's static-frame repeat
     *                          ([IDLE_ENCODER_REPEAT_US]) is still emitting the last picture;
     *  • rate = 0            → the whole VD→encoder pipeline stalled.
     */
    private fun countProbeFrame() {
        probeFramesTotal++
        val now = android.os.SystemClock.elapsedRealtime()
        if (probeWindowStart == 0L) { probeWindowStart = now; probeWindowBase = 0L; return }
        val elapsed = now - probeWindowStart
        if (elapsed >= 1000L) {
            val delta = probeFramesTotal - probeWindowBase
            val fps = delta * 1000.0 / elapsed
            val msg = "[PROBE-FPS] encoder frames total=$probeFramesTotal " +
                "rate=${"%.1f".format(fps)}fps (+$delta in ${elapsed}ms) queueDrops=$droppedFrames"
            android.util.Log.i(PROBE_TAG, msg)
            log(msg)
            probeWindowStart = now
            probeWindowBase = probeFramesTotal
        }
    }

    /** Append one Annex-B access unit to the diagnostic dump until the cap, then close. */
    private fun writeDump(au: ByteArray) {
        val d = dumpOut ?: return
        try {
            d.write(au)
            dumpFrames++
            if (dumpFrames >= dumpCap) {
                d.flush(); d.close(); dumpOut = null
                log("[DUMP] complete: $dumpPath ($dumpFrames frames) — Share Log to send it")
            }
        } catch (e: Exception) {
            try { d.close() } catch (_: Exception) {}
            dumpOut = null
            log("[DUMP] write failed: $e")
        }
    }

    /**
     * @param abandonNavigation when true (default), a live NAV_TO is cleared so reopening the app
     *   doesn't resume a killed route. Soft AA→Map switches pass false so the destination survives.
     */
    fun stop(abandonNavigation: Boolean = true) {
        running = false
        try { dumpOut?.flush(); dumpOut?.close() } catch (_: Exception) {}
        if (dumpOut != null) log("[DUMP] stopped: $dumpPath ($dumpFrames frames) — Share Log to send it")
        dumpOut = null
        drainThread?.interrupt(); drainThread = null
        try { aaCompositor?.release() } catch (_: Exception) {}
        aaCompositor = null
        try { mirrorScaler?.release() } catch (_: Exception) {}
        mirrorScaler = null
        mirrorDisplayListener?.let { listener ->
            try {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dm.unregisterDisplayListener(listener)
            } catch (_: Exception) {}
        }
        mirrorDisplayListener = null
        projectionCallback?.let { cb ->
            try { ProjectionHolder.projection?.unregisterCallback(cb) } catch (_: Exception) {}
        }
        projectionCallback = null
        ProjectionService.setKeepScreenOn(context, false)
        // Soft AA→Map keeps the FGS wake; only release when this was the map Presentation itself
        // or a full stop (abandonNavigation). Soft-switch stops the AA compositor with
        // abandonNavigation=false and must not drop the screen wake before GPX attaches.
        if (abandonNavigation || gpxDashUi != null) {
            AndroidAutoService.setGpxScreenWake(context, false)
        }
        GpxSession.clearTouchTarget()
        // Killing projection mid-route must not leave NAV_TO sticky — reopen would "resume"
        // and immediately flash Arrived if you're still near that place.
        if (abandonNavigation && GpxSession.active && GpxSession.mode == GpxSession.Mode.NAV_TO) {
            GpxSession.abandonStaleNavigation("projection stopped")
        }
        try { gpxVoice?.shutdown() } catch (_: Exception) {}
        gpxVoice = null
        main.post {
            // DEV PROBE: release the MapLibre view + camera animation BEFORE the Presentation/VD go away.
            try { onProbeStop?.invoke() } catch (_: Exception) {}
            try { gpxDashUi?.release() } catch (_: Exception) {}
            gpxDashUi = null
            try { presentation?.dismiss() } catch (_: Exception) {}
            presentation = null
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        frameQueue.clear()
        codecConfig = null
    }

    companion object {
        /**
         * Logical width (dp) we render the own-content dash at, regardless of the bike's pixel
         * resolution. Smaller = bigger chrome. 900dp keeps HUD controls readable without the FAB /
         * speedometer crowding the metrics bar on typical bike panels.
         */
        const val TARGET_DASH_WIDTH_DP = 900.0

        /**
         * Encoder's static-screen frame-repeat floor (µs). Must stay comfortably under the bike's ~9s
         * media-socket timeout so an unchanging map still holds the link, but far slower than the old
         * 100ms/10fps value which re-encoded a still screen ~10× more than needed. AaCompositor's ~2s
         * idle redraw is the primary keep-alive; this is the encoder-side backstop. See idea 5 in
         * docs/06-OPTIMIZATION-IDEAS.md.
         */
        const val IDLE_ENCODER_REPEAT_US = 900_000L   // 0.9s → ≈1fps floor on a static screen

        /** Diagnostic build flag: dump the H.264 we send to the bike (bounded). Turn on to capture a
         *  .h264 of the exact wire stream for offline ffprobe analysis; off for normal use. */
        const val DUMP_H264 = false
        /** How many access units to capture before auto-stopping the dump (~20s at 30fps). */
        const val DUMP_CAP = 600

        /** Distinct logcat tag for the DEV MapLibre-VD probe's once-per-second frame-rate line
         *  ([countProbeFrame]) so it can be watched in isolation: `adb logcat -s MapLibreVdProbe:*`. */
        const val PROBE_TAG = "MapLibreVdProbe"
        /** Dump cap for the DEV probe: ~3000 AUs safely spans a 60 s run (incl. a mid-run screen-off
         *  toggle) even at 30–45 fps, so the pulled .h264 covers the whole experiment. */
        const val PROBE_DUMP_CAP = 3000
    }
}
