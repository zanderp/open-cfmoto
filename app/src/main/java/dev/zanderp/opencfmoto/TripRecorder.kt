// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock

/**
 * Records a ride from the phone's GPS: live speed, distance, moving time, max speed, and the full
 * track for the map. One recorder is shared process-wide via [TripLogger] so Android Auto projection,
 * the built-in Map ([TripAutoLog]), and the [TripActivity] HUD never double-count the same ride.
 *
 * Auto-segmenting: if the bike sits still longer than [IDLE_SPLIT_MS], the current leg is finalised
 * and saved and a fresh one begins — so parking mid-journey splits into sensible separate trips.
 * A leg is only saved if it covers at least [MIN_SAVE_DISTANCE_M] over [MIN_SAVE_TIME_MS] of motion,
 * filtering out GPS jitter while stopped.
 */
class TripRecorder(private val appContext: Context) : LocationListener {

    data class Stats(
        val recording: Boolean,
        val speedKmh: Int,
        val distanceMeters: Double,
        val movingTimeMs: Long,
        val maxSpeedMs: Float,
        val hasFix: Boolean,
        val accuracyM: Int,
    )

    @Volatile var onStats: ((Stats) -> Unit)? = null
    @Volatile var onTripSaved: ((Trip) -> Unit)? = null

    @Volatile var recording = false
        private set

    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val points = ArrayList<TrackPoint>()
    private var distanceMeters = 0.0
    private var movingTimeMs = 0L
    private var maxSpeedMs = 0f
    private var startTime = 0L
    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var lastMovingAt = 0L
    private var hasFix = false
    private var lastAccuracy = 0
    private var curSpeedKmh = 0

    private fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun gpsAvailable(): Boolean = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /** Begin (or resume) recording. Returns false if we lack permission or GPS is off. */
    fun start(): Boolean {
        if (recording) return true
        if (!hasPermission() || lm == null || !gpsAvailable()) return false
        resetLeg()
        recording = true
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (_: SecurityException) {
            recording = false
            return false
        }
        emit()
        return true
    }

    /** Stop recording and save the current leg if it's meaningful. */
    fun stopAndSave() {
        if (!recording) return
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        recording = false
        finalizeLeg(save = true)
        curSpeedKmh = 0
        hasFix = false
        emit()
    }

    /** Stop and throw away the current leg (the HUD "Reset"). */
    fun discard() {
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        recording = false
        resetLeg()
        curSpeedKmh = 0
        hasFix = false
        emit()
    }

    fun snapshot(): Stats = Stats(
        recording = recording,
        speedKmh = curSpeedKmh,
        distanceMeters = distanceMeters,
        movingTimeMs = movingTimeMs,
        maxSpeedMs = maxSpeedMs,
        hasFix = hasFix,
        accuracyM = lastAccuracy,
    )

    override fun onLocationChanged(location: Location) {
        if (!recording) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastFix
        val d = prev?.distanceTo(location) ?: 0f
        val dtMs = if (prev != null && lastFixAt > 0) now - lastFixAt else 0L

        var speedMs = if (location.hasSpeed()) location.speed
            else if (dtMs in 1..5000) d / (dtMs / 1000f) else 0f

        val moving = speedMs >= MIN_MOVING_MS
        if (prev != null && moving && dtMs in 1..5000) {
            distanceMeters += d
            movingTimeMs += dtMs
        }
        if (moving) lastMovingAt = now
        else if (lastMovingAt > 0 && now - lastMovingAt > IDLE_SPLIT_MS && distanceMeters > 0) {
            // Parked long enough — bank this leg and start a fresh one, staying in recording mode.
            finalizeLeg(save = true)
            resetLeg()
            speedMs = 0f
        }

        if (speedMs > maxSpeedMs) maxSpeedMs = speedMs
        curSpeedKmh = (speedMs * 3.6f).coerceAtLeast(0f).toInt()
        hasFix = true
        lastAccuracy = if (location.hasAccuracy()) location.accuracy.toInt() else 0

        AutoVolumeController.onSpeedChanged(appContext, curSpeedKmh)

        points.add(TrackPoint(location.latitude, location.longitude, System.currentTimeMillis(), speedMs))
        lastFix = location
        lastFixAt = now
        emit()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun resetLeg() {
        points.clear()
        distanceMeters = 0.0
        movingTimeMs = 0L
        maxSpeedMs = 0f
        startTime = System.currentTimeMillis()
        lastFix = null
        lastFixAt = 0L
        lastMovingAt = 0L
    }

    private fun finalizeLeg(save: Boolean) {
        if (!save) return
        if (distanceMeters < MIN_SAVE_DISTANCE_M || movingTimeMs < MIN_SAVE_TIME_MS || points.size < 2) return
        val trip = Trip(
            id = startTime.toString(),
            start = startTime,
            end = System.currentTimeMillis(),
            distanceMeters = distanceMeters,
            movingTimeMs = movingTimeMs,
            maxSpeedMs = maxSpeedMs,
            points = ArrayList(points),
        )
        TripStore.save(appContext, trip)
        LogBus.log("[trip] saved ride ${trip.distanceText()} in ${trip.durationText()}")
        try { onTripSaved?.invoke(trip) } catch (_: Exception) {}
    }

    private fun emit() {
        try { onStats?.invoke(snapshot()) } catch (_: Exception) {}
    }

    companion object {
        private const val MIN_MOVING_MS = 0.8f          // ~2.9 km/h below which we treat as stopped
        private const val IDLE_SPLIT_MS = 3 * 60_000L    // parked > 3 min → split into a new leg
        private const val MIN_SAVE_DISTANCE_M = 100.0    // don't save trivial legs
        private const val MIN_SAVE_TIME_MS = 15_000L
    }
}
