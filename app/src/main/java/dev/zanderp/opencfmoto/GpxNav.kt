// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import dev.overtake.maps.model.Lane
import dev.overtake.maps.model.RouteStep

import kotlin.math.*

/** Ride maths for OpenCfMoto Map / GPX: ETA, elevation, turn and waypoint prompts on the dash. */
class GpxNav(
    private val track: GpxTrack,
    /** OSRM guidance steps for road routes; null/empty falls back to geometry turn inference. */
    private val steps: List<RouteStep>? = null,
) {
    val cumDistM: DoubleArray
    val totalM: Double
    /** Cumulative ascent (m) at each index when elevation exists; else empty. */
    val cumAscentM: DoubleArray
    val hasElevation: Boolean
    val totalAscentM: Double
    /** Track index of each guidance step's maneuver point (parallel to [steps]). */
    private val stepIndex: IntArray
    /** Last snapped index — stay on the forward leg instead of hopping to a return loop. */
    private var cursor = 0

    init {
        val n = track.points.size
        cumDistM = DoubleArray(n)
        cumAscentM = DoubleArray(n)
        var acc = 0.0
        var asc = 0.0
        var elevCount = 0
        for (i in track.points.indices) {
            if (track.points[i].ele != null) elevCount++
            if (i > 0) {
                acc += haversineM(
                    track.points[i - 1].lat, track.points[i - 1].lon,
                    track.points[i].lat, track.points[i].lon,
                )
                val e0 = track.points[i - 1].ele
                val e1 = track.points[i].ele
                if (e0 != null && e1 != null && e1 > e0) asc += (e1 - e0)
            }
            cumDistM[i] = acc
            cumAscentM[i] = asc
        }
        totalM = acc
        hasElevation = elevCount >= (n / 2).coerceAtLeast(2)
        totalAscentM = asc
        // Snap each OSRM maneuver to the nearest track point so cues can be ordered along the ride.
        stepIndex = if (steps.isNullOrEmpty() || n == 0) {
            IntArray(0)
        } else {
            IntArray(steps.size) { s ->
                val sp = steps[s]
                var bi = 0
                var bd = Double.MAX_VALUE
                for (i in track.points.indices) {
                    val d = haversineM(sp.maneuverLat, sp.maneuverLon, track.points[i].lat, track.points[i].lon)
                    if (d < bd) { bd = d; bi = i }
                }
                bi
            }
        }
    }

    enum class TurnDir {
        LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
        UTURN, ROUNDABOUT, MERGE_LEFT, MERGE_RIGHT, FORK_LEFT, FORK_RIGHT,
        STRAIGHT, ARRIVE, CONTINUE
    }

    data class TurnCue(
        val index: Int,
        val distanceAlongM: Double,
        val distanceFromRiderM: Double,
        val dir: TurnDir,
        val speak: String,
        val shortLabel: String,
        /** Spoken instruction WITHOUT any distance prefix, e.g. "turn right onto Main St". */
        val instr: String = "",
        /** Road you turn onto (OSRM steps only). */
        val roadName: String? = null,
        /** Compact lane arrows to be in, when OSRM reports turn lanes. */
        val laneLabel: String? = null,
    )

    data class Progress(
        val nearestIndex: Int,
        val doneM: Double,
        val remainM: Double,
        val offTrackM: Double,
        /** GPS position projected onto the route line (smooth, road-snapped puck). */
        val snapLat: Double,
        val snapLon: Double,
        val nextWaypoint: GpxPoint?,
        val nextWaypointM: Double?,
        val trackBearingDeg: Float,
        val upcomingTurn: TurnCue?,
        /** GPS altitude if available, else track ele at nearest point. */
        val altitudeM: Double?,
        /** Ascent still ahead on the GPX (m), when elevation present. */
        val remainAscentM: Double?,
        /** Approx grade % over the next ~150 m of track. */
        val gradePct: Double?,
        /** ETA seconds at [speedMps] (null if too slow). */
        val etaSec: Long?,
    )

    fun progress(
        lat: Double,
        lon: Double,
        speedMps: Float = 0f,
        gpsAltitudeM: Double? = null,
        units: MapUnits = MapUnits.METRIC,
    ): Progress? {
        if (track.points.isEmpty()) return null
        val n = track.points.size
        val searchFrom = (cursor - 8).coerceAtLeast(0)
        var bestI = cursor.coerceIn(0, n - 1)
        var bestD = Double.MAX_VALUE
        for (i in searchFrom until n) {
            val p = track.points[i]
            val d = haversineM(lat, lon, p.lat, p.lon)
            if (d < bestD) {
                bestD = d
                bestI = i
            }
        }
        if (bestD > 80.0) {
            for (i in track.points.indices) {
                val p = track.points[i]
                val d = haversineM(lat, lon, p.lat, p.lon)
                if (d < bestD) {
                    bestD = d
                    bestI = i
                }
            }
        } else if (bestI < cursor) {
            val backM = cumDistM[cursor] - cumDistM[bestI]
            if (backM > 40.0) bestI = cursor
        }
        cursor = bestI
        // Project onto the adjacent segments so the puck slides along the road (no hopping
        // between sparse points, no phantom "ahead/behind" jumps).
        val snap = snapToRoute(lat, lon, bestI)
        val offTrack = snap.dist
        val done = (cumDistM[bestI] + snap.along).coerceIn(0.0, totalM)
        val remain = (totalM - done).coerceAtLeast(0.0)
        val bearing = bearingAt(bestI)
        val nextWpt = nextWaypointAhead(bestI, lat, lon)
        val turn = upcomingTurn(bestI, units = units)
        val alt = gpsAltitudeM ?: track.points[bestI].ele
        val remainAsc = if (hasElevation) (totalAscentM - cumAscentM[bestI]).coerceAtLeast(0.0) else null
        val grade = gradeAhead(bestI, 150.0)
        val eta = etaSeconds(remain, speedMps)
        return Progress(
            bestI, done, remain, offTrack,
            snap.lat, snap.lon,
            nextWpt?.first, nextWpt?.second, bearing, turn,
            alt, remainAsc, grade, eta,
        )
    }

    private class Snap(val lat: Double, val lon: Double, val dist: Double, val along: Double)

    /** Project (lat,lon) onto the nearest of the two segments touching point [i]. */
    private fun snapToRoute(lat: Double, lon: Double, i: Int): Snap {
        var best = Snap(track.points[i].lat, track.points[i].lon, Double.MAX_VALUE, 0.0)
        if (track.points.size < 2) {
            return Snap(track.points[i].lat, track.points[i].lon, 0.0, 0.0)
        }
        val pairs = listOf(i - 1 to i, i to i + 1)
        for ((ai, bi) in pairs) {
            if (ai < 0 || bi > track.points.lastIndex) continue
            val a = track.points[ai]
            val b = track.points[bi]
            val mLat = 111_320.0
            val mLon = 111_320.0 * cos(Math.toRadians(a.lat))
            val bx = (b.lon - a.lon) * mLon
            val by = (b.lat - a.lat) * mLat
            val px = (lon - a.lon) * mLon
            val py = (lat - a.lat) * mLat
            val segLen2 = bx * bx + by * by
            val t = if (segLen2 <= 0.0) 0.0 else ((px * bx + py * by) / segLen2).coerceIn(0.0, 1.0)
            val projx = t * bx
            val projy = t * by
            val dist = hypot(px - projx, py - projy)
            if (dist < best.dist) {
                val plon = a.lon + projx / mLon
                val plat = a.lat + projy / mLat
                // Along-track offset from point [i] to the projection (signed for the i→i+1 leg).
                val along = if (ai == i) t * sqrt(segLen2)
                else -(1.0 - t) * sqrt(segLen2)
                best = Snap(plat, plon, dist, along)
            }
        }
        return best
    }

    fun pointsDone(index: Int): List<GpxPoint> =
        if (track.points.isEmpty()) emptyList()
        else track.points.subList(0, (index + 1).coerceIn(1, track.points.size))

    fun pointsRemain(index: Int): List<GpxPoint> =
        if (track.points.isEmpty()) emptyList()
        else track.points.subList(index.coerceIn(0, track.points.size - 1), track.points.size)

    fun upcomingTurn(
        fromIndex: Int,
        lookAheadM: Double = 900.0,
        minTurnDeg: Float = 38f,
        units: MapUnits = MapUnits.METRIC,
    ): TurnCue? {
        val stepCue = if (!steps.isNullOrEmpty()) stepCue(fromIndex, units) else null
        if (stepCue != null) return stepCue
        if (steps != null && steps.isNotEmpty()) return null // OSRM route: don't fake geometry turns
        if (fromIndex >= track.points.lastIndex) return null
        val startDist = cumDistM.getOrElse(fromIndex) { 0.0 }
        val endDist = startDist + lookAheadM
        var i = fromIndex
        var prevBrng = bearingAt(fromIndex)
        var lastSampleDist = startDist
        while (i < track.points.lastIndex && cumDistM[i] <= endDist) {
            i++
            val d = cumDistM[i]
            if (d - lastSampleDist < 40.0 && i < track.points.lastIndex) continue
            lastSampleDist = d
            val brng = bearingAt(i.coerceAtMost(track.points.lastIndex - 1))
            val delta = normalizeDelta(brng - prevBrng)
            if (abs(delta) >= minTurnDeg) {
                val dir = when {
                    delta > 100 -> TurnDir.SHARP_RIGHT
                    delta < -100 -> TurnDir.SHARP_LEFT
                    delta > 0 -> TurnDir.RIGHT
                    else -> TurnDir.LEFT
                }
                val distFromRider = (d - startDist).coerceAtLeast(0.0)
                val distLabel = formatDistance(distFromRider, units)
                val dirWord = when (dir) {
                    TurnDir.LEFT -> "turn left"
                    TurnDir.RIGHT -> "turn right"
                    TurnDir.SHARP_LEFT -> "sharp left"
                    TurnDir.SHARP_RIGHT -> "sharp right"
                    else -> "continue"
                }
                val short = when (dir) {
                    TurnDir.LEFT -> "← Left"
                    TurnDir.RIGHT -> "Right →"
                    TurnDir.SHARP_LEFT -> "←← Sharp"
                    TurnDir.SHARP_RIGHT -> "Sharp →→"
                    else -> "↑"
                }
                return TurnCue(
                    index = i,
                    distanceAlongM = d,
                    distanceFromRiderM = distFromRider,
                    dir = dir,
                    speak = "In $distLabel, $dirWord",
                    shortLabel = "$short · $distLabel",
                    instr = dirWord,
                )
            }
            prevBrng = brng
        }
        return null
    }

    /** Next OSRM maneuver ahead of [fromIndex], with real street name + lanes. */
    private fun stepCue(fromIndex: Int, units: MapUnits): TurnCue? {
        val steps = steps ?: return null
        if (steps.isEmpty() || stepIndex.isEmpty()) return null
        val startDist = cumDistM.getOrElse(fromIndex) { 0.0 }
        for (s in steps.indices) {
            val type = steps[s].maneuverType
            if (type == "depart") continue
            val idx = stepIndex[s]
            // Allow a small tolerance so a maneuver just behind the snapped point still shows.
            if (idx < fromIndex - 1) continue
            val dist = (cumDistM.getOrElse(idx) { startDist } - startDist)
            if (dist < -15.0) continue
            val step = steps[s]
            if (type == "arrive") {
                // Arrival is handled by the destination logic; skip as a turn banner.
                if (dist <= 25.0) continue else return null
            }
            val dir = dirFor(type, step.modifier)
            val distFromRider = dist.coerceAtLeast(0.0)
            val distLabel = formatDistance(distFromRider, units)
            val road = step.roadName.takeIf { it.isNotBlank() }
            val instr = instruction(type, step.modifier, road, step.exit)
            val laneLabel = laneLabel(step.lanes)
            return TurnCue(
                index = idx,
                distanceAlongM = cumDistM.getOrElse(idx) { startDist },
                distanceFromRiderM = distFromRider,
                dir = dir,
                speak = "In $distLabel, $instr",
                shortLabel = buildString {
                    append(arrowFor(dir))
                    if (road != null) append(" ").append(road)
                    append(" · ").append(distLabel)
                },
                roadName = road,
                laneLabel = laneLabel,
                instr = instr,
            )
        }
        return null
    }

    private fun gradeAhead(fromIndex: Int, windowM: Double): Double? {
        if (!hasElevation) return null
        val start = cumDistM.getOrElse(fromIndex) { 0.0 }
        val e0 = track.points[fromIndex].ele ?: return null
        var i = fromIndex
        while (i < track.points.lastIndex && cumDistM[i] - start < windowM) i++
        val e1 = track.points[i].ele ?: return null
        val dist = (cumDistM[i] - start).coerceAtLeast(1.0)
        return ((e1 - e0) / dist) * 100.0
    }

    private fun nextWaypointAhead(fromIndex: Int, lat: Double, lon: Double): Pair<GpxPoint, Double>? {
        if (track.waypoints.isEmpty()) return null
        val aheadFrom = cumDistM.getOrElse(fromIndex) { 0.0 }
        var best: Pair<GpxPoint, Double>? = null
        for (w in track.waypoints) {
            var nearestTrackI = 0
            var nearestD = Double.MAX_VALUE
            for (i in track.points.indices) {
                val p = track.points[i]
                val d = haversineM(w.lat, w.lon, p.lat, p.lon)
                if (d < nearestD) {
                    nearestD = d
                    nearestTrackI = i
                }
            }
            if (cumDistM[nearestTrackI] + 30 < aheadFrom) continue
            val dRide = haversineM(lat, lon, w.lat, w.lon)
            if (best == null || dRide < best.second) best = w to dRide
        }
        return best ?: track.waypoints.minByOrNull { haversineM(lat, lon, it.lat, it.lon) }
            ?.let { it to haversineM(lat, lon, it.lat, it.lon) }
    }

    private fun bearingAt(index: Int): Float {
        val i = index.coerceIn(0, (track.points.size - 2).coerceAtLeast(0))
        if (track.points.size < 2) return 0f
        val a = track.points[i]
        val b = track.points[(i + 1).coerceAtMost(track.points.lastIndex)]
        return bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }

    companion object {
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(p1) * cos(p2) * sin(dLon / 2).pow(2)
            return 2 * r * asin(min(1.0, sqrt(a)))
        }

        fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dLon = Math.toRadians(lon2 - lon1)
            val y = sin(dLon) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
            val brng = Math.toDegrees(atan2(y, x))
            return ((brng + 360.0) % 360.0).toFloat()
        }

        fun normalizeDelta(deg: Float): Float {
            var d = deg
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            return d
        }

        /**
         * Which way to turn (relative to [headingDeg]) to face [targetBearingDeg].
         * Used off-road: point the rider at the nearest route snap until they're back on it.
         */
        fun dirToFace(headingDeg: Float, targetBearingDeg: Float): TurnDir {
            val d = normalizeDelta(targetBearingDeg - headingDeg)
            return when {
                d > 150f || d < -150f -> TurnDir.UTURN
                d > 100f -> TurnDir.SHARP_RIGHT
                d < -100f -> TurnDir.SHARP_LEFT
                d > 35f -> TurnDir.RIGHT
                d < -35f -> TurnDir.LEFT
                d > 15f -> TurnDir.SLIGHT_RIGHT
                d < -15f -> TurnDir.SLIGHT_LEFT
                else -> TurnDir.STRAIGHT
            }
        }

        /** Short HUD label while walking/riding back onto the route. */
        fun joinRouteWord(dir: TurnDir): String = when (dir) {
            TurnDir.LEFT -> "Turn left"
            TurnDir.RIGHT -> "Turn right"
            TurnDir.SHARP_LEFT -> "Sharp left"
            TurnDir.SHARP_RIGHT -> "Sharp right"
            TurnDir.SLIGHT_LEFT -> "Bear left"
            TurnDir.SLIGHT_RIGHT -> "Bear right"
            TurnDir.UTURN -> "Turn around"
            TurnDir.STRAIGHT, TurnDir.CONTINUE -> "Go ahead"
            else -> "To route"
        }

        /** Spoken cue while off the road / joining the route. */
        fun joinRouteSpeak(dir: TurnDir): String = when (dir) {
            TurnDir.LEFT -> "turn left toward the route"
            TurnDir.RIGHT -> "turn right toward the route"
            TurnDir.SHARP_LEFT -> "sharp left toward the route"
            TurnDir.SHARP_RIGHT -> "sharp right toward the route"
            TurnDir.SLIGHT_LEFT -> "bear left toward the route"
            TurnDir.SLIGHT_RIGHT -> "bear right toward the route"
            TurnDir.UTURN -> "turn around toward the route"
            TurnDir.STRAIGHT, TurnDir.CONTINUE -> "go ahead to the route"
            else -> "head to the route"
        }

        /** Off-route distance above which we guide by compass toward the snap point. */
        const val OFF_ROUTE_GUIDE_M = 40.0

        /** Map an OSRM maneuver type+modifier to our turn direction (for icon + arrow). */
        fun dirFor(type: String, modifier: String?): TurnDir {
            val m = modifier?.lowercase()
            return when (type.lowercase()) {
                "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" ->
                    TurnDir.ROUNDABOUT
                "merge" -> if (m?.contains("left") == true) TurnDir.MERGE_LEFT else TurnDir.MERGE_RIGHT
                "fork" -> if (m?.contains("left") == true) TurnDir.FORK_LEFT else TurnDir.FORK_RIGHT
                "arrive" -> TurnDir.ARRIVE
                else -> when (m) {
                    "uturn" -> TurnDir.UTURN
                    "sharp left" -> TurnDir.SHARP_LEFT
                    "sharp right" -> TurnDir.SHARP_RIGHT
                    "slight left" -> TurnDir.SLIGHT_LEFT
                    "slight right" -> TurnDir.SLIGHT_RIGHT
                    "left" -> TurnDir.LEFT
                    "right" -> TurnDir.RIGHT
                    "straight" -> TurnDir.STRAIGHT
                    else -> TurnDir.CONTINUE
                }
            }
        }

        fun instruction(type: String, modifier: String?, road: String?, exit: Int?): String {
            val m = modifier?.lowercase()
            val onto = if (road != null) " onto $road" else ""
            return when (type.lowercase()) {
                "roundabout", "rotary", "roundabout turn" ->
                    if (exit != null) "at the roundabout take exit $exit$onto"
                    else "at the roundabout${modWord(m)?.let { ", $it" } ?: ""}$onto"
                "merge" -> "merge${modWord(m)?.let { " $it" } ?: ""}$onto"
                "fork" -> "keep ${sideWord(m) ?: "ahead"}$onto"
                "on ramp" -> "take the ramp${modWord(m)?.let { " $it" } ?: ""}$onto"
                "off ramp" -> "take the exit${modWord(m)?.let { " $it" } ?: ""}$onto"
                "end of road" -> "turn ${sideWord(m) ?: "ahead"}$onto"
                "new name", "continue" -> if (road != null) "continue onto $road" else "continue straight"
                "arrive" -> "you have arrived"
                else -> when (m) {
                    "uturn" -> "make a U-turn$onto"
                    null, "straight" -> "continue straight$onto"
                    else -> "turn ${modWord(m)}$onto"
                }
            }
        }

        private fun modWord(m: String?): String? = when (m) {
            "sharp left" -> "sharp left"
            "sharp right" -> "sharp right"
            "slight left" -> "slight left"
            "slight right" -> "slight right"
            "left" -> "left"
            "right" -> "right"
            "straight" -> "straight"
            "uturn" -> "around"
            else -> null
        }

        private fun sideWord(m: String?): String? = when {
            m == null -> null
            m.contains("left") -> "left"
            m.contains("right") -> "right"
            else -> null
        }

        fun arrowFor(dir: TurnDir): String = when (dir) {
            TurnDir.LEFT -> "←"
            TurnDir.RIGHT -> "→"
            TurnDir.SHARP_LEFT -> "↰"
            TurnDir.SHARP_RIGHT -> "↱"
            TurnDir.SLIGHT_LEFT -> "↖"
            TurnDir.SLIGHT_RIGHT -> "↗"
            TurnDir.UTURN -> "⤺"
            TurnDir.ROUNDABOUT -> "↻"
            TurnDir.MERGE_LEFT -> "⇜"
            TurnDir.MERGE_RIGHT -> "⇝"
            TurnDir.FORK_LEFT -> "⋔"
            TurnDir.FORK_RIGHT -> "⋔"
            TurnDir.STRAIGHT, TurnDir.CONTINUE -> "↑"
            TurnDir.ARRIVE -> "◉"
        }

        /** Drawable for the maneuver banner icon. */
        fun iconResFor(dir: TurnDir): Int = when (dir) {
            TurnDir.LEFT -> R.drawable.ic_man_left
            TurnDir.RIGHT -> R.drawable.ic_man_right
            TurnDir.SHARP_LEFT -> R.drawable.ic_man_sharp_left
            TurnDir.SHARP_RIGHT -> R.drawable.ic_man_sharp_right
            TurnDir.SLIGHT_LEFT -> R.drawable.ic_man_slight_left
            TurnDir.SLIGHT_RIGHT -> R.drawable.ic_man_slight_right
            TurnDir.UTURN -> R.drawable.ic_man_uturn
            TurnDir.ROUNDABOUT -> R.drawable.ic_man_roundabout
            TurnDir.MERGE_LEFT, TurnDir.MERGE_RIGHT -> R.drawable.ic_man_merge
            TurnDir.FORK_LEFT, TurnDir.FORK_RIGHT -> R.drawable.ic_man_fork
            TurnDir.STRAIGHT, TurnDir.CONTINUE -> R.drawable.ic_man_straight
            TurnDir.ARRIVE -> R.drawable.ic_man_arrive
        }

        /** Compact arrows for the lanes you should be in; null when no usable lane data. */
        fun laneLabel(lanes: List<Lane>?): String? {
            if (lanes.isNullOrEmpty()) return null
            val valid = lanes.filter { it.valid }
            val use = if (valid.isNotEmpty()) valid else return null
            val arrows = use.joinToString(" ") { laneArrow(it.indications) }
            return if (arrows.isBlank()) null else arrows
        }

        private fun laneArrow(indications: List<String>): String {
            val first = indications.firstOrNull()?.lowercase() ?: return "↑"
            return when {
                first.contains("sharp left") -> "↰"
                first.contains("sharp right") -> "↱"
                first.contains("slight left") -> "↖"
                first.contains("slight right") -> "↗"
                first.contains("uturn") -> "⤺"
                first.contains("left") -> "←"
                first.contains("right") -> "→"
                else -> "↑"
            }
        }

        fun formatKm(metres: Double): String = formatDistance(metres, MapUnits.METRIC)

        fun formatDistance(metres: Double, units: MapUnits): String = when (units) {
            MapUnits.METRIC ->
                if (metres >= 1000) String.format("%.1f km", metres / 1000.0)
                else String.format("%.0f m", metres)
            MapUnits.IMPERIAL -> {
                val miles = metres / 1609.344
                if (miles >= 0.1) String.format("%.1f mi", miles)
                else String.format("%.0f ft", metres * 3.28084)
            }
        }

        /**
         * Distance rounded to a natural spoken value ("in 300 meters", "in 1000 feet",
         * "in 1.2 kilometers") — like Google/Waze, so the pre-turn cue reads cleanly.
         */
        fun roundedSpokenDistance(metres: Double, units: MapUnits): String = when (units) {
            MapUnits.IMPERIAL -> {
                val miles = metres / 1609.344
                if (miles >= 0.2) String.format("%.1f miles", miles)
                else {
                    val ft = metres * 3.28084
                    val steps = intArrayOf(50, 100, 150, 200, 300, 500, 800, 1000, 1500)
                    val r = steps.firstOrNull { it >= ft } ?: ((Math.round(ft / 500.0) * 500).toInt())
                    "$r feet"
                }
            }
            MapUnits.METRIC -> {
                if (metres >= 1000) String.format("%.1f kilometers", metres / 1000.0)
                else {
                    val steps = intArrayOf(50, 100, 150, 200, 300, 400, 500, 700, 800)
                    val r = steps.firstOrNull { it >= metres } ?: ((Math.round(metres / 100.0) * 100).toInt())
                    "$r meters"
                }
            }
        }

        fun formatSpeed(speedKmh: Float, units: MapUnits): String = when (units) {
            MapUnits.METRIC -> String.format("%.0f", speedKmh)
            MapUnits.IMPERIAL -> String.format("%.0f", speedKmh * 0.621371f)
        }

        fun speedUnitLabel(units: MapUnits): String = when (units) {
            MapUnits.METRIC -> "km/h"
            MapUnits.IMPERIAL -> "mph"
        }

        fun formatAltitude(metres: Double, units: MapUnits): String = when (units) {
            MapUnits.METRIC -> "${metres.roundToInt()}"
            MapUnits.IMPERIAL -> "${(metres * 3.28084).roundToInt()}"
        }

        fun formatAscent(metres: Double, units: MapUnits): String = when (units) {
            MapUnits.METRIC -> "↑${metres.roundToInt()}"
            MapUnits.IMPERIAL -> "↑${(metres * 3.28084).roundToInt()}"
        }

        /**
         * Seconds to destination. When stopped / crawling we still estimate using a typical
         * motorcycle cruise (~50 km/h) so ETA never blanks to "—" while riding UI is up.
         */
        fun etaSeconds(remainM: Double, speedMps: Float): Long? {
            if (remainM <= 0.0) return 0L
            // ~1.5 m/s ≈ walking; below that use a sensible assumed cruise (50 km/h).
            val v = if (speedMps >= 1.5f) speedMps.toDouble() else 50.0 / 3.6
            return (remainM / v).roundToLong().coerceAtLeast(0L)
        }

        fun formatEta(sec: Long?): String {
            if (sec == null) return "—"
            val m = sec / 60
            return when {
                m < 60 -> "${m}m"
                else -> String.format("%dh%02dm", m / 60, m % 60)
            }
        }

        fun formatArrival(etaSec: Long?): String {
            if (etaSec == null) return "—"
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.SECOND, etaSec.toInt().coerceAtMost(48 * 3600))
            return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }

        /**
         * Compact status line under the big dash widgets.
         * Remaining distance lives in the LEFT metric — do not repeat "left X km" here.
         */
        fun formatHud(
            speedKmh: Float,
            avgKmh: Float,
            p: Progress?,
            totalM: Double,
            units: MapUnits = MapUnits.METRIC,
            showNextStop: Boolean = true,
        ): String {
            if (p == null) return ""
            val tot = formatDistance(totalM, units)
            val off = if (p.offTrackM > 40) "OFF ${formatDistance(p.offTrackM, units)}" else ""
            val grade = p.gradePct?.let { g ->
                if (abs(g) >= 2) String.format("%+d%%", g.roundToInt()) else ""
            } ?: ""
            val avg = if (avgKmh >= 1f) "avg ${formatSpeed(avgKmh, units)}" else ""
            val wpt = if (showNextStop) {
                p.nextWaypoint?.let { w ->
                    val n = w.name?.takeIf { it.isNotBlank() } ?: "wpt"
                    val d = p.nextWaypointM?.let { formatDistance(it, units) } ?: ""
                    "→ $n $d"
                } ?: ""
            } else ""
            return listOfNotNull(
                "Route · $tot".takeIf { totalM > 0 },
                off.takeIf { it.isNotEmpty() },
                grade.takeIf { it.isNotEmpty() },
                avg.takeIf { it.isNotEmpty() },
                wpt.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
        }

        /** Map zoom hint from speed. */
        /** Tighter follow zoom (closer at town speeds, still readable on highway). */
        fun zoomForSpeedKmh(speedKmh: Float): Double = when {
            speedKmh < 8f -> 17.8
            speedKmh < 20f -> 17.0
            speedKmh < 35f -> 16.2
            speedKmh < 55f -> 15.4
            speedKmh < 80f -> 14.6
            speedKmh < 110f -> 13.8
            else -> 13.0
        }
    }
}

/** Rolling ride averages for the average-speed HUD. */
class GpxRideStats {
    private var movingDistM = 0.0
    private var movingTimeMs = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastMs: Long = 0L

    fun onLocation(lat: Double, lon: Double, speedMps: Float, nowMs: Long = System.currentTimeMillis()) {
        val prevLat = lastLat
        val prevLon = lastLon
        val prevMs = lastMs
        lastLat = lat
        lastLon = lon
        lastMs = nowMs
        if (prevLat == null || prevLon == null || prevMs == 0L) return
        val dt = nowMs - prevMs
        if (dt <= 0L || dt > 10_000L) return
        if (speedMps < 1.0f) return // stopped — don't dilute average
        val d = GpxNav.haversineM(prevLat, prevLon, lat, lon)
        if (d > 80) return // GPS jump
        movingDistM += d
        movingTimeMs += dt
    }

    /** Average moving speed km/h. */
    fun avgKmh(): Float {
        if (movingTimeMs < 5_000L) return 0f
        val mps = movingDistM / (movingTimeMs / 1000.0)
        return (mps * 3.6).toFloat()
    }
}
