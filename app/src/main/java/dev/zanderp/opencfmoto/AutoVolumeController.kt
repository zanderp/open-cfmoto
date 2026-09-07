// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.media.AudioManager

/**
 * Handles automatic volume adjustment based on vehicle speed.
 * Hooked into [TripRecorder] or other location listeners.
 */
object AutoVolumeController {

    private var lastSpeedKmh = -1
    private var lastAppliedOffset: Int? = null

    fun onSpeedChanged(context: Context, speedKmh: Int) {
        if (!AppSettings.autoVolumeOn(context)) return
        if (speedKmh == lastSpeedKmh) return
        lastSpeedKmh = speedKmh

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val mode = AppSettings.autoVolumeMode(context) // 1 = Relative, 0 = Fixed / Absolute
        val idx = (speedKmh / 10).coerceIn(0, 14)

        if (mode == 0) { // Fixed / Absolute
            val points = AppSettings.autoVolumePointsAbsolute(context)
            val targetVol = points.getOrElse(idx) { currentVol }.coerceIn(0, maxVol)
            if (targetVol != currentVol) am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        } else { // Relative
            val points = AppSettings.autoVolumePointsRelative(context)
            val newOffset = points.getOrElse(idx) { 0 }
            val prevOffset = lastAppliedOffset
            if (prevOffset == null) {
                // First tick: just establish the baseline, no audible jump.
                lastAppliedOffset = newOffset
            } else {
                // Apply only the delta vs. the last commanded offset, on top of whatever the
                // stream volume actually reads right now — never compare against a remembered
                // absolute level. Some OEMs (observed on Samsung) echo back a stream volume that
                // differs slightly from what was set (e.g. Bluetooth AVRCP absolute-volume
                // rounding); comparing against a remembered value made that look like a manual
                // override and silently re-anchored to a no-op on every tick. Delta application
                // self-corrects regardless of why the live level drifted.
                val delta = newOffset - prevOffset
                val targetVol = (currentVol + delta).coerceIn(0, maxVol)
                if (targetVol != currentVol) am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                lastAppliedOffset = newOffset
            }
        }
    }

    fun reset() {
        lastSpeedKmh = -1
        lastAppliedOffset = null
    }
}
