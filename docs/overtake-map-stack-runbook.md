# Overtake — map renderer + routing engine runbook

> Decision-grade research (Aug 2026) for making Overtake open-cfmoto's offline, screen-off,
> moto-optimized map/routing hub. Constraints that decide everything: **offline** (bike Wi-Fi has no
> internet), **screen-off** (phone screen turns off while riding), **AGPL-3.0**, **moto/curvy** routing,
> and **reuse** what open-cfmoto already has (osmdroid, MapLibre offline download, `OfflineRouter`,
> `MapOfflineManager`, `OfflineRoadGraph`).

## The two layers (don't conflate them)
- **Renderer** (draws the map): osmdroid · MapLibre · Mapsforge.
- **Router** (computes the route): OSRM · Valhalla · GraphHopper · BRouter.

## Findings that flip the decision
1. **osmdroid is archived upstream** (Nov 2024, read-only, Apache-2.0). Still fine; "reuse" now means
   maintaining a frozen snapshot. If it ever blocks a new Android version → in-house fork, **not** a
   renderer swap to MapLibre (which breaks screen-off).
2. **MapLibre is GL/Choreographer-driven** → freezes with the screen off, and its incomplete GL frames
   block-artifact when encoded to the VirtualDisplay. Wrong tool for the dash.
3. **Mapsforge renders vector maps via `android.graphics.Canvas`/Bitmap** (zero GL) → feeds osmdroid's
   own Canvas `MapView` through the `osmdroid-mapsforge` adapter → **screen-off works exactly like
   raster osmdroid**. LGPL-3.0 with the Android-linking waiver (AGPL-friendly). **This is the
   vector-premium-on-the-dash answer.** ⚠️ Use `org.mapsforge:mapsforge-map-android`, NOT `org.mapsforge:vtm*`
   (VTM is the GL sibling — same screen-off risk).
4. **GraphHopper maintainers stepped back from on-device Android offline routing** (their roadmap is
   server + thin client). Mine its open `curvature.json` **idea**, don't embed the engine.
5. **Valhalla has the best native `motorcycle` costing model** but the weakest on-device Android story
   (no official AAR; historically an HTTP client). → **server-side only** (self-host at home, pre-fetch
   a route on home Wi-Fi before departure).
6. **BRouter is the only router built for offline on-device Android** — MIT, standalone Android app,
   worldwide `.rd5` segments regenerated weekly, already the offline router behind OsmAnd/Locus/c:geo.

## Recommended stack
- **Renderer → Mapsforge** (via `osmdroid-mapsforge`, layered onto the existing osmdroid `MapView`).
  MapLibre keeps its current job (offline vector download mgmt, phone-side styling) but is **not** the
  dash renderer.
- **Router → BRouter** as the new top-of-chain, fully-offline, in-process engine. Keep Valhalla
  server-side (best moto costing) for home-Wi-Fi pre-fetch; OSRM stays online fallback.
- **Owner's hypothesis ("GraphHopper + BRouter + OSM + osmdroid") = partially right:** OSM + osmdroid +
  BRouter is the strong, precedented stack (what OsmAnd/Locus/c:geo ship). GraphHopper as a *second
  on-device engine* is the weak link — take its curvature model as a design idea, re-express it as a
  BRouter `.brf` profile, skip running graphhopper-core on the phone.

## Phased runbook (AFTER "stabilize osmdroid + Google-Maps-via-AA" — DONE)
- **Phase 1 — BRouter engine, stock profile, routing only (headless, low-risk).** Vendor `brouter-core`
  (MIT) in-process; extend `MapOfflineManager`/`OfflineRoadGraph` to fetch+cache `.rd5` for the region
  already downloaded; wire BRouter as the FIRST link in `OfflineRouter`. Verify: airplane mode → route
  across a pre-downloaded region → zero network calls.
- **Phase 2 — `motorcycle_curvy.brf` profile.** Curvature/scenic weighting modeled on GraphHopper's
  `curvature.json`, corrected for 450cc motorway access (moped class is wrong). Verify: A/B vs stock on
  known-curvy roads; rider sanity-checks routes.
- **Phase 3 — Mapsforge rendering via `osmdroid-mapsforge` (the one that touches the dash pipeline).**
  Add `MapsForgeTileProvider` feeding the same osmdroid `MapView` behind a settings toggle;
  `MapOfflineManager` gains a `.map` asset type. Verify: the SAME screen-off test that validated raster
  osmdroid, PLUS panning into never-cached areas (renders from the one local `.map` file — the point).
