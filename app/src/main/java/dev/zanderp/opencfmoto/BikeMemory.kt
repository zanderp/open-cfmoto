package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One remembered bike: the exact scanned QR, a friendly (user-editable) name, and an optional photo
 * (an absolute path to an image imported into the app's private storage).
 */
data class SavedBike(val raw: String, val name: String, val photoPath: String? = null) {
    val qr: QrData? get() = QrData.parse(raw)
}

/**
 * Remembers the bikes the user has connected to so a returning rider can reconnect with one tap
 * instead of re-scanning the dash QR every time — and can keep more than one bike paired (e.g. two
 * motorcycles) and pick between them.
 *
 * We persist the **raw QR string** per bike (not the parsed fields): [QrData.parse] reconstructs the
 * exact same [QrData], and [BikeProfiles.selectByQr] picks the same profile, so a saved reconnect is
 * byte-identical to a fresh scan. The list is stored as JSON; a legacy single-bike entry (older app
 * versions) is migrated into the list on first read.
 */
object BikeMemory {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_LIST = "bikes_json"
    private const val KEY_SELECTED = "selected_raw"

    // Legacy single-bike keys (pre-multi-device); migrated then left in place harmlessly.
    private const val KEY_RAW = "last_qr_raw"
    private const val KEY_NAME = "last_bike_name"

    // Per-bike winning Wi-Fi transport ("AP" | "P2P"), keyed by the (stable) dash SSID.
    private const val KEY_TRANSPORT_PREFIX = "transport_"

    // Per-bike projection mode ("CFMOTO" | "ANDROID_AUTO"), chosen once at pairing.
    private const val KEY_MODE_PREFIX = "mode_"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All remembered bikes, most-recently-saved first. */
    fun devices(ctx: Context): List<SavedBike> {
        migrateIfNeeded(ctx)
        val arr = runCatching { JSONArray(prefs(ctx).getString(KEY_LIST, "[]")) }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val raw = o.optString("raw"); val name = o.optString("name")
                val photo = o.optString("photo").takeIf { it.isNotBlank() }
                if (raw.isNotBlank()) add(SavedBike(raw, name.ifBlank { raw }, photo))
            }
        }
    }

    /** The bike the one-tap Connect will use: the explicitly selected one, else the most recent. */
    fun selected(ctx: Context): SavedBike? {
        val list = devices(ctx)
        if (list.isEmpty()) return null
        val sel = prefs(ctx).getString(KEY_SELECTED, null)
        return list.firstOrNull { it.raw == sel } ?: list.first()
    }

    /** Persist (or move-to-front) the QR we just connected with, and select it. Keeps any custom
     *  name/photo the rider already gave this bike. */
    fun save(ctx: Context, raw: String, qr: QrData) {
        val prior = devices(ctx).firstOrNull { it.raw == raw }
        val name = prior?.name ?: displayName(qr)
        val existing = devices(ctx).filter { it.raw != raw }
        val list = buildList {
            add(SavedBike(raw, name, prior?.photoPath))
            addAll(existing)
        }
        writeList(ctx, list)
        prefs(ctx).edit().putString(KEY_SELECTED, raw).apply()
    }

    fun select(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_SELECTED, raw).apply()
    }

    /** Give a bike a new display name (blank keeps the current one). */
    fun rename(ctx: Context, raw: String, newName: String) {
        val trimmed = newName.trim()
        writeList(ctx, devices(ctx).map {
            if (it.raw == raw && trimmed.isNotBlank()) it.copy(name = trimmed) else it
        })
    }

    /** Attach (or clear, with null) a bike's photo path. */
    fun setPhoto(ctx: Context, raw: String, path: String?) {
        writeList(ctx, devices(ctx).map { if (it.raw == raw) it.copy(photoPath = path) else it })
    }

    fun remove(ctx: Context, raw: String) {
        writeList(ctx, devices(ctx).filter { it.raw != raw })
        if (prefs(ctx).getString(KEY_SELECTED, null) == raw) {
            prefs(ctx).edit().remove(KEY_SELECTED).apply()
        }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_LIST).remove(KEY_SELECTED)
            .remove(KEY_RAW).remove(KEY_NAME)
            .apply()
    }

    /**
     * The Wi-Fi transport ("AP" | "P2P") that last produced a live link for [ssid], or null if we've
     * never connected this bike. Lets the connect path skip the transport that doesn't work on this
     * phone instead of burning its timeout every time (see [setWinningTransport]).
     */
    fun winningTransport(ctx: Context, ssid: String): String? =
        if (ssid.isBlank()) null else prefs(ctx).getString("$KEY_TRANSPORT_PREFIX$ssid", null)

    /**
     * Remember which transport just got Wi-Fi up for this bike. Some dashes advertise Wi-Fi Direct
     * (DIRECT-* SSID) yet never form a P2P group on a given phone; the app then falls back to the
     * SoftAP after a long timeout. Recording the winner makes every later connect go straight to it.
     */
    fun setWinningTransport(ctx: Context, ssid: String, transport: String) {
        if (ssid.isBlank()) return
        prefs(ctx).edit().putString("$KEY_TRANSPORT_PREFIX$ssid", transport).apply()
    }

    /**
     * The projection mode ("CFMOTO" | "ANDROID_AUTO") chosen for [ssid] when pairing, or null if not
     * chosen yet (→ the first-connect popup asks). Kept per bike, not on the dashboard.
     */
    fun bikeMode(ctx: Context, ssid: String): String? =
        if (ssid.isBlank()) null else prefs(ctx).getString("$KEY_MODE_PREFIX$ssid", null)

    fun setBikeMode(ctx: Context, ssid: String, mode: String) {
        if (ssid.isBlank()) return
        prefs(ctx).edit().putString("$KEY_MODE_PREFIX$ssid", mode).apply()
    }

    // ---- convenience accessors used across the app (selected bike) ----
    fun lastRaw(ctx: Context): String? = selected(ctx)?.raw
    fun lastQr(ctx: Context): QrData? = selected(ctx)?.qr
    fun lastBikeName(ctx: Context): String? = selected(ctx)?.name
    fun hasSaved(ctx: Context): Boolean = selected(ctx) != null

    private fun writeList(ctx: Context, list: List<SavedBike>) {
        val arr = JSONArray()
        for (b in list) {
            val o = JSONObject().put("raw", b.raw).put("name", b.name)
            b.photoPath?.let { o.put("photo", it) }
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Fold a pre-multi-device single saved bike into the list, once. */
    private fun migrateIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains(KEY_LIST)) return
        val raw = p.getString(KEY_RAW, null)
        val arr = JSONArray()
        if (!raw.isNullOrBlank()) {
            val name = p.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: raw
            arr.put(JSONObject().put("raw", raw).put("name", name))
            p.edit().putString(KEY_SELECTED, raw).apply()
        }
        p.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun displayName(qr: QrData): String =
        qr.name?.takeIf { it.isNotBlank() } ?: qr.ssid
}
