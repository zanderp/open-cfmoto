// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.aa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays Android Auto's projected audio (PCM) on the phone's own output — speaker, or a paired BT
 * headset/helmet — instead of discarding it.
 *
 * In self-mode AA treats us as a car head unit and sends nav voice, media and system sounds through
 * the projection audio channels ([Channel.ID_AU1]/[ID_AUD]/[ID_AU2]) in the PCM format we advertised
 * in [ServiceDiscoveryResponse]. The app used to ACK-and-drop that PCM on the assumption AA would
 * play it locally; on modern AA it does not, so the phone was silent. This writes it out.
 *
 * Off the read thread: [submit] copies the chunk onto a bounded queue and one writer thread drains
 * it, so a full audio buffer never stalls video message processing (it drops instead).
 *
 * v1 does not touch audio focus — the AudioTrack is audible regardless, and focus/ducking is a later
 * refinement handled alongside the handlebar-volume toggle.
 *
 * Output is pinned to the phone's built-in speaker ([setPreferredDevice]). Without this, when the
 * phone is also paired to a Bluetooth audio device (the bike's own BT module, a helmet, …) the
 * system routes our tracks there and the rider hears nothing on the phone ("it thinks it's connected
 * to another source"). Routing to a chosen BT headset/intercom instead is a later toggle.
 */
class AaAudioOutput(private val context: Context) {

    private class Chunk(val channel: Int, val pcm: ByteArray)

    // Format per channel MUST match what ServiceDiscoveryResponse advertises for that sink.
    private data class Fmt(val rate: Int, val stereo: Boolean, val usage: Int)

    private val formats = mapOf(
        Channel.ID_AUD to Fmt(48000, stereo = true, AudioAttributes.USAGE_MEDIA),
        Channel.ID_AU1 to Fmt(16000, stereo = false, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE),
        Channel.ID_AU2 to Fmt(16000, stereo = false, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION),
    )

    private val queue = LinkedBlockingQueue<Chunk>(96)
    // Written by the worker (trackFor) and read+cleared by stop() on the caller thread → concurrent.
    private val tracks = ConcurrentHashMap<Int, AudioTrack>()
    @Volatile private var running = false
    private var worker: Thread? = null
    private var dropped = 0L

    fun start() {
        if (running) return
        running = true
        worker = Thread(::drain).apply { name = "AaAudioOutput"; isDaemon = true; start() }
        AaLog.i("AaAudioOutput started — projecting AA audio to the phone")
    }

    /** Copy [len] bytes of PCM from [data] at [offset] for [channel] and enqueue (drop if full). */
    fun submit(channel: Int, data: ByteArray, offset: Int, len: Int) {
        if (!running || len <= 0 || !formats.containsKey(channel)) return
        val pcm = data.copyOfRange(offset, offset + len)
        if (!queue.offer(Chunk(channel, pcm))) {
            dropped++
            if (dropped == 1L || dropped % 200 == 0L) AaLog.w("AaAudioOutput: queue full, dropped $dropped chunk(s)")
        }
    }

    private fun drain() {
        while (running) {
            val chunk = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            val track = trackFor(chunk.channel) ?: continue
            try {
                track.write(chunk.pcm, 0, chunk.pcm.size)
            } catch (e: Exception) {
                AaLog.e("AaAudioOutput write failed on ch=${chunk.channel}: ${e.message}")
            }
        }
    }

    private fun trackFor(channel: Int): AudioTrack? {
        tracks[channel]?.let { return it }
        val f = formats[channel] ?: return null
        return try {
            val channelMask = if (f.stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(f.rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(f.usage)
                        .setContentType(
                            if (f.usage == AudioAttributes.USAGE_MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC
                            else AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(f.rate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            // Pin to the phone speaker so a paired Bluetooth device (the bike's own module, a helmet)
            // doesn't silently swallow the audio. Best-effort: if the speaker isn't enumerable the
            // track still plays on the system default.
            builtinSpeaker()?.let { spk ->
                val ok = track.setPreferredDevice(spk)
                AaLog.i("AaAudioOutput: pin ${Channel.name(channel)} → phone speaker (${if (ok) "ok" else "rejected"})")
            }
            track.play()
            tracks[channel] = track
            AaLog.i("AaAudioOutput: ${Channel.name(channel)} track @ ${f.rate}Hz ${if (f.stereo) "stereo" else "mono"}")
            track
        } catch (e: Exception) {
            AaLog.e("AaAudioOutput: track create failed ch=$channel: ${e.message}")
            null
        }
    }

    /** The phone's built-in loudspeaker output, or null if it can't be enumerated. */
    private fun builtinSpeaker(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    fun stop() {
        running = false
        val w = worker
        worker = null
        w?.interrupt()
        // Wait for the worker to leave drain() before releasing tracks — otherwise it can be inside a
        // native track.write() (not interruptible) and we'd release the track out from under it.
        try { w?.join(500) } catch (_: InterruptedException) {}
        queue.clear()
        for (t in tracks.values) {
            try { t.pause(); t.flush(); t.release() } catch (_: Exception) {}
        }
        tracks.clear()
        AaLog.i("AaAudioOutput stopped")
    }
}