- **Phase 4 — parked/optional: MapLibre on the dash via a self-driven render loop.** Only if Mapsforge's
  quality proves insufficient after real riding. Fights the platform (Choreographer bypass) forever.

## Two codebase checks before Phase 1 (explorador/read)
1. What the current **Valhalla / OSRM links in `OfflineRouter`** actually do today — a genuine on-device
   engine, or a call to a self-hosted/remote instance? (Decides whether BRouter is net-new offline
   capability or a replacement for something already failing offline.)
2. The current **`MapOfflineManager` / `OfflineRoadGraph`** download-manager API shape, before assuming
   the "`.rd5` / `.map` as sibling asset types" reuse seam.

## License note
All candidates combine cleanly into AGPL-3.0 (Apache-2.0 / BSD-2 / MIT one-way compatible; mapsforge
LGPL-3.0 relicensable into GPLv3-family). The ONLY thing to avoid is GraphHopper's proprietary layer
(Directions API / Kurviger package) — not `graphhopper-core`, which is Apache-2.0 and fine to read/adapt.

## Refinement: "best of both worlds" (owner, connectivity model)
The phone MOSTLY has mobile data — and the app ALREADY binds map/routing to CELLULAR while on the bike
Wi-Fi (`AppHttp.ensureCellularUplink()` pins a cellular/internet network so data isn't parked; the bike
Wi-Fi carries the projection, cellular carries the internet). `OfflineRouter` is already online-first
(ORS → Valhalla OSM.de [remote, already `motorcycle` costing] → offline Overpass graph → OSRM [remote]).
So the architecture is online-first-for-quality, offline-as-fallback — the owner's goal is the design.

Consequence: **BRouter and Mapsforge are the OFFLINE FALLBACK tier (dead zones), not the daily driver.**
The daily-driver quality lives in the online tier + a robust connectivity-aware chooser.

Grounded findings (code): Valhalla=`valhalla1.openstreetmap.de` (remote, `motorcycle` costing when
avoidHighways), OSRM=`router.project-osrm.org` (remote, driving), offline=`OfflineRoadGraph` (on-device
graph built from Overpass at download time — the weak fallback BRouter replaces),
`MapOfflineManager.downloadArea(bounds, styles, …)` = the seam to add `.rd5`/`.map` sibling assets.

### Phased execution (sequential; i18n runs in parallel)
- **F0 — connectivity-aware chooser + online moto quality.** Add an internet check so a no-internet route
  skips ORS/Valhalla/OSRM (3 remote timeouts → current hang) and goes straight to the offline engine; use
  `motorcycle` costing consistently on Valhalla. Files: `OfflineRouter`, `ValhallaRouter`, `AppHttp`.
- **F1 — BRouter offline engine** (MIT, vendored) replaces `OfflineRoadGraph`; `.rd5` fetch as a
  `MapOfflineManager` sibling; wired as the offline link in `OfflineRouter`.
- **F2 — `motorcycle_curvy.brf`** profile.
- **F3 — Mapsforge** render via `osmdroid-mapsforge`; `.map` sibling; settings toggle; screen-off test.

## Continuity across connectivity changes (owner — "Google Maps se ve igual online/offline y no cambia la ruta")
Toggling internet must NOT visibly change the map OR recompute the route. Two rules:

- **Rule A — the RENDERER choice tracks DATA COVERAGE, not live connectivity.** Within a downloaded
  `.map` region, ALWAYS render Mapsforge — identical vector look online AND offline, zero flicker when
  data toggles. Online only serves (a) downloading the region once, (b) optional live overlays (traffic).
  Areas with no `.map` fall back to online raster — a stable, coverage-based decision, never a
  per-second connectivity flip. This makes Mapsforge the **continuity renderer** (Google-Maps-style:
  download the area → same look always), not merely an offline fallback → it becomes the PRIMARY dash
  renderer, not F3's "fallback".

- **Rule B — a computed route is STICKY.** It NEVER recomputes because connectivity changed. The rider
  keeps following the fixed polyline; recompute ONLY on genuine off-route (reroute) or explicit user
  re-route. The online↔offline engine choice matters only at (re)compute time, never mid-follow.
  (Verify: today's reroute fires on off-route via GpxDashUi `offRouteSinceMs`/`lastAutoRerouteMs`, NOT on
  a network callback — keep it that way; do not add a connectivity listener that reroutes.)

