// SPDX-License-Identifier: AGPL-3.0-or-later
// "Pair the bike once, then connect on its own." CompanionDeviceManager associates the bike by its
// Wi-Fi SSID; once associated the system can wake the app when the bike is near (CockpitCompanionService)
// and joining the SoftAP / enabling Bluetooth no longer needs a prompt. Needs Android 13+ for the
// Wi-Fi filter; older versions fall back to foreground auto-connect. Device validation pending.
package dev.zanderp.opencfmoto.connection

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.annotation.RequiresApi
import dev.zanderp.opencfmoto.LogBus
import java.util.concurrent.Executor
import java.util.regex.Pattern

object AutoConnectManager {

    /** Silent Wi-Fi/BT association is available (Android 13+ with a CompanionDeviceManager). */
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && context.getSystemService(CompanionDeviceManager::class.java) != null

    /**
     * Start associating a bike whose SoftAP SSID matches [ssidRegex] (e.g. "CFMOTO.*" or the exact
     * SSID). The system shows a one-time device picker: launch [onIntentSender] from an Activity
     * result launcher. On success the association is created and presence observation begins.
     */
    @RequiresApi(33)
    fun associate(activity: Activity, ssidRegex: String, onIntentSender: (IntentSender) -> Unit) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return
        val filter = WifiDeviceFilter.Builder().setNamePattern(Pattern.compile(ssidRegex)).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        val direct = Executor { it.run() }
        cdm.associate(request, direct, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onIntentSender(intentSender)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                LogBus.log("[CDM] associated id=${associationInfo.id}")
                // Presence observation for a Wi‑Fi-SSID association needs the ObservingDevicePresenceRequest
                // API (Android 15+). On 13/14 the association is created (usable once the OS updates) but
                // the OS won't wake us — the foreground cockpit fallback + classic maybeAutoConnect cover it.
                if (Build.VERSION.SDK_INT >= 35) startObserving(activity, associationInfo.id)
                else LogBus.log("[CDM] background wake needs Android 15+ — using foreground auto-connect for now")
            }
            override fun onFailure(error: CharSequence?) { LogBus.log("[CDM] associate failed: $error") }
        })
    }

    /**
     * Ask the system to wake us (bind [CockpitCompanionService]) when this association's device appears
     * / disappears. Uses the [ObservingDevicePresenceRequest] API by associationId — added in Android 15
     * (API 35), which is the first release that supports presence for a Wi‑Fi (non-Bluetooth-MAC)
     * association. Needs the REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE permission (in the manifest).
     */
    @RequiresApi(35)
    fun startObserving(context: Context, associationId: Int) {
        try {
            val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
            val req = ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build()
            cdm.startObservingDevicePresence(req)
            LogBus.log("[CDM] observing presence id=$associationId")
        } catch (e: Exception) {
            LogBus.log("[CDM] observe failed: ${e.message}")
        }
    }

    /** Stop presence observation for [associationId] (e.g. when the rider forgets a bike). Best-effort. */
    @RequiresApi(35)
    fun stopObserving(context: Context, associationId: Int) {
        try {
            val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
            val req = ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build()
            cdm.stopObservingDevicePresence(req)
            LogBus.log("[CDM] stopped observing id=$associationId")
        } catch (e: Exception) {
            LogBus.log("[CDM] stop-observe failed: ${e.message}")
        }
    }

    /** Ids of bikes already associated on this phone. */
    @RequiresApi(33)
    fun associations(context: Context): List<Int> = try {
        context.getSystemService(CompanionDeviceManager::class.java)?.myAssociations?.map { it.id } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}
