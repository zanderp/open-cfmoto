// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * On-screen way to drive Android Auto without touching the dash: a "Navigate to…" box, an on-screen
 * D-pad + rotary knob (forwarded over the AAP INPUT channel via [AaVideoBridge.keySink]/[scrollSink]),
 * and the toggle + entry point for handlebar-button control ([MediaButtonBridge]/[ButtonMappingActivity]).
 *
 * Fullscreen pad mode expands the pad for gloved use while riding and holds [FLAG_KEEP_SCREEN_ON]
 * so the phone doesn't dim or lock mid-ride.
 */
class ControlsActivity : AppCompatActivity() {

    private var volumeSeek: SeekBar? = null
    private var volumeValue: TextView? = null
    private var syncingVolumeUi = false
    private var padFullscreen = false
    private lateinit var insetsController: WindowInsetsControllerCompat
    private lateinit var btnPadFullscreen: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_controls)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.controls_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        insetsController = WindowInsetsControllerCompat(window, findViewById(R.id.controls_root)).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (padFullscreen) setPadFullscreen(false) else finish()
            }
        })

        // Navigate-to box.
        val nav = findViewById<EditText>(R.id.et_nav)
        findViewById<MaterialButton>(R.id.btn_nav_go).setOnClickListener {
            val dest = nav.text?.toString()?.trim().orEmpty()
            if (dest.isEmpty()) {
                Toast.makeText(this, "Type a destination first", Toast.LENGTH_SHORT).show()
            } else {
                NavLauncher.navigate(this, dest, LogBus::log)
            }
        }

        // Volume slider — above the pad so riders can still set media/nav loudness when the bike's
        // ▲/▼ are hijacked for Android Auto navigation.
        volumeSeek = findViewById(R.id.seek_volume)
        volumeValue = findViewById(R.id.tv_volume_value)
        volumeSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                volumeValue?.text = progress.toString()
                if (!fromUser || syncingVolumeUi) return
                MediaButtonBridge.setVolume(this@ControlsActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        syncVolumeUi()

        // On-screen pad.
        btnPadFullscreen = findViewById(R.id.btn_pad_fullscreen)
        btnPadFullscreen.setOnClickListener { setPadFullscreen(!padFullscreen) }
        findViewById<View>(R.id.btn_knob_back).setOnClickListener { scroll(-1) }
        findViewById<View>(R.id.btn_knob_fwd).setOnClickListener { scroll(+1) }
        findViewById<View>(R.id.btn_dpad_up).setOnClickListener { key(AaInput.KEY_UP) }
        findViewById<View>(R.id.btn_dpad_down).setOnClickListener { key(AaInput.KEY_DOWN) }
        findViewById<View>(R.id.btn_dpad_left).setOnClickListener { key(AaInput.KEY_LEFT) }
        findViewById<View>(R.id.btn_dpad_right).setOnClickListener { key(AaInput.KEY_RIGHT) }
        findViewById<View>(R.id.btn_select).setOnClickListener { key(AaInput.KEY_ENTER) }
        findViewById<View>(R.id.btn_back).setOnClickListener { key(AaInput.KEY_BACK) }
        findViewById<View>(R.id.btn_home).setOnClickListener { key(AaInput.KEY_HOME) }
        findViewById<View>(R.id.btn_assistant).setOnClickListener {
            ensureMicPermission()
            key(AaInput.KEY_ASSISTANT)
        }

        // Touchscreen dashes (800MT, 1000 MT-X) run Android Auto's touch UI, which has no focus
        // cursor for a rotary knob to move — so handlebar-button navigation can't work there and only
        // costs the rider their music. Warn, and let them just tap the dash. The live profile is
        // authoritative once connected; otherwise fall back to what the last connect remembered.
        AppSettings.applyToHolder(this)
        val live = BikeProfileHolder.active
        val touchDash: Boolean? = if (live !== BikeProfiles.legacy) {
            DashMemory.setLastDashTouch(this, BikeProfileHolder.advertisesScreenTouch)
            BikeProfileHolder.advertisesScreenTouch
        } else {
            DashMemory.lastDashTouch(this)
        }
        if (touchDash == true) {
            findViewById<View>(R.id.tv_touch_hint).visibility = View.VISIBLE
        }

        // Handlebar-button mode toggle (live-applied if the bridge is running).
        val sw = findViewById<MaterialSwitch>(R.id.switch_control_aa)
        sw.isChecked = ButtonMode.isControlAa(this)
        sw.setOnCheckedChangeListener { _, checked ->
            ButtonMode.set(this, checked)
            MediaButtonBridge.instance?.setCaptureActive(checked)
            LogBus.log("→ handlebar buttons ${if (checked) "control Android Auto" else "control media"}")
            if (checked) ensureMicPermission()
        }

        // ▲/▼ = navigate the AA menu (default) vs. plain volume. Live-applied via the pref setter.
        val swVol = findViewById<MaterialSwitch>(R.id.switch_handlebar_volume_nav)
        swVol.isChecked = HandlebarVolumeMode.isNavigate(this)
        swVol.setOnCheckedChangeListener { _, checked ->
            HandlebarVolumeMode.set(this, checked)
            LogBus.log("→ handlebar ▲/▼ ${if (checked) "navigate the AA menu" else "control volume"}")
        }

        findViewById<MaterialButton>(R.id.btn_customize).setOnClickListener {
            startActivity(Intent(this, ButtonMappingActivity::class.java))
        }

        // Map theme (Maps day/night) — applies live to any running AA session.
        findViewById<MaterialButton>(R.id.theme_auto).setOnClickListener { setMapTheme(MapTheme.AUTO) }
        findViewById<MaterialButton>(R.id.theme_day).setOnClickListener { setMapTheme(MapTheme.DAY) }
        findViewById<MaterialButton>(R.id.theme_night).setOnClickListener { setMapTheme(MapTheme.NIGHT) }
        highlightTheme(NightPrefs.theme(this))
    }

    override fun onResume() {
        super.onResume()
        syncVolumeUi()
    }

    /**
     * Driving mode: pad fills the screen, system bars hide, and [FLAG_KEEP_SCREEN_ON] stops the
     * phone from dimming/locking while the pad is up. Back exits fullscreen first.
     */
    private fun setPadFullscreen(on: Boolean) {
        padFullscreen = on
        val hide = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.controls_header).visibility = hide
        findViewById<View>(R.id.card_nav).visibility = hide
        findViewById<View>(R.id.card_theme).visibility = hide
        findViewById<View>(R.id.card_handlebar).visibility = hide
        findViewById<View>(R.id.pad_intro).visibility = hide
        findViewById<View>(R.id.tv_volume_hint).visibility = hide

        val content = findViewById<LinearLayout>(R.id.controls_content)
        val pad = findViewById<View>(R.id.card_pad)
        val scroll = findViewById<android.widget.ScrollView>(R.id.controls_scroll)
        scroll.isFillViewport = true

        val contentLp = content.layoutParams
        contentLp.height = if (on) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        content.layoutParams = contentLp

        val padLp = pad.layoutParams as LinearLayout.LayoutParams
        if (on) {
            padLp.height = 0
            padLp.weight = 1f
            padLp.topMargin = 0
        } else {
            padLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            padLp.weight = 0f
            padLp.topMargin = (12 * resources.displayMetrics.density).toInt()
        }
        pad.layoutParams = padLp

        // Taller hit targets for gloves while riding.
        val minH = ((if (on) 72 else 48) * resources.displayMetrics.density).toInt()
        for (id in PAD_BUTTON_IDS) {
            findViewById<View>(id).minimumHeight = minH
        }
        // Give the d-pad rows equal stretch so the pad uses the full height.
        val rowWeight = if (on) 1f else 0f
        val rowHeight = if (on) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        for (id in listOf(R.id.pad_row_knob, R.id.pad_row_mid, R.id.pad_row_down, R.id.pad_row_nav)) {
            val row = findViewById<LinearLayout>(id)
            val lp = row.layoutParams as LinearLayout.LayoutParams
            lp.height = rowHeight
            lp.weight = rowWeight
            row.layoutParams = lp
            row.gravity = android.view.Gravity.CENTER
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                if (child is MaterialButton) {
                    val clp = child.layoutParams as LinearLayout.LayoutParams
                    clp.height = if (on) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
                    child.layoutParams = clp
                }
            }
        }

        btnPadFullscreen.text = if (on) "Exit" else "Fullscreen"

        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "Pad fullscreen — screen stays on. Press Back to exit.", Toast.LENGTH_SHORT).show()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Match the SeekBar to the live music stream (and bridge pin, when capturing). */
    private fun syncVolumeUi() {
        val seek = volumeSeek ?: return
        val (now, max) = MediaButtonBridge.volumeLevels(this)
        syncingVolumeUi = true
        try {
            if (seek.max != max) seek.max = max
            seek.progress = now
            volumeValue?.text = now.toString()
        } finally {
            syncingVolumeUi = false
        }
    }

    private fun setMapTheme(theme: MapTheme) {
        NightPrefs.setTheme(this, theme)
        AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(this))
        highlightTheme(theme)
        Toast.makeText(this, getString(R.string.setup_toast_map_theme, getString(theme.labelRes)), Toast.LENGTH_SHORT).show()
    }

    /** Paint the selected segment in brand color; the rest stay neutral tonal. */
    private fun highlightTheme(selected: MapTheme) {
        val onColor = ContextCompat.getColor(this, R.color.brand_orange)
        val onText = ContextCompat.getColor(this, R.color.on_brand)
        val offColor = ContextCompat.getColor(this, R.color.surface_high)
        val offText = ContextCompat.getColor(this, R.color.text_primary)
        val pairs = listOf(
            R.id.theme_auto to MapTheme.AUTO,
            R.id.theme_day to MapTheme.DAY,
            R.id.theme_night to MapTheme.NIGHT,
        )
        for ((id, theme) in pairs) {
            val btn = findViewById<MaterialButton>(id)
            val on = theme == selected
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) onColor else offColor)
            btn.setTextColor(if (on) onText else offText)
        }
    }

    private fun key(code: Int) {
        val map = MapInputBridge.keySink
        if (map != null) {
            map(code)
            return
        }
        val sink = AaVideoBridge.keySink
        if (sink == null) {
            Toast.makeText(this, "Start Android Auto or Map first", Toast.LENGTH_SHORT).show()
        } else {
            sink(code)
        }
    }

    private fun scroll(delta: Int) {
        val map = MapInputBridge.scrollSink
        if (map != null) {
            map(delta)
            return
        }
        val sink = AaVideoBridge.scrollSink
        if (sink == null) {
            Toast.makeText(this, "Start Android Auto or Map first", Toast.LENGTH_SHORT).show()
        } else {
            sink(delta)
        }
    }

    /** The Assistant needs the mic to hear you — ask for it the first time voice is used. */
    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    companion object {
        private const val REQ_MIC = 71
        private val PAD_BUTTON_IDS = intArrayOf(
            R.id.btn_knob_back, R.id.btn_dpad_up, R.id.btn_knob_fwd,
            R.id.btn_dpad_left, R.id.btn_select, R.id.btn_dpad_right,
            R.id.btn_dpad_down,
            R.id.btn_back, R.id.btn_home, R.id.btn_assistant,
        )
    }
}