Net: the online/offline split lives in the DATA layer (which `.map`/`.rd5`/engine answers a request),
never in what the rider SEES mid-ride. This reframes the plan — F3 (Mapsforge) is promoted toward the
primary renderer, and F0/F1 must guarantee route stickiness.

## Finalized stack (owner decision)
- **Renderers — BOTH full, user-selectable:** **Mapsforge = PRIMARY** (vector, offline-continuity, default)
  **+ osmdroid = FULL** (raster, kept fully available, NOT demoted to a mere fallback). F3 = a render-engine
  toggle in settings; each engine individually satisfies Continuity Rule A (same look online/offline).
- **AA providers: Google Maps + Waze** (via Android Auto).
- **Routing — online (common case, via cellular):** ORS → Valhalla (`motorcycle`, F0) → OSRM (fallback). Kept.
- **Routing — offline (safety net):** BRouter (F1) replaces the weak Overpass `OfflineRoadGraph`.
- **GraphHopper — NOT an engine here.** On-device offline is abandoned by its own maintainers; an online
  GH server is needless infra (Valhalla OSM.de already gives free online moto routing). Its only value is
  the `curvature.json` model → re-expressed as BRouter's moto-curvy `.brf` profile (F2). Design reference,
  not code.

### Phase map (updated)
- F0 (running) — connectivity-aware chooser + sticky route + consistent `motorcycle` costing.
- F1 — BRouter offline engine (replaces Overpass graph).
- F2 — `motorcycle_curvy.brf` (curvature idea from GraphHopper).
- F3 — Mapsforge PRIMARY renderer + osmdroid FULL, render-engine toggle (settings); screen-off + continuity test.

## Probe RESULT + final re-sequence (owner, after the empirical MapLibre-on-VD probe)
EMPIRICAL PROBE on the real 450NK phone (Redmi zircon), MapLibre forced onto the dash VirtualDisplay→H.264
(dev-mode harness, commit 0c5b7c8):
- **SCREEN OFF:** MapLibre KEEPS rendering (~60→~115fps, *faster* off) — does NOT freeze. Screen-off concern DISPROVEN.
- **ENCODE:** the dumped `.h264` (1024×464) is CLEAN — zero block artifacts, incl. screen-off @115fps with fast
  camera motion (3 frames inspected). The old DashMapEngine "heavy block artifacts" comment did NOT reproduce.
→ **MapLibre IS viable as the dash renderer** on this device/pipeline. My earlier "impossible/parked" framing was wrong.

**FINAL renderer sequence (owner decision):** MapLibre FIRST → reinforce osmdroid → Mapsforge LAST.
**5 providers:** 3 FREE renderers (MapLibre vector+3D · osmdroid raster · Mapsforge offline-vector) + 2 via AA
(Google Maps · Waze). All must reach the dash SCREEN-OFF (proven: MapLibre + osmdroid; Mapsforge by Canvas construction).

**CROSS-CUTTING REQUIREMENT:** the address SEARCH must be Google-Maps-class — "totalmente a la par" (powerful
autocomplete + ranking + POI). F0's D-pass fixed the local-name bias ("rincón de la flora 1"); reaching FULL parity
needs a dedicated search-parity pass (autocomplete responsiveness, POI categories, fuzzy match, ranking, recents/favs).

**Phase order now:** F-MapLibre (dash render, productionize the probe) → F-osmdroid (reinforce) → F-Mapsforge →
F2 (moto-curvy `.brf` routing) → search-parity pass → Google/Waze AA polish.

## Architecture: Overtake = the map-SUPPLIER library (factory), open-cfmoto consumes it
Overtake is a standalone Android library (github.com/Authoritt/overtake) that SUPPLIES maps/routing/search;
open-cfmoto (this fork) consumes it as a dependency. Pattern = a Kotlin **sealed interface + factory facade**
over THREE orthogonal contracts (more elegant than a classic AbstractFactory; matches the domain's 3 axes and
lets us MIX render/route/search):

- `interface MapRenderer` — draws to a `Surface` the APP provides (MapLibre · osmdroid · Mapsforge). The library
  stays free of `VirtualDisplay`/PXC; the app wires the VD/phone surface. `DashMapEngine` is already this factory
  (libre vs osm) → extracts cleanly.
