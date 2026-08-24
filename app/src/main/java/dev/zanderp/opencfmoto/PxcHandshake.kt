package dev.zanderp.opencfmoto

import org.json.JSONObject
import java.net.Socket
import java.util.UUID

/**
 * Server-side PXC control dispatcher. The phone is the SERVER: after we send the
 * ECP_PXC_MDNS_RESPOND probe to the bike, the bike connects back to our listening
 * ports and drives the handshake. We reply per cmd.
 *
 * Verified against cfmoto-tcp-v5.log (official app, standard Car PXC, JSON CLIENT_INFO):
 *   bike 0x10000 (CAR_CTRL select)  -> we 0x10001
 *   bike 0x20000 (CAR_DATA select)  -> we 0x20001
 *   bike 0x10010 CLIENT_INFO (JSON) -> we 0x10011 (our info + RSA pubkey + signed HUID)
 *   bike 0x10690 {usbSpeed,wifiSpeed} -> we 0x10691
 *   bike 0x103e0 {client_set,sn}    -> we 0x103e1, then 0x201c0 CHECK_SN_RESULT {isOk:true}
 *   bike 0x70000000 heartbeat        -> we 0x70000001
 */
class PxcHandshake(
    private val log: (String) -> Unit,
) {
    private val phoneUuid: String = UUID.randomUUID().toString()
    @Volatile var carHuid: String? = null
        private set
    @Volatile var lastClientInfo: JSONObject? = null
        private set
    /** Dashboard-variant strategy, selected from the bike's CLIENT_INFO. Read by the media plane too
     *  (via EasyConnProber.handshake.profile). Written once on the control thread, before any media
     *  socket exists. */
    @Volatile var profile: BikeProfile = BikeProfiles.legacy
        private set
    /** Rate-limit HU_TIME_SYNC log lines (bike sends ~every 2s). */
    @Volatile private var lastHuTimeSyncLogAt: Long = 0L
    @Volatile private var huTimeSyncCount: Int = 0

    /**
     * Called when the bike selects a PXC channel on a :10922 socket (CAR_CTRL or CAR_DATA).
     * [EasyConnProber] starts a proactive 0x70000000 heartbeat on **each** of those sockets —
     * the 800NK (sdk 0.9.23.x) tears the whole session down after ~7s of silence on either one.
     */
    @Volatile var onPxcChannelSelected: ((Socket, String) -> Unit)? = null

    /** Dispatch one inbound frame on a given socket (ctrl or media). */
    fun handle(tag: String, frame: PxcFrame, socket: Socket) {
        val out = socket.getOutputStream()
        when (frame.cmd) {
            PxcFrame.CMD_CHANNEL_CAR_CTRL -> {
                log("[$tag] bike selected CAR_CTRL (0x10000) → ack 0x10001")
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_CTRL + 1, ByteArray(0)).write(out)
                onPxcChannelSelected?.invoke(socket, "CAR_CTRL")
            }
            PxcFrame.CMD_CHANNEL_CAR_DATA -> {
                log("[$tag] bike selected CAR_DATA (0x20000) → ack 0x20001")
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_DATA + 1, ByteArray(0)).write(out)
                onPxcChannelSelected?.invoke(socket, "CAR_DATA")
            }
            PxcFrame.CMD_CLIENT_INFO -> onClientInfo(tag, frame, out)
            PxcFrame.CMD_QUERY_SPEED -> {
                log("[$tag] QUERY_SPEED ${frame.payload.asText()} → reply 0x10691")
                PxcFrame(PxcFrame.CMD_QUERY_SPEED_RLY, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_CHECK_SN -> onCheckSn(tag, frame, out)
            PxcFrame.CMD_HEARTBEAT -> {
                PxcFrame(PxcFrame.CMD_HEARTBEAT_ACK, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_HEARTBEAT_ACK,
            PxcFrame.CMD_CHECK_SN_RESULT + 1 -> {
                // acks from the bike — nothing to do
            }
            // First-class: never empty-ack 0x10600 (→ 1970 / 00:00 on Morini/Voge/QJ).
            // Echo-only (2.0.10): do not push an unsolicited 0x10601 — Griffin / X-Cape / Voge
            // ignore it or jump hours.
            PxcFrame.CMD_HU_TIME_SYNC -> onHuTimeSync(tag, frame, out)
            // Zontes-gated (channel=21340): send currentTime/currentTimeZone in the 0x10451
            // reply. Every other bike keeps the empty cmd+1 ack via
            // BikeProfile.handleUnknownControl (unchanged — falls through below otherwise).
            PxcFrame.CMD_HU_QUERY_TIME -> onHuQueryTime(tag, frame, out)
            else -> {
                if (!profile.handleUnknownControl(tag, frame, out, log)) {
                    log("[$tag] cmd=0x${frame.cmd.toUInt().toString(16)} (${PxcFrame.nameOf(frame.cmd)}) " +
                        "len=${frame.payload.size} ${frame.payload.asText()}")
                }
            }
        }
    }

    private fun onHuTimeSync(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val ack = HuTimeSync.ack(frame.payload)
        PxcFrame(PxcFrame.CMD_HU_TIME_SYNC_ACK, ack.payload).write(out)
        val n = ++huTimeSyncCount
        val now = System.currentTimeMillis()
        if (n <= 3 || now - lastHuTimeSyncLogAt >= 30_000L) {
            lastHuTimeSyncLogAt = now
            log("[$tag] HU_TIME_SYNC #$n len=${frame.payload.size} → ack 0x10601 mode=${ack.mode} time=${ack.stamp}")
        }
    }

    private fun onHuQueryTime(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val channel = lastClientInfo?.optString("channel")
        val ack = HuQueryTime.ack(channel = channel)
        PxcFrame(PxcFrame.CMD_HU_QUERY_TIME_ACK, ack.payload).write(out)
        log("[$tag] HU_QUERY_TIME (0x10450) len=${frame.payload.size} → 0x10451 dateTime=${ack.dateTime}")
    }

    private fun onClientInfo(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] *** CLIENT_INFO from bike *** $text")
        val json = try { JSONObject(text) } catch (e: Exception) {
            log("[$tag] CLIENT_INFO parse failed: $e"); return
        }
        lastClientInfo = json
        carHuid = json.optString("HUID").ifEmpty { json.optString("huid") }.ifEmpty { null }
        log("[$tag] carHuid=$carHuid HUName=${json.optString("HUName")} channel=${json.optString("channel")}")

        profile = BikeProfiles.select(json, log)
        val early = BikeProfileHolder.active
        val startedSpec = BikeProfileHolder.aaVideo  // what Android Auto actually started / negotiated with
        // Keep a measured landscape panel (e.g. 800MT 1280×576) when CLIENT_INFO scoring would
        // flip to the near-square 800NK Advanced profile — that mis-route resized touch/margins
        // mid-session while AA stayed on the landscape stream (connect/drop flaps in field logs).
        val earlyPanel = early.panelSize
        val earlyIsWide = earlyPanel != null && earlyPanel.first >= earlyPanel.second * 3 / 2
        if (earlyIsWide && profile === Cfdl26NkTouchProfile) {
            log("[$tag] keeping landscape QR/measured profile '${early.name}' " +
                "(CLIENT_INFO preferred '${profile.name}' but panel ${earlyPanel!!.first}x${earlyPanel.second} is wide)")
            profile = early
        }
        if (early !== profile) {
            log("[$tag] profile refined from QR guess '${early.name}' → CLIENT_INFO '${profile.name}' " +
                "(AA already started at the QR-guess resolution)")
        }
        BikeProfileHolder.active = profile  // authoritative; QR modelId was only the early hint
        // Android Auto's video surface + decoder buffer were sized when AA started (from the QR guess)
        // and CANNOT be resized mid-session. If the refined profile wants a different resolution and no
        // explicit override is in effect, pin the spec to what AA is really running: otherwise the
        // compositor scales the live source (e.g. 800x480) as if it were the profile's (e.g. 720x1280),
        // producing a wildly wrong draw rect and a picture the dash rejects — which drops the link every
        // few seconds (the connect/drop flap). Known/consistent bikes hit no-op here.
        if (BikeProfileHolder.aaVideoOverride == null && BikeProfileHolder.aaVideo != startedSpec) {
            BikeProfileHolder.aaVideoOverride = startedSpec
            log("[$tag] pinned AA video to ${startedSpec.width}x${startedSpec.height} " +
                "(can't resize mid-session; refined profile wanted ${profile.aaVideo.width}x${profile.aaVideo.height})")
        }
        log("[$tag] *** BikeProfile selected: ${profile.name} ***")

        val reply = profile.buildClientInfoReply(json, carHuid, phoneUuid)
        log("[$tag] → CLIENT_INFO reply ${reply.toString().take(180)}…")
        PxcFrame(PxcFrame.CMD_CLIENT_INFO_RLY, reply.toString().toByteArray(Charsets.UTF_8)).write(out)
    }

    private fun onCheckSn(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] CHECK_SN from bike: $text")
        val sn = try { JSONObject(text).optString("sn") } catch (e: Exception) { "" }
        // ack the request frame
        PxcFrame(PxcFrame.CMD_CHECK_SN_ACK, ByteArray(0)).write(out)
        // send the result
        val result = JSONObject().apply {
            put("isOk", true)
            put("errCode", 0)
            put("errMsg", "")
            put("id", sn)
            put("client_set", "easy_conn")
        }
        log("[$tag] → CHECK_SN_RESULT ${result}")
        PxcFrame(PxcFrame.CMD_CHECK_SN_RESULT, result.toString().toByteArray(Charsets.UTF_8)).write(out)
    }
}

private fun ByteArray.asText(): String =
    if (isEmpty()) "" else try { String(this, Charsets.UTF_8) } catch (e: Exception) { "<${size}b>" }
    
