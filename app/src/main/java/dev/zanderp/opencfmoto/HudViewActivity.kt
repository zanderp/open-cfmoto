// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * Full-screen in-app dash: live AA video when connected, or Map / GPX phone preview when
 * [GpxSession] is active without a bike (debug / offline).
 */
class HudViewActivity : AppCompatActivity() {

    private lateinit var surface: SurfaceView
    private lateinit var gpxHost: FrameLayout
    private lateinit var hint: TextView
    private lateinit var titleView: TextView
    private var attached = false
    private var noSessionToastAt = 0L
    private var fullscreen = false
    private var gpxPreview = false
    private var gpxUi: GpxDashUi? = null
    private var boundSessionKey: String? = null
    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud_view)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surface = findViewById(R.id.hud_surface)
        gpxHost = findViewById(R.id.hud_gpx_host)
        hint = findViewById(R.id.hud_hint)
        titleView = findViewById(R.id.hud_title)

        insetsController = WindowInsetsControllerCompat(window, findViewById(R.id.hud_root)).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val topbar = findViewById<View>(R.id.hud_topbar)
        val baseTopPad = topbar.paddingTop
        val baseLeftPad = topbar.paddingLeft
        val baseRightPad = topbar.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(topbar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(baseLeftPad + bars.left, baseTopPad + bars.top, baseRightPad + bars.right, v.paddingBottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else finish()
            }
        })

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (gpxPreview) return
                val pipe = AaVideoBridge.pipeline
                if (pipe == null) {
                    LogBus.log("[HUD] no AA pipeline — preview will show once Android Auto is live")
                    return
                }
                if (!attached) {
                    pipe.setPreviewSurface(holder.surface, width, height)
                    attached = true
                    LogBus.log("[HUD] preview surface attached ${width}x$height")
                } else {
                    pipe.updatePreviewSize(width, height)
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (attached) {
                    AaVideoBridge.pipeline?.clearPreviewSurface()
                    attached = false
                    LogBus.log("[HUD] preview surface detached")
                }
            }
        })

        surface.setOnTouchListener { _, e -> onSurfaceTouch(e); true }

        findViewById<View>(R.id.hud_close).setOnClickListener { finish() }
        findViewById<View>(R.id.hud_map_hub).setOnClickListener {
            GpxActivity.start(this)
        }
        findViewById<View>(R.id.hud_toggle_controls).setOnClickListener { toggleControls() }
        findViewById<View>(R.id.hud_fullscreen).setOnClickListener { setFullscreen(!fullscreen) }
        findViewById<View>(R.id.hud_theme).setOnClickListener { cycleMapTheme() }

        findViewById<View>(R.id.hud_knob_back).setOnClickListener { scroll(-1) }
        findViewById<View>(R.id.hud_knob_fwd).setOnClickListener { scroll(+1) }
        findViewById<View>(R.id.hud_left).setOnClickListener { key(AaInput.KEY_LEFT) }
        findViewById<View>(R.id.hud_right).setOnClickListener { key(AaInput.KEY_RIGHT) }
        findViewById<View>(R.id.hud_up).setOnClickListener { key(AaInput.KEY_UP) }
        findViewById<View>(R.id.hud_down).setOnClickListener { key(AaInput.KEY_DOWN) }
        findViewById<View>(R.id.hud_ok).setOnClickListener { key(AaInput.KEY_ENTER) }
        findViewById<View>(R.id.hud_back).setOnClickListener { key(AaInput.KEY_BACK) }
        findViewById<View>(R.id.hud_home).setOnClickListener { key(AaInput.KEY_HOME) }
        findViewById<View>(R.id.hud_voice).setOnClickListener {
            ensureMicPermission()
            key(AaInput.KEY_ASSISTANT)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Force rebuild when Hub opens a new GPX / nav session into an existing preview.
        refreshMode(forceReload = true)
    }

    override fun onResume() {
        super.onResume()
        refreshMode(forceReload = false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // We keep configChanges so the map isn't torn down — still reflow the Waze chrome.
        gpxUi?.onConfigurationChanged()
    }

    override fun onPause() {
        if (isFinishing) stopGpxPreview()
        super.onPause()
    }

    override fun onDestroy() {
        stopGpxPreview()
        super.onDestroy()
    }

    private fun sessionKey(): String =
        "${GpxSession.mode}|${GpxSession.trackName}|${GpxSession.trackFile?.absolutePath}|${GpxSession.destination?.lat}|${GpxSession.destination?.lon}"

    private fun refreshMode(forceReload: Boolean) {
        // Live Android Auto video always wins: the phone Map preview is only a fallback for when
        // there's no bike pipeline. This keeps a previously-opened preview from sticking over the
        // real HUD once the bike connects.
        val live = AaVideoBridge.pipeline != null
        val wantGpx = !live && GpxSession.active
        if (wantGpx) {
            val key = sessionKey()
            val needReload = forceReload ||
                intent.getBooleanExtra(EXTRA_FORCE_RELOAD, false) ||
                key != boundSessionKey
            intent.removeExtra(EXTRA_FORCE_RELOAD)
            startGpxPreview(reload = needReload)
        } else {
            stopGpxPreview()
            val live = AaVideoBridge.pipeline != null
            hint.visibility = if (live) View.GONE else View.VISIBLE
            surface.visibility = View.VISIBLE
            gpxHost.visibility = View.GONE
            findViewById<View>(R.id.hud_topbar).visibility =
                if (fullscreen) View.GONE else View.VISIBLE
            findViewById<View>(R.id.hud_map_hub).visibility = View.GONE
            findViewById<View>(R.id.hud_controls).visibility =
                if (fullscreen) View.GONE else View.VISIBLE
            findViewById<View>(R.id.hud_toggle_controls).visibility = View.VISIBLE
            titleView.text = "Dash view"
        }
    }

    private fun startGpxPreview(reload: Boolean) {
        if (gpxPreview && gpxUi != null && !reload) {
            hint.visibility = View.GONE
            return
        }
        stopGpxPreview()
        gpxPreview = true
        gpxHost.removeAllViews()
        val root = try {
            LayoutInflater.from(this).inflate(R.layout.presentation_gpx, gpxHost, false)
        } catch (e: Exception) {
            gpxPreview = false
            LogBus.log("[HUD] inflate map chrome failed: $e")
            Toast.makeText(this, "Map UI failed to open", Toast.LENGTH_LONG).show()
            return
        }
        gpxHost.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val ui = GpxDashUi(this, root, { LogBus.log(it) }, isAlive = { !isFinishing && gpxPreview })
        val ok = try {
            ui.bind()
        } catch (e: Exception) {
            LogBus.log("[HUD] bind map failed: $e")
            false
        } catch (oom: OutOfMemoryError) {
            LogBus.log("[HUD] bind map OOM: $oom")
            false
        }
        if (!ok) {
            gpxPreview = false
            try { ui.release() } catch (_: Exception) {}
            gpxHost.removeAllViews()
            Toast.makeText(this, "Could not load map — try again from Map hub", Toast.LENGTH_LONG).show()
            return
        }
        gpxUi = ui
        boundSessionKey = sessionKey()
        surface.visibility = View.GONE
        gpxHost.visibility = View.VISIBLE
        hint.visibility = View.GONE
        // Map chrome has its own search / Hub / close — hide Dash topbar so search
        // isn't crushed under the punch-hole + app bar.
        findViewById<View>(R.id.hud_topbar).visibility = View.GONE
        findViewById<View>(R.id.hud_map_hub).visibility = View.GONE
        findViewById<View>(R.id.hud_controls).visibility = View.GONE
        findViewById<View>(R.id.hud_toggle_controls).visibility = View.GONE
        // Waze-style: ride immersive — no system status bar eating the map.
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        titleView.text = when (GpxSession.mode) {
            GpxSession.Mode.GPX -> "GPX · ${GpxSession.trackName}"
            GpxSession.Mode.NAV_TO -> "Nav · ${GpxSession.trackName}"
            GpxSession.Mode.FREE_RIDE -> "Map preview"
        }
        LogBus.log("[HUD] Map preview bound (${GpxSession.mode} '${GpxSession.trackName}')")
    }

    private fun stopGpxPreview() {
        if (!gpxPreview && gpxUi == null) {
            boundSessionKey = null
            return
        }
        gpxPreview = false
        boundSessionKey = null
        try { gpxUi?.release() } catch (_: Exception) {}
        gpxUi = null
        gpxHost.removeAllViews()
        gpxHost.visibility = View.GONE
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun cycleMapTheme() {
        val theme = NightPrefs.cycle(this)
        val on = NightPrefs.isNightNow(this)
        AaVideoBridge.nightSink?.invoke(on)
        gpxUi?.applyTheme()
        val extra = if (theme == MapTheme.AUTO) " (${if (on) "night" else "day"} now)" else ""
        Toast.makeText(this, getString(R.string.setup_toast_map_theme, getString(theme.labelRes)) + extra, Toast.LENGTH_SHORT).show()
    }

    private fun toggleControls() {
        if (gpxPreview) return
        val bar = findViewById<View>(R.id.hud_controls)
        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        findViewById<View>(R.id.hud_topbar).visibility = if (on) View.GONE else View.VISIBLE
        if (!gpxPreview) {
            findViewById<View>(R.id.hud_controls).visibility = if (on) View.GONE else View.VISIBLE
        }
        if (on) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "Fullscreen — press Back to exit", Toast.LENGTH_SHORT).show()
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun onSurfaceTouch(e: MotionEvent) {
        if (gpxPreview) return
        val sink = AaVideoBridge.previewTouchSink
        if (sink == null) {
            val now = System.currentTimeMillis()
            if (now - noSessionToastAt > 3000) {
                noSessionToastAt = now
                Toast.makeText(this, "Connect to the bike first", Toast.LENGTH_SHORT).show()
            }
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                forward(sink, e, e.actionIndex, AaInput.ACTION_DOWN)
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until e.pointerCount) forward(sink, e, i, AaInput.ACTION_MOVE)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                forward(sink, e, e.actionIndex, AaInput.ACTION_UP)
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until e.pointerCount) forward(sink, e, i, AaInput.ACTION_UP)
        }
    }

    private fun forward(
        sink: (Int, Int, Int, Int) -> Unit,
        e: MotionEvent,
        index: Int,
        action: Int,
    ) {
        val src = mapToSource(e.getX(index), e.getY(index)) ?: return
        sink(action, e.getPointerId(index), src.first, src.second)
    }

    private fun mapToSource(vx: Float, vy: Float): Pair<Int, Int>? {
        val vw = surface.width
        val vh = surface.height
        val sw = BikeProfileHolder.aaUsableWidth
        val sh = BikeProfileHolder.aaUsableHeight
        if (vw == 0 || vh == 0 || sw == 0 || sh == 0) return null

        val srcAspect = sw.toFloat() / sh
        val viewAspect = vw.toFloat() / vh
        val rectW: Int
        val rectH: Int
        if (srcAspect < viewAspect) {
            rectH = vh; rectW = Math.round(vh * srcAspect)
        } else {
            rectW = vw; rectH = Math.round(vw / srcAspect)
        }
        val rectX = (vw - rectW) / 2
        val rectY = (vh - rectH) / 2

        val rx = vx - rectX
        val ry = vy - rectY
        if (rx < 0 || ry < 0 || rx >= rectW || ry >= rectH) return null
        val sx = (rx * sw / rectW).toInt().coerceIn(0, sw - 1)
        val sy = (ry * sh / rectH).toInt().coerceIn(0, sh - 1)
        return sx to sy
    }

    private fun key(code: Int) {
        if (gpxPreview) {
            Toast.makeText(this, "AA controls need a bike connection", Toast.LENGTH_SHORT).show()
            return
        }
        val sink = AaVideoBridge.keySink
        if (sink == null) {
            Toast.makeText(this, "Connect to the bike first", Toast.LENGTH_SHORT).show()
        } else {
            sink(code)
        }
    }

    private fun scroll(delta: Int) {
        if (gpxPreview) {
            Toast.makeText(this, "AA controls need a bike connection", Toast.LENGTH_SHORT).show()
            return
        }
        val sink = AaVideoBridge.scrollSink
        if (sink == null) {
            Toast.makeText(this, "Connect to the bike first", Toast.LENGTH_SHORT).show()
        } else {
            sink(delta)
        }
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    companion object {
        private const val REQ_MIC = 72
        const val EXTRA_GPX_PREVIEW = "gpx_preview"
        const val EXTRA_FORCE_RELOAD = "gpx_force_reload"

        fun startGpxPreview(ctx: android.content.Context) {
            ctx.startActivity(
                Intent(ctx, HudViewActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_GPX_PREVIEW, true)
                    putExtra(EXTRA_FORCE_RELOAD, true)
                },
            )
        }

        /**
         * Open the live in-app "Dash view": the Android Auto video mirrored onto the phone (Google Maps
         * / Waze as projected to the bike dash), with the on-screen controls. No extras = live-AA mode.
         * When nothing is projecting it shows a hint. Reachable from the cockpit only in AA mode.
         */
        fun start(ctx: android.content.Context) {
            ctx.startActivity(
                Intent(ctx, HudViewActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
            )
        }
    }
}