- `interface Router` — connectivity-aware (online ORS/Valhalla/OSRM chain + offline BRouter), sticky route.
- `interface PlaceSearch` — Google-Maps-class address search ("a la par").

Provider as a sealed type (models the real Native-vs-AA split):
```
sealed interface MapProvider {
  data class Native(renderer: MapRenderer, router: Router, search: PlaceSearch) : MapProvider
  data object GoogleAA : MapProvider   // projected via Android Auto, not our engine
  data object WazeAA   : MapProvider
}
// OvertakeMaps.create(config): MapProvider   // the facade
```

**Path (avoid throwaway coupling):** PROVE/build the pieces in the fork now (F-MapLibre/BRouter/search — the seams
`DashMapEngine`/`OfflineRouter`/`NominatimSearch`), THEN EXTRACT them into the Overtake library behind these
contracts as a dedicated phase. Designing the contracts now = fork code builds toward them → clean extraction.
The Overtake library today holds the AA-clone readers (NowPlaying/NavGuidance/CallState); the map factory joins it.

## Extraction blueprint (arquitecto) — the execution plan
Module: NEW `:overtake-maps` (Android lib, `dev.overtake.maps`) in the Overtake repo; keeps `:overtake`
(readers) permission-light. `:brouter` copied into the Overtake repo. Fork consumes via `implementation(project(...))`.

**Renderer seam is ALREADY clean:** VD/Presentation only in comments; renderers take `(Context, ViewGroup host)`.
So `MapRenderer` = a **View-host** seam (not raw Surface). The one correction to the target arch.

**5 injectable seams (the whole coupling reduces to these):**
1. HTTP+connectivity — MOVE mechanics to `net.OvertakeHttp` (neutral); INJECT selection via
   `networkProvider: () -> Network?` (fork wires `= { AppHttp.internetNetwork() }`). `AppHttp` STAYS in the fork
   (5 non-map users); one cellular-pin manager. This is the Router's connectivity-awareness (not `ConnectionState`).
2. Logging — INJECT via `OvertakeLog.logger = { … LogBus … }` (sink already exists). `LogBus` STAYS (45 files).
3. Config — `OvertakeMapsConfig` (once) + `RouteOptions` (per request). `MapPrefs`/`SettingsStore` STAY, feed config.
4. Location — already injected (app pushes `setMe/follow`). No work.
5. View-host — `(Context, ViewGroup)` injected by the app (host lives inside the VD Presentation on the bike).

**MOVES to `:overtake-maps`:** renderers (`DashMapEngine`→facade, `MapLibreDashController`, `DashMapController`,
`GpxOsmdroid`), routers (`OfflineRouter`,`Osrm/Ors/Valhalla/Brouter`,`OfflineRoadGraph`,`FunRoutePlanner`, offline
pack/tile stack), search (`NominatimSearch`,`AndroidGeocode`,`OverpassClient`), models (`GeoPoint`(new),`MapPlace`,
`Route/RouteStep/Lane`,`RouteResult`,`RouteOptions/RouteMode`,`PoiChip`), `:brouter`.
**STAYS in the fork (do NOT move):** VideoPipeline/VD/PXC, cockpit UI (`GpxDashUi` etc.), `GpxNav` (nav-cue engine,
not a model), `GpxParser/GpxSession/GpxVoice`, `MapPlaces`/`MapPrefs`/`SettingsStore`, `AppHttp`/`LogBus`/
`ConnectionState`/`BikeProfileHolder`, `MapLibreVdProbe`.
**GpxPoint boundary:** lib owns neutral `GeoPoint`; a ~10-line adapter at the Router→`GpxNav` seam. Renderers use
`List<Pair<Double,Double>>` — no mapping.

**Staged order (each: move → contract → wire fork consume → `:app:assembleDebug` green → smoke):**
- Stage 0 — scaffolding (module + `:brouter` copy + models + `OvertakeHttp` + `OvertakeLog`/networkProvider wiring). [LIB side in flight.]
- Stage 1 — `PlaceSearch` (least coupled; `AndroidGeocode` has ZERO deps). Move search + the parity improvements.
- Stage 2 — `Router` (HTTP seam + `RouteOptions` + `:brouter`; callbacks→suspend; GpxPoint↔GeoPoint adapter).
- Stage 3 — `MapRenderer` (the View-host seam; grep-gate: `Presentation|VirtualDisplay|VideoPipeline|Pxc` = 0 in overtake-maps/src). Riskiest (MapLibre-on-VD) → last + revertible.
- Stage 4 — facade `OvertakeMaps.create` + `MapProvider.Native|GoogleAA|WazeAA` (Google/Waze reuse `NavGuidance` + launch the app).
**Reversibility:** stages least→most coupled; each ends green; revert = drop the module dep + restore the fork copy.

