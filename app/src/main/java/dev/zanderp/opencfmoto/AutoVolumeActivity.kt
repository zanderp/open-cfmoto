// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class AutoVolumeActivity : AppCompatActivity() {

    private lateinit var avSwitch: MaterialSwitch
    private lateinit var modeGroup: RadioGroup
    private lateinit var maxStepsBtn: MaterialButton
    private lateinit var pointsContainer: LinearLayout

    private var isRelativeMode: Boolean = true
    private var maxSteps: Int = 15

    private val fixedValues = mutableListOf<Int>()
    private val relativeValues = mutableListOf<Int>()

    private val valueTextViews = mutableListOf<TextView>()
    private val vuProgressBars = mutableListOf<ProgressBar>()

    private val activeList: MutableList<Int>
        get() = if (isRelativeMode) relativeValues else fixedValues

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_volume)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.av_scroll)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        avSwitch = findViewById(R.id.av_switch)
        modeGroup = findViewById(R.id.av_mode_group)
        maxStepsBtn = findViewById(R.id.av_max_steps_btn)
        pointsContainer = findViewById(R.id.av_points_container)

        avSwitch.isChecked = AppSettings.autoVolumeOn(this)
        isRelativeMode = (AppSettings.autoVolumeMode(this) == 1)

        // The curve editor's range must match the phone's real STREAM_MUSIC granularity, or
        // values entered here silently clamp to a different max at runtime (AutoVolumeController
        // always clamps against the live getStreamMaxVolume(), not this stored preset).
        val detectedMax = (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        maxSteps = detectedMax
        findViewById<TextView>(R.id.av_max_steps_detected).text =
            getString(R.string.av_max_steps_detected, detectedMax)

        if (isRelativeMode) {
            findViewById<RadioButton>(R.id.av_mode_relative).isChecked = true
        } else {
            findViewById<RadioButton>(R.id.av_mode_absolute).isChecked = true
        }

        fixedValues.addAll(AppSettings.autoVolumePointsAbsolute(this).map { it.coerceIn(0, maxSteps) })
        relativeValues.addAll(AppSettings.autoVolumePointsRelative(this).map { it.coerceIn(0, maxSteps) })

        updateMaxStepsBtn()

        maxStepsBtn.setOnClickListener {
            showMaxStepsDialog()
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            isRelativeMode = (checkedId == R.id.av_mode_relative)
            refreshAllRows()
        }

        for (i in 0..14) {
            val speed = i * 10
            addPointRow(i, speed)
        }

        // Master Gain / Preamp controls
        findViewById<MaterialButton>(R.id.av_preamp_plus).setOnClickListener {
            val list = activeList
            val maxVal = list.maxOrNull() ?: 0
            if (maxVal < maxSteps) {
                for (i in list.indices) {
                    list[i] = (list[i] + 1).coerceAtMost(maxSteps)
                }
                refreshAllRows()
            } else {
                Toast.makeText(this, "Максимумът ($maxSteps) е достигнат — кривата е запазена", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.av_preamp_minus).setOnClickListener {
            val list = activeList
            val minVal = list.minOrNull() ?: 0
            if (minVal > 0) {
                for (i in list.indices) {
                    list[i] = (list[i] - 1).coerceAtLeast(0)
                }
                refreshAllRows()
            } else {
                Toast.makeText(this, "Минимумът (0) е достигнат — кривата е запазена", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.av_save_btn).setOnClickListener {
            save()
        }
    }

    private fun showMaxStepsDialog() {
        val options = arrayOf("15 стъпки (Стандартно)", "30 стъпки (Samsung)", "50 стъпки (Samsung)", "100 стъпки (Фино)")
        val values = intArrayOf(15, 30, 50, 100)
        val currentIdx = values.indexOf(maxSteps).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.av_max_steps_title)
            .setSingleChoiceItems(options, currentIdx) { d, which ->
                maxSteps = values[which]
                updateMaxStepsBtn()
                for (i in vuProgressBars.indices) {
                    vuProgressBars[i].max = maxSteps
                }
                refreshAllRows()
                d.dismiss()
            }
            .show()
    }

    private fun updateMaxStepsBtn() {
        maxStepsBtn.text = "$maxSteps стъпки"
    }

    private fun addPointRow(index: Int, speedKmh: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }

        val label = TextView(this).apply {
            text = getString(R.string.av_speed_label, speedKmh)
            layoutParams = LinearLayout.LayoutParams((62 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(getColor(R.color.text_primary))
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val initialValue = activeList[index]
        val color = getVuColor(initialValue)

        val vuBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = maxSteps
            progress = initialValue
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@AutoVolumeActivity, R.color.vu_track))
            val lp = LinearLayout.LayoutParams(0, (10 * resources.displayMetrics.density).toInt(), 1f)
            lp.setMargins((6 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
            layoutParams = lp
        }
        vuProgressBars.add(vuBar)

        val stepperContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnSize = (38 * resources.displayMetrics.density).toInt()

        val btnMinus = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "–"
            textSize = 18f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener {
                val list = activeList
                if (list[index] > 0) {
                    list[index]--
                    updateRowUi(index)
                }
            }
        }

        val valText = TextView(this).apply {
            text = formatValueText(initialValue)
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams((38 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        valueTextViews.add(valText)

        val btnPlus = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+"
            textSize = 18f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener {
                val list = activeList
                if (list[index] < maxSteps) {
                    list[index]++
                    updateRowUi(index)
                }
            }
        }

        stepperContainer.addView(btnMinus)
        stepperContainer.addView(valText)
        stepperContainer.addView(btnPlus)

        row.addView(label)
        row.addView(vuBar)
        row.addView(stepperContainer)
        pointsContainer.addView(row)
    }

    private fun formatValueText(v: Int): String {
        return if (isRelativeMode && v > 0) "+$v" else v.toString()
    }

    private fun refreshAllRows() {
        for (i in 0..14) {
            updateRowUi(i)
        }
    }

    private fun updateRowUi(index: Int) {
        val v = activeList[index].coerceIn(0, maxSteps)
        activeList[index] = v
        val color = getVuColor(v)
        valueTextViews[index].text = formatValueText(v)
        valueTextViews[index].setTextColor(color)
        vuProgressBars[index].progress = v
        vuProgressBars[index].progressTintList = ColorStateList.valueOf(color)
    }

    private fun getVuColor(value: Int): Int {
        val greenThreshold = (maxSteps * 0.55f).toInt()
        val orangeThreshold = (maxSteps * 0.80f).toInt()
        return when {
            value <= greenThreshold -> ContextCompat.getColor(this, R.color.vu_green)
            value <= orangeThreshold -> ContextCompat.getColor(this, R.color.vu_orange)
            else -> ContextCompat.getColor(this, R.color.vu_red)
        }
    }

    private fun save() {
        AppSettings.setAutoVolumeOn(this, avSwitch.isChecked)
        AppSettings.setAutoVolumeMode(this, if (isRelativeMode) 1 else 0)
        AppSettings.setAutoVolumeMaxSteps(this, maxSteps)
        AppSettings.setAutoVolumePointsAbsolute(this, fixedValues)
        AppSettings.setAutoVolumePointsRelative(this, relativeValues)
        AutoVolumeController.reset()
        Toast.makeText(this, R.string.res_save, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, AutoVolumeActivity::class.java))
        }
    }
}
