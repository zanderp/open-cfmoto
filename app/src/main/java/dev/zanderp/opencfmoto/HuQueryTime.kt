package dev.zanderp.opencfmoto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Body for `0x10451` — reply to bike `ECP_C2P_QUERY_TIME` (`0x10450`).
 *
 * Zontes-gated (channel=21340): adds `currentTime` (epoch + local TZ offset) and
 * `currentTimeZone`, matching the official Zontes Smart app's ECP_C2P_QUERY_TIME reply.
 * Every other channel keeps the plain {time, dateTime} shape — no change in behavior for
 * Griffin / X-Cape / Voge / QJ / etc. See Discord #debugging write-up.
 */
internal object HuQueryTime {
    private val dateTimeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
    private val EXTENDED_FIELDS_CHANNELS = setOf("21340") // Zontes 125X, confirmed

    data class Ack(val payload: ByteArray, val dateTime: String, val timeMillis: Long)

    fun ack(
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        channel: String? = null,
    ): Ack {
        val dateTime = synchronized(dateTimeFmt) {
            dateTimeFmt.timeZone = zone
            String.format(Locale.US, "%s:%03d", dateTimeFmt.format(Date(nowMillis)), (nowMillis % 1000L).toInt())
        }
        val json = if (channel != null && channel in EXTENDED_FIELDS_CHANNELS) {
            val currentTime = nowMillis + zone.getOffset(nowMillis)
            "{\"time\":$nowMillis,\"currentTime\":$currentTime,\"currentTimeZone\":\"${zone.id}\",\"dateTime\":\"$dateTime\"}"
        } else {
            "{\"time\":$nowMillis,\"dateTime\":\"$dateTime\"}"
        }
        return Ack(json.toByteArray(Charsets.UTF_8), dateTime, nowMillis)
    }
}