## Extraction COMPLETE — as-built (autonomous session, Aug 18 2026)

**Status: DONE (compile-green + agent-verified; runtime on the bike is owner-gated).**
Overtake is now open-cfmoto's map-SUPPLIER library and the fork consumes it. The blueprint above shipped
with these deltas from plan:

- **Sequencing reversed by the probe.** The plan feared MapLibre-on-VirtualDisplay (block artifacts,
  screen-off freeze — see "Findings" §2). The empirical probe (§"Probe RESULT") **refuted it**:
  MapLibre-on-VD renders clean, screen-off, ~60→115fps, no artifacts. So MapLibre landed **first and as
  the default renderer** (`RendererKind.MAPLIBRE`), then osmdroid was reinforced, then Mapsforge — the
  owner's final order ("primero maplibre, luego reforzar osmdroid, por último mapsforge").

- **Two repos, composite build.** Overtake (`E:\Desarrollo\Activos\overtake`, module `:overtake-maps`,
  group `dev.overtake`) is a SEPARATE Gradle project; the fork wires it with
  `includeBuild("../overtake")` + `implementation("dev.overtake:overtake-maps:0.1.0-dev")` (NOT
  `project(:…)` — different repos). Both builds stay green independently.

- **The 4 contracts, all populated for real** (no stubs): `MapRenderer` (DashMapEngine → MapLibre |
  osmdroid | Mapsforge; `attach(context, host)` View-host seam, so VirtualDisplay/Presentation/PXC stay
  in the FORK — grep-gate `Presentation|VirtualDisplay|VideoPipeline|Pxc` = 0 in the lib src, PASSED),
  `Router` (RouterChain: ORS→Valhalla-`motorcycle`→OSRM online + BRouter/OfflineRoadGraph offline;
  connectivity chooser + **sticky route**), `PlaceSearch` (Photon+Nominatim+Geocoder+Overpass merge,
  Google-class ranking), `OfflineManager` (areas/`.rd5`/tile cache/POI index).

- **`MapProvider` sealed** = `Native(renderer,router,search)` | `GoogleAA` | `WazeAA`. Google AND Waze
  route to the dash **via Android Auto** (`DashboardScreen.connectMode` = AA for GOOGLE||WAZE; the
  "sin-AA" projection is the investigated-impossible MURO — the Native renderer is the AA-free path).

- **F2 moto routing**: BRouter `motorcycle_curvy.brf` is the default profile — JVM route sweep proved it
  fixes the 2× detour (Reykjavík→Keflavík 100.8km→48.9km) and biases toward curvy roads.

**Commits (by lane, both repos):**
- Overtake `feat/overtake-maps`: 9cb4abf(1 search) 8ed17b9(2a routers) e95d760(2b offline) c7e7c0d(3
  render) 2d974bc(4a Mapsforge) 5d618a5(4b moto profile).
- Fork `feat/cockpit-redesign`: 0956715(0b consume) 58815e6(1) 83c10b0(2a) 8b08667(2b) a2cf33e(3)
  2e59e2a(4a Mapsforge renderer picker) 55c3740(4c Waze-por-AA).

**Delivery**: build the composite from the cockpit worktree (`assembleDebug`) and install on the phone.
adb dropped mid-session (phone off-LAN + PIN); the owner re-pairs and re-tests on the bike.

**Bike-gated (NOT yet verified — need the 450NK + re-paired adb):** MapLibre/Mapsforge on the real HUD;
Mapsforge screen-off; the moto-curvy profile on real rides; Google/Waze projecting via AA; the whole
extracted APK's end-to-end runtime; offline e2e.

**Micro-follows (non-blocking):** Mapsforge `.map` per-region auto-download UX (today it drops to
`filesDir/mapsforge/` with a graceful no-`.map` fallback); consolidate the Overpass throttle;
`osmTileSource` config is dormant.
