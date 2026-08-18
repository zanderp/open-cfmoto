// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import dev.overtake.maps.search.NominatimSearch
import dev.overtake.maps.OfflineManager
import dev.overtake.maps.render.GpxOsmdroid
import dev.overtake.maps.route.offline.OfflineAreasStore
import dev.overtake.maps.route.offline.OfflinePoiIndex
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.io.File
import java.io.FileOutputStream

/**
 * Map hub: free ride, GPX tracks, OSM search/POI, favorites, markers, history —
 * then project to the bike Presentation (no MediaProjection).
 */
class GpxActivity : AppCompatActivity() {

    private lateinit var fileLabel: TextView
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var projectBtn: MaterialButton
    private lateinit var offlineBtn: MaterialButton
    private lateinit var resultsBox: LinearLayout
    private lateinit var favBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var goParkedBtn: MaterialButton
    private lateinit var packStatus: TextView
    private lateinit var corridorBtn: MaterialButton
    private lateinit var offlineHereBtn: MaterialButton
    private lateinit var offlineTrackBtn: MaterialButton
    private lateinit var offlineAreaProgress: ProgressBar
    private lateinit var offlineList: LinearLayout
    private lateinit var offlineClearRasterBtn: MaterialButton
    private var cachedFile: File? = null
    private var displayName: String = ""
    private var parsed: GpxTrack? = null
    private var offlineReady = false
    private var corridorPois: List<MapPlace> = emptyList()
    private var offlineHighDetail = false
    private var offlineRadiusKm = 25
    private var offlineBusy = false
    // True while the download is building offline ROUTING data (so tile-progress % doesn't overwrite
    // the indeterminate "building routes" line). Mirrors OfflinePacksScreen's phase-gated progress UI.
    private var offlineRouting = false
    // The extracted offline-data manager (downloaded areas + offline routing data + raster cache).
    private val offlineManager by lazy { overtakeOffline(this) }

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) { }
        val name = queryDisplayName(uri) ?: "track.gpx"
        val dest = File(cacheDir, "gpx-import-${System.currentTimeMillis()}.gpx")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: run {
            Toast.makeText(this, "Could not read file", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val track = runCatching { GpxParser.parse(dest) }.getOrElse {
            Toast.makeText(this, "Invalid GPX: ${it.message}", Toast.LENGTH_LONG).show()
            dest.delete()
            return@registerForActivityResult
        }
        if (track.points.isEmpty() && track.waypoints.isEmpty()) {
            Toast.makeText(this, "GPX has no track points or waypoints", Toast.LENGTH_LONG).show()
            dest.delete()
            return@registerForActivityResult
        }
        cachedFile = dest
        parsed = track
        displayName = track.name.ifBlank { name }
        offlineReady = false
        val nav = GpxNav(track)
        fileLabel.text = buildString {
            append(displayName)
            append("  ·  ${track.points.size} pts · ${track.waypoints.size} wpt")
            if (nav.totalM > 0) {
                append(" · ").append(GpxNav.formatDistance(nav.totalM, MapPrefs.units(this@GpxActivity)))
            }
        }
        projectBtn.isEnabled = true
        findViewById<MaterialButton>(R.id.gpx_open_map).isEnabled = true
        offlineBtn.isEnabled = track.points.isNotEmpty()
        corridorBtn.isEnabled = track.points.isNotEmpty()
        offlineTrackBtn.isEnabled = track.points.isNotEmpty() && !offlineBusy
        corridorPois = emptyList()
        progressLabel.text = "GPX ready — Open on map, or Project to bike when connected."
        progressBar.isVisible = false
        MapPlaces.pushHistory(
            this,
            MapPlace(displayName, track.points.first().lat, track.points.first().lon, "gpx", "GPX track"),
        )
        refreshLists()
        LogBus.log("→ GPX loaded: $displayName (${track.points.size} points)")
        Toast.makeText(this, "GPX loaded — tap Open GPX on map", Toast.LENGTH_LONG).show()
    }

    private val importMapLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Could not read file", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val result = MapDataBackup.importJson(this, text)
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        if (result.ok) {
            refreshSettingsUi()
            refreshLists()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_gpx)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.gpx_activity_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + 16, b.top + 16, b.right + 16, b.bottom + 16)
            insets
        }
        GpxOsmdroid.configure(this)
        fileLabel = findViewById(R.id.gpx_file_label)
        progressLabel = findViewById(R.id.gpx_progress_label)
        progressBar = findViewById(R.id.gpx_progress)
        projectBtn = findViewById(R.id.gpx_project)
        offlineBtn = findViewById(R.id.gpx_offline)
        resultsBox = findViewById(R.id.gpx_results)
        favBox = findViewById(R.id.gpx_favorites)
        historyBox = findViewById(R.id.gpx_history)
        searchEdit = findViewById(R.id.gpx_search)
        goParkedBtn = findViewById(R.id.gpx_go_parked)
        packStatus = findViewById(R.id.gpx_pack_status)
        corridorBtn = findViewById(R.id.gpx_corridor_poi)
        offlineHereBtn = findViewById(R.id.gpx_offline_here)
        offlineTrackBtn = findViewById(R.id.gpx_offline_track)
        offlineAreaProgress = findViewById(R.id.gpx_offline_progress)
        offlineList = findViewById(R.id.gpx_offline_list)
        offlineClearRasterBtn = findViewById(R.id.gpx_offline_clear_raster)

        findViewById<MaterialButton>(R.id.gpx_free_ride).setOnClickListener { startFreeRide() }
        findViewById<MaterialButton>(R.id.gpx_see_phone).setOnClickListener { seeMapOnPhone() }
        findViewById<MaterialButton>(R.id.gpx_pick).setOnClickListener {
            pickLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
        }
        offlineBtn.setOnClickListener { downloadOffline() }
        findViewById<MaterialButton>(R.id.gpx_open_map).setOnClickListener { openGpxOnPhoneMap() }
        projectBtn.setOnClickListener { startGpxProjection() }
        findViewById<MaterialButton>(R.id.gpx_search_btn).setOnClickListener { runSearch() }
        searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(); true
            } else false
        }
        wireSettingsSegments()
        findViewById<MaterialButton>(R.id.gpx_park_here).setOnClickListener { markParkingHere() }
        goParkedBtn.setOnClickListener {
            // Walking back to the bike → navigate on the phone, not the (parked) bike screen.
            val p = MapPlaces.parked(this) ?: return@setOnClickListener
            navigateTo(p, toBike = false)
        }
        findViewById<MaterialButton>(R.id.gpx_clear_parked).setOnClickListener {
            if (MapPlaces.parked(this) == null) return@setOnClickListener
            MapPlaces.clearParked(this)
            refreshSettingsUi()
            refreshLists()
            Toast.makeText(this, "Parked spot cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.gpx_set_home_hub).setOnClickListener {
            ensureLocationPermission()
            val near = lastKnown()
            if (near == null) {
                Toast.makeText(this, "Need a GPS fix to set Home", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MapPlaces.setHome(
                this,
                MapPlace("Home", near.first, near.second, "home", "Set from Map hub"),
            )
            refreshSettingsUi()
            refreshLists()
            Toast.makeText(this, "Home saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.gpx_go_home_hub).setOnClickListener {
            val home = MapPlaces.home(this) ?: return@setOnClickListener
            navigateTo(home, toBike = true)
        }
        corridorBtn.setOnClickListener { downloadCorridorPois() }
        offlineHereBtn.setOnClickListener { downloadAreaAroundMe() }
        offlineTrackBtn.setOnClickListener { downloadAreaAroundTrack() }
        offlineClearRasterBtn.setOnClickListener { clearRasterCache() }

        val orsKeyEdit = findViewById<EditText>(R.id.gpx_ors_key)
        orsKeyEdit.setText(MapPrefs.orsApiKey(this))
        findViewById<MaterialButton>(R.id.gpx_ors_save).setOnClickListener {
            val key = orsKeyEdit.text?.toString()?.trim().orEmpty()
            MapPrefs.setOrsApiKey(this, key)
            val msg = if (key.isEmpty()) {
                "ORS key cleared — Avoid still works via free Valhalla routing"
            } else {
                "ORS key saved — stricter avoid highways/tolls enabled"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            currentFocus?.let {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
        findViewById<MaterialButton>(R.id.gpx_hub_circuit).setOnClickListener {
            MapPrefs.setRouteMode(this, RouteMode.FUN)
            GpxSession.pendingCircuit = true
            GpxSession.prepareFreeRide(overlays())
            HudViewActivity.startGpxPreview(this)
        }
        findViewById<MaterialButton>(R.id.gpx_export_map).setOnClickListener { exportMapData() }
        findViewById<MaterialButton>(R.id.gpx_import_map).setOnClickListener {
            importMapLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        val chips = findViewById<ChipGroup>(R.id.gpx_poi_chips)
        for (chip in NominatimSearch.POI_CHIPS) {
            chips.addView(
                Chip(this).apply {
                    text = chip.label
                    isClickable = true
                    isCheckable = false
                    setOnClickListener { runPoi(chip) }
                },
            )
        }
        refreshSettingsUi()
        refreshLists()
        intent.getStringExtra(EXTRA_SEARCH)?.takeIf { it.isNotBlank() }?.let { q ->
            searchEdit.setText(q)
            searchEdit.post { runSearch() }
        }
    }

    private fun wireSettingsSegments() {
        findViewById<MaterialButton>(R.id.gpx_units_metric).setOnClickListener {
            MapPrefs.setUnits(this, MapUnits.METRIC)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_units_imperial).setOnClickListener {
            MapPrefs.setUnits(this, MapUnits.IMPERIAL)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_theme_auto).setOnClickListener {
            NightPrefs.setTheme(this, MapTheme.AUTO)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_theme_day).setOnClickListener {
            NightPrefs.setTheme(this, MapTheme.DAY)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_theme_night).setOnClickListener {
            NightPrefs.setTheme(this, MapTheme.NIGHT)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_voice_on).setOnClickListener {
            MapPrefs.setVoicePrompts(this, true)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_voice_off).setOnClickListener {
            MapPrefs.setVoicePrompts(this, false)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_next_stop_on).setOnClickListener {
            MapPrefs.setShowNextStop(this, true)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_next_stop_off).setOnClickListener {
            MapPrefs.setShowNextStop(this, false)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_auto_finish_on).setOnClickListener {
            MapPrefs.setAutoFinishOnArrive(this, true)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_auto_finish_off).setOnClickListener {
            MapPrefs.setAutoFinishOnArrive(this, false)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_chrome_dark).setOnClickListener {
            MapPrefs.setChrome(this, MapChrome.DARK)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_chrome_black).setOnClickListener {
            MapPrefs.setChrome(this, MapChrome.BLACK)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_chrome_slate).setOnClickListener {
            MapPrefs.setChrome(this, MapChrome.SLATE)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_chrome_forest).setOnClickListener {
            MapPrefs.setChrome(this, MapChrome.FOREST)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_buildings_on).setOnClickListener {
            MapPrefs.setBuildings3d(this, true)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_buildings_off).setOnClickListener {
            MapPrefs.setBuildings3d(this, false)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_route_fast).setOnClickListener {
            MapPrefs.setRouteMode(this, RouteMode.FAST)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_route_fun).setOnClickListener {
            MapPrefs.setRouteMode(this, RouteMode.FUN)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_avoid_tolls_on).setOnClickListener {
            MapPrefs.setAvoidTolls(this, true)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_avoid_tolls_off).setOnClickListener {
            MapPrefs.setAvoidTolls(this, false)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_avoid_hwy_on).setOnClickListener {
            MapPrefs.setAvoidHighways(this, true)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_avoid_hwy_off).setOnClickListener {
            MapPrefs.setAvoidHighways(this, false)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_circuit_30).setOnClickListener {
            MapPrefs.setFunCircuitKm(this, 30)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_circuit_60).setOnClickListener {
            MapPrefs.setFunCircuitKm(this, 60)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_circuit_100).setOnClickListener {
            MapPrefs.setFunCircuitKm(this, 100)
            refreshSettingsUi()
        }
        findViewById<MaterialButton>(R.id.gpx_offline_detail_std).setOnClickListener {
            offlineHighDetail = false
            refreshOfflineControls()
        }
        findViewById<MaterialButton>(R.id.gpx_offline_detail_high).setOnClickListener {
            offlineHighDetail = true
            refreshOfflineControls()
        }
        findViewById<MaterialButton>(R.id.gpx_offline_radius_10).setOnClickListener {
            offlineRadiusKm = 10
            refreshOfflineControls()
        }
        findViewById<MaterialButton>(R.id.gpx_offline_radius_25).setOnClickListener {
            offlineRadiusKm = 25
            refreshOfflineControls()
        }
        findViewById<MaterialButton>(R.id.gpx_offline_radius_50).setOnClickListener {
            offlineRadiusKm = 50
            refreshOfflineControls()
        }
    }

    private fun nearestCircuitKm(km: Int): Int = when (km) {
        in 0..45 -> 30
        in 46..80 -> 60
        else -> 100
    }

    /** Paint the segment matching [selected] in brand orange; the rest stay neutral. */
    private fun <T> highlight(selected: T, vararg pairs: Pair<Int, T>) {
        val onColor = ContextCompat.getColor(this, R.color.brand_orange)
        val onText = ContextCompat.getColor(this, R.color.on_brand)
        val offColor = ContextCompat.getColor(this, R.color.surface_high)
        val offText = ContextCompat.getColor(this, R.color.text_primary)
        for ((id, value) in pairs) {
            val btn = findViewById<MaterialButton>(id)
            val on = value == selected
            btn.backgroundTintList = ColorStateList.valueOf(if (on) onColor else offColor)
            btn.setTextColor(if (on) onText else offText)
            if (id in OFFLINE_SEGMENT_IDS) {
                btn.isEnabled = !offlineBusy
                btn.alpha = if (offlineBusy) 0.45f else 1f
            }
        }
    }

    private fun refreshSettingsUi() {
        highlight(
            MapPrefs.units(this),
            R.id.gpx_units_metric to MapUnits.METRIC,
            R.id.gpx_units_imperial to MapUnits.IMPERIAL,
        )
        highlight(
            NightPrefs.theme(this),
            R.id.gpx_theme_auto to MapTheme.AUTO,
            R.id.gpx_theme_day to MapTheme.DAY,
            R.id.gpx_theme_night to MapTheme.NIGHT,
        )
        highlight(
            MapPrefs.voicePrompts(this),
            R.id.gpx_voice_on to true,
            R.id.gpx_voice_off to false,
        )
        highlight(
            MapPrefs.showNextStop(this),
            R.id.gpx_next_stop_on to true,
            R.id.gpx_next_stop_off to false,
        )
        highlight(
            MapPrefs.autoFinishOnArrive(this),
            R.id.gpx_auto_finish_on to true,
            R.id.gpx_auto_finish_off to false,
        )
        highlight(
            MapPrefs.chrome(this),
            R.id.gpx_chrome_dark to MapChrome.DARK,
            R.id.gpx_chrome_black to MapChrome.BLACK,
            R.id.gpx_chrome_slate to MapChrome.SLATE,
            R.id.gpx_chrome_forest to MapChrome.FOREST,
        )
        highlight(
            MapPrefs.buildings3d(this),
            R.id.gpx_buildings_on to true,
            R.id.gpx_buildings_off to false,
        )
        highlight(
            MapPrefs.routeMode(this),
            R.id.gpx_route_fast to RouteMode.FAST,
            R.id.gpx_route_fun to RouteMode.FUN,
        )
        highlight(
            MapPrefs.avoidTolls(this),
            R.id.gpx_avoid_tolls_on to true,
            R.id.gpx_avoid_tolls_off to false,
        )
        highlight(
            MapPrefs.avoidHighways(this),
            R.id.gpx_avoid_hwy_on to true,
            R.id.gpx_avoid_hwy_off to false,
        )
        highlight(
            nearestCircuitKm(MapPrefs.funCircuitKm(this)),
            R.id.gpx_circuit_30 to 30,
            R.id.gpx_circuit_60 to 60,
            R.id.gpx_circuit_100 to 100,
        )
        refreshOfflineControls()
        refreshOfflineList()
        corridorBtn.text = if (corridorPois.isEmpty()) {
            getString(R.string.gpx_download_roadside_poi_track)
        } else {
            getString(R.string.gpx_roadside_poi_ready, corridorPois.size)
        }
        val parked = MapPlaces.parked(this)
        goParkedBtn.isEnabled = parked != null
        goParkedBtn.text = if (parked != null) {
            getString(R.string.gpx_navigate_to_parked_name, parked.name)
        } else {
            getString(R.string.gpx_navigate_to_parked_bike)
        }
        findViewById<MaterialButton>(R.id.gpx_clear_parked).apply {
            isEnabled = parked != null
            text = getString(R.string.gpx_clear_parked_spot)
        }
        findViewById<MaterialButton>(R.id.gpx_park_here).text =
            if (parked != null) getString(R.string.gpx_update_parking_here)
            else getString(R.string.gpx_mark_parking_here)
        val home = MapPlaces.home(this)
        findViewById<MaterialButton>(R.id.gpx_go_home_hub).apply {
            isEnabled = home != null
            text = if (home != null) getString(R.string.gpx_navigate_home_name, home.name)
            else getString(R.string.gpx_navigate_home)
        }
    }

    private fun markParkingHere() {
        ensureLocationPermission()
        val near = lastKnown()
        if (near == null) {
            Toast.makeText(this, getString(R.string.gpx_need_gps_mark_parking), Toast.LENGTH_SHORT).show()
            return
        }
        val replacing = MapPlaces.parked(this) != null
        MapPlaces.setParked(
            this,
            MapPlace("Parked", near.first, near.second, "parking", "Marked from Map hub"),
        )
        refreshSettingsUi()
        refreshLists()
        Toast.makeText(
            this,
            if (replacing) getString(R.string.gpx_parking_spot_updated)
            else getString(R.string.gpx_parking_spot_saved),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Session overlays that are not re-read live from [MapPlaces] (corridor POI cache). */
    private fun overlays(): List<MapPlace> = corridorPois

    private fun downloadCorridorPois() {
        val track = parsed ?: return
        corridorBtn.isEnabled = false
        progressLabel.text = getString(R.string.gpx_downloading_roadside_poi)
        OverpassClient.corridorPoisAsync(
            track.points,
            onResult = { list ->
                runOnUiThread {
                    corridorPois = list
                    OfflinePoiIndex.setCorridorCache(list)
                    corridorBtn.isEnabled = true
                    refreshSettingsUi()
                    progressLabel.text = if (list.isEmpty()) {
                        getString(R.string.gpx_no_roadside_poi_in_corridor)
                    } else {
                        getString(R.string.gpx_roadside_poi_count, list.size)
                    }
                    Toast.makeText(this, progressLabel.text, Toast.LENGTH_LONG).show()
                }
            },
            onError = { err ->
                runOnUiThread {
                    corridorBtn.isEnabled = true
                    progressLabel.text = err
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    private fun exportMapData() {
        try {
            val json = MapDataBackup.exportJson(this)
            val dir = File(cacheDir, "map-backup").apply { mkdirs() }
            val file = File(dir, MapDataBackup.suggestedFileName())
            file.writeText(json, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.gpx_export_map_subject))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(send, getString(R.string.gpx_export_map_chooser)))
            LogBus.log("→ Map data exported: ${file.name}")
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.gpx_export_failed, e.toString()), Toast.LENGTH_LONG).show()
        }
    }

    /** Primary Start: free ride projected to the bike (own-content, no Android Auto). */
    private fun startFreeRide() {
        if (DependencyPrompt.showForConnect(this, forScan = false)) return
        ensureLocationPermission()
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        GpxSession.prepareFreeRide(overlays())
        MapPlaces.pushHistory(
            this,
            MapPlace(
                getString(R.string.gpx_free_ride_place),
                0.0,
                0.0,
                "mode",
                getString(R.string.gpx_free_ride_started),
            ),
        )
        launchSession(toBike = true)
    }

    /** See the map on the phone (no bike) — free explore. Never auto-starts a route. */
    private fun seeMapOnPhone() {
        ensureLocationPermission()
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        // Walk-back to the bike is explicit: use "Navigate to parked", not this button.
        GpxSession.prepareFreeRide(overlays())
        launchSession(toBike = false)
    }

    private fun openGpxOnPhoneMap() {
        val file = cachedFile ?: return
        ensureLocationPermission()
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        GpxSession.prepareGpx(file, displayName, overlays())
        launchSession(toBike = false)
    }

    private fun startGpxProjection() {
        val file = cachedFile ?: return
        if (DependencyPrompt.showForConnect(this, forScan = false)) return
        if (BikeMemory.lastQr(this) == null) {
            Toast.makeText(this, getString(R.string.gpx_no_bike_open_phone_map), Toast.LENGTH_LONG).show()
            openGpxOnPhoneMap()
            return
        }
        if (!offlineReady) {
            Toast.makeText(
                this,
                getString(R.string.gpx_tip_download_offline_first),
                Toast.LENGTH_LONG,
            ).show()
        }
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        GpxSession.prepareGpx(file, displayName, overlays())
        launchSession(toBike = true)
    }

    /** Navigate to a searched place. [toBike] projects to the bike; false keeps it on the phone. */
    private fun navigateTo(place: MapPlace, toBike: Boolean = true) {
        if (toBike && DependencyPrompt.showForConnect(this, forScan = false)) return
        ensureLocationPermission()
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        MapPlaces.pushHistory(this, place)
        GpxSession.prepareNavTo(place, overlays())
        launchSession(toBike = toBike)
    }

    /** Phone Dash preview by default; [toBike] joins the bike when a QR is saved. */
    private fun launchSession(toBike: Boolean) {
        val live = ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING
        if (!toBike && !live) {
            HudViewActivity.startGpxPreview(this)
            finish()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_START_GPX, true)
                putExtra(MainActivity.EXTRA_GPX_TO_BIKE, toBike)
            },
        )
        finish()
    }

    private fun downloadOffline() {
        // Compliant offline = MapLibre vector region for the track corridor (OpenFreeMap).
        downloadAreaAroundTrack()
    }

    private fun refreshOfflineControls() {
        offlineHereBtn.isEnabled = !offlineBusy
        offlineTrackBtn.isEnabled = !offlineBusy && (parsed?.points?.isNotEmpty() == true)
        offlineClearRasterBtn.isEnabled = !offlineBusy
        highlight(
            offlineHighDetail,
            R.id.gpx_offline_detail_std to false,
            R.id.gpx_offline_detail_high to true,
        )
        highlight(
            offlineRadiusKm,
            R.id.gpx_offline_radius_10 to 10,
            R.id.gpx_offline_radius_25 to 25,
            R.id.gpx_offline_radius_50 to 50,
        )
    }

    private fun refreshOfflineList() {
        val areas = offlineManager.storedAreas()
        offlineList.removeAllViews()
        val rasterMb = GpxOsmdroid.rasterCacheBytes(this) / (1024 * 1024)
        offlineClearRasterBtn.text = getString(R.string.gpx_clear_cached_bike_tiles_mb, rasterMb)
        if (areas.isEmpty()) {
            offlineList.addView(TextView(this).apply {
                text = getString(R.string.gpx_no_offline_areas_yet)
                setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
                textSize = 13f
            })
        } else {
            for (a in areas) offlineList.addView(offlineRow(a))
        }
        // Fill in real vector sizes asynchronously.
        offlineManager.areaSizes { sizes ->
            runOnUiThread {
                offlineList.removeAllViews()
                if (areas.isEmpty()) {
                    offlineList.addView(TextView(this).apply {
                        text = getString(R.string.gpx_no_offline_areas_yet)
                        setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
                        textSize = 13f
                    })
                } else {
                    for (a in areas) offlineList.addView(offlineRow(a, sizes[a.name] ?: 0L))
                }
            }
        }
    }

    private fun offlineAreaDetailText(
        quality: String,
        area: OfflineAreasStore.Area,
        vectorMb: Long,
    ): String = when {
        area.vector && area.raster ->
            getString(R.string.gpx_offline_area_detail_vector_raster, quality, vectorMb)
        area.vector ->
            getString(R.string.gpx_offline_area_detail_vector, quality, vectorMb)
        area.raster ->
            getString(R.string.gpx_offline_area_detail_raster, quality)
        else ->
            getString(R.string.gpx_offline_area_detail, quality)
    }

    private fun offlineRow(a: OfflineAreasStore.Area, vectorBytes: Long = 0L): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply {
            text = a.name
            setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_primary))
            textSize = 15f
        })
        val detail = if (a.zoomMax >= OfflineAreasStore.AREA_ZOOM_HIGH_MAX) {
            getString(R.string.gpx_high)
        } else {
            getString(R.string.gpx_standard)
        }
        val vmb = vectorBytes / (1024 * 1024)
        row.addView(TextView(this).apply {
            text = offlineAreaDetailText(detail, a, vmb)
            setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
            textSize = 12f
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.gpx_delete)
            textSize = 12f
            isEnabled = !offlineBusy
            setOnClickListener { deleteOfflineArea(a) }
        })
        row.addView(actions)
        return row
    }

    private fun deleteOfflineArea(a: OfflineAreasStore.Area) {
        // The library removes the MapLibre regions, the offline routing data (BRouter .rd5 + Overpass
        // graph) and the registry entry in one call, so the classic hub deletes exactly what the
        // native OfflinePacksScreen does.
        offlineManager.delete(a.name) { ok ->
            runOnUiThread {
                refreshOfflineList()
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.gpx_offline_area_deleted, a.name)
                    else getString(R.string.gpx_offline_area_partial_delete),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun clearRasterCache() {
        if (offlineBusy) return
        GpxOsmdroid.clearRasterCache(this)
        refreshOfflineList()
        Toast.makeText(this, getString(R.string.gpx_cached_bike_tiles_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun downloadAreaAroundMe() {
        ensureLocationPermission()
        val near = lastKnown()
        if (near == null) {
            Toast.makeText(this, getString(R.string.gpx_need_gps_pick_area), Toast.LENGTH_SHORT).show()
            return
        }
        val (lat, lon) = near
        val dLat = offlineRadiusKm / 111.32
        val dLon = offlineRadiusKm / (111.32 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val name = getString(R.string.gpx_near_me_area_name, offlineRadiusKm)
        downloadOfflineArea(name, lat + dLat, lat - dLat, lon + dLon, lon - dLon)
    }

    private fun downloadAreaAroundTrack() {
        val track = parsed ?: return
        if (track.points.isEmpty()) return
        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        for (p in track.points) {
            if (p.lat > north) north = p.lat
            if (p.lat < south) south = p.lat
            if (p.lon > east) east = p.lon
            if (p.lon < west) west = p.lon
        }
        // Pad ~2 km so junctions just off the line are covered.
        val pad = 2.0 / 111.32
        val name = "GPX · ${displayName.take(24)}"
        downloadOfflineArea(name, north + pad, south - pad, east + pad, west - pad)
    }

    /**
     * Google-style offline: download the MapLibre vector region (OpenFreeMap) for this bbox, then the
     * offline routing data — driven through the shared library engine ([OfflineManager]) so the
     * classic hub and the native OfflinePacksScreen run the identical download + route-build + persist
     * flow. The library persists the area to the registry on tile success and reports whether offline
     * routing data was obtained (`routed`); the UI here is unchanged.
     */
    private fun downloadOfflineArea(
        name: String,
        north: Double,
        south: Double,
        east: Double,
        west: Double,
    ) {
        if (offlineBusy) return
        offlineBusy = true
        offlineRouting = false
        refreshOfflineControls()
        offlineAreaProgress.isVisible = true
        offlineAreaProgress.isIndeterminate = true
        progressLabel.text = getString(R.string.gpx_downloading_offline_map, name)

        offlineManager.download(
            name,
            OfflineManager.Bbox(north, south, east, west),
            offlineHighDetail,
            onPhase = { phase ->
                runOnUiThread {
                    when (phase) {
                        OfflineManager.Phase.ROUTING -> {
                            offlineRouting = true
                            offlineAreaProgress.isIndeterminate = true
                            progressLabel.text = getString(R.string.gpx_offline_map_building_routing)
                        }
                        OfflineManager.Phase.TILES -> offlineRouting = false
                    }
                }
            },
            onProgress = { percent, bytes ->
                runOnUiThread {
                    // While building routes we keep the indeterminate "building routing" line.
                    if (!offlineRouting) {
                        offlineAreaProgress.isIndeterminate = false
                        offlineAreaProgress.max = 100
                        offlineAreaProgress.progress = percent.coerceIn(0, 100)
                        progressLabel.text = getString(
                            R.string.gpx_offline_map_progress,
                            percent.coerceIn(0, 100),
                            bytes / (1024 * 1024),
                        )
                    }
                }
            },
            onDone = { ok, routed, message ->
                runOnUiThread { finishOfflineArea(name, ok, routed, message) }
            },
        )
    }

    private fun finishOfflineArea(name: String, ok: Boolean, routed: Boolean, message: String) {
        offlineBusy = false
        offlineRouting = false
        offlineAreaProgress.isVisible = false
        // On tile success the library already persisted the area; show the localized routing status.
        // On failure surface the engine's message. (offlineReady mirrors "an area is downloaded".)
        val msg = when {
            !ok -> message
            routed -> getString(R.string.gpx_offline_map_routing_ready, name)
            else -> getString(R.string.gpx_offline_map_routing_unavailable, name)
        }
        progressLabel.text = msg
        if (ok) offlineReady = true
        refreshOfflineControls()
        refreshOfflineList()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun runSearch() {
        val q = searchEdit.text?.toString().orEmpty()
        if (q.isBlank()) {
            Toast.makeText(this, getString(R.string.gpx_type_place_name), Toast.LENGTH_SHORT).show()
            return
        }
        resultsBox.removeAllViews()
        resultsBox.addView(TextView(this).apply {
            text = getString(R.string.gpx_searching)
            setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
        })
        val near = lastKnown()
        val offline = OfflinePoiIndex.search(this, q, near?.first, near?.second)
        // Prefer online location-biased results (Photon) so "Plo"/"Cons" surface major cities.
        NominatimSearch.searchAsync(
            q, near?.first, near?.second,
            onResult = { list -> runOnUiThread {
                showResults(if (list.isNotEmpty()) list else offline)
            } },
            onError = { err -> runOnUiThread {
                if (offline.isNotEmpty()) {
                    showResults(offline)
                } else {
                    resultsBox.removeAllViews()
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            } },
        )
    }

    private fun runPoi(chip: PoiChip) {
        val near = lastKnown()
        if (near == null) {
            Toast.makeText(this, getString(R.string.gpx_need_gps_nearby_poi), Toast.LENGTH_SHORT).show()
            ensureLocationPermission()
            return
        }
        resultsBox.removeAllViews()
        resultsBox.addView(TextView(this).apply {
            text = getString(R.string.gpx_finding_poi, chip.label)
            setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
        })
        val units = MapPrefs.units(this)
        fun withDistance(list: List<MapPlace>): List<MapPlace> =
            list.map { p ->
                val dM = OverpassClient.approxMetres(near.first, near.second, p.lat, p.lon)
                val sub = listOf(GpxNav.formatDistance(dM, units), p.subtitle)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                p.copy(category = chip.label, subtitle = sub)
            }.sortedBy { OverpassClient.approxMetres(near.first, near.second, it.lat, it.lon) }
        val offline = OfflinePoiIndex.nearbyCategory(
            this, chip.label, near.first, near.second,
        )
        if (offline.isNotEmpty()) {
            showResults(withDistance(offline))
            return
        }
        // Real "around me" tag search (nearest first), not a text search for the word.
        OverpassClient.nearbyByTagAsync(
            chip.query, near.first, near.second,
            onResult = { list -> runOnUiThread { showResults(withDistance(list)) } },
            onError = { err -> runOnUiThread {
                resultsBox.removeAllViews()
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            } },
        )
    }

    private fun showResults(list: List<MapPlace>) {
        resultsBox.removeAllViews()
        if (list.isEmpty()) {
            resultsBox.addView(TextView(this).apply {
                text = getString(R.string.gpx_no_results)
                setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
            })
            return
        }
        for (p in list) {
            resultsBox.addView(placeRow(p, showFav = true, showMarker = true, showGo = true))
        }
    }

    private fun refreshLists() {
        favBox.removeAllViews()
        val favs = MapPlaces.favorites(this)
        val marks = MapPlaces.markers(this)
        if (favs.isEmpty() && marks.isEmpty()) {
            favBox.addView(TextView(this).apply {
                text = getString(R.string.gpx_no_favorites_yet)
                setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
                textSize = 13f
            })
        } else {
            for (p in favs) favBox.addView(placeRow(p, showFav = false, showMarker = true, showGo = true, unfav = true))
            for (p in marks) {
                favBox.addView(placeRow(p.copy(name = "📌 ${p.name}"), showFav = true, showMarker = false, showGo = true, unmark = true, place = p))
            }
        }
        historyBox.removeAllViews()
        // Parked lives under the parking hub controls, not as a sticky history row.
        val hist = MapPlaces.history(this)
            .filter { it.category != "mode" && it.category != "parking" }
        if (hist.isEmpty()) {
            historyBox.addView(TextView(this).apply {
                text = getString(R.string.gpx_no_history_yet)
                setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
                textSize = 13f
            })
        } else {
            for (p in hist.take(12)) {
                historyBox.addView(placeRow(p, showFav = true, showMarker = true, showGo = true))
            }
        }
    }

    private fun placeRow(
        display: MapPlace,
        showFav: Boolean,
        showMarker: Boolean,
        showGo: Boolean,
        unfav: Boolean = false,
        unmark: Boolean = false,
        place: MapPlace = display,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply {
            text = display.name
            setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_primary))
            textSize = 15f
        })
        if (place.subtitle.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = place.subtitle.take(90)
                setTextColor(ContextCompat.getColor(this@GpxActivity, R.color.text_secondary))
                textSize = 12f
            })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (showGo && !(place.lat == 0.0 && place.lon == 0.0)) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.main_go)
                textSize = 12f
                setOnClickListener { navigateTo(place) }
            })
        }
        if (showFav) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "★"
                textSize = 12f
                setOnClickListener {
                    MapPlaces.addFavorite(this@GpxActivity, place)
                    Toast.makeText(this@GpxActivity, getString(R.string.gpx_favorite_saved), Toast.LENGTH_SHORT).show()
                    refreshLists()
                }
            })
        }
        if (unfav) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.gpx_remove)
                textSize = 12f
                setOnClickListener {
                    MapPlaces.removeFavorite(this@GpxActivity, place)
                    refreshLists()
                }
            })
        }
        if (showMarker) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.gpx_marker_label)
                textSize = 12f
                setOnClickListener {
                    MapPlaces.addMarker(this@GpxActivity, place)
                    Toast.makeText(this@GpxActivity, getString(R.string.gpx_marker_saved), Toast.LENGTH_SHORT).show()
                    refreshLists()
                }
            })
        }
        if (unmark) {
            actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.gpx_delete)
                textSize = 12f
                setOnClickListener {
                    MapPlaces.deletePlace(this@GpxActivity, place)
                    Toast.makeText(this@GpxActivity, getString(R.string.gpx_deleted), Toast.LENGTH_SHORT).show()
                    refreshSettingsUi()
                    refreshLists()
                }
            })
        }
        row.addView(actions)
        return row
    }

    private fun lastKnown(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return loc?.let { it.latitude to it.longitude }
    }

    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 41)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    companion object {
        const val EXTRA_SEARCH = "search_query"

        private val OFFLINE_SEGMENT_IDS = setOf(
            R.id.gpx_offline_detail_std,
            R.id.gpx_offline_detail_high,
            R.id.gpx_offline_radius_10,
            R.id.gpx_offline_radius_25,
            R.id.gpx_offline_radius_50,
        )

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, GpxActivity::class.java))
        }

        fun startSearch(ctx: Context, query: String) {
            ctx.startActivity(
                Intent(ctx, GpxActivity::class.java).putExtra(EXTRA_SEARCH, query),
            )
        }
    }
}
