// Adapted from headunit-revived (AGPLv3): aap/AapTransport.kt
// Trimmed for video-only headless projection: no audio decoder / audio manager / mic recorder /
// notification / Settings. Keeps the send+poll HandlerThreads, the AAP version+SSL handshake,
// and the inbound message loop. gainVideoFocus's Activity broadcast is gone (AapControlMedia
// grants video focus directly).
package dev.zanderp.opencfmoto.aa

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.SparseIntArray
import dev.zanderp.opencfmoto.aa.proto.Control

class AapTransport(
    private val videoDecoder: VideoDecoder,
    private val context: Context,
    private val externalSsl: AapSslContext? = null
) {
    val ssl: AapSsl = externalSsl ?: AapSslContext(SingleKeyKeyManager(context))

    internal val aapVideo: AapVideo
    /** Plays AA's projected audio (nav voice / media / system) on the phone instead of discarding it. */
    internal val audioOutput = AaAudioOutput(context)
    private var sendThread: HandlerThread? = null
    private var pollThread: HandlerThread? = null
    private val sessionIds = SparseIntArray(4)
    private val startedSensors = HashSet<Int>(4)
    private var connection: AccessoryConnection? = null
    private var aapRead: AapRead? = null

    var ignoreNextStopRequest: Boolean = false
    /** Set when VIDEO_FOCUS_NATIVE triggers a stop (user tapped Exit on the phone). */
    @Volatile var wasUserExit: Boolean = false
    @Volatile var onQuit: ((Boolean) -> Unit)? = null

    /** The head unit's microphone (Assistant voice). Installed by [AaReceiver]; driven by AA's
     *  MICROPHONE_REQUEST via [AapControlMedia]. Null until a session is set up. */
    @Volatile var microphone: AaMicrophone? = null

    private var pollHandler: Handler? = null
    private val pollHandlerCallback = Handler.Callback {
        val readInstance = aapRead ?: return@Callback false
        val ret = readInstance.read()
        if (ret < 0) {
            AaLog.i("Quitting because ret < 0 ($ret)")
            this.quit(clean = (ret == -2))
            return@Callback true
        }
        pollHandler?.let { if (!it.hasMessages(MSG_POLL)) it.sendEmptyMessage(MSG_POLL) }
        return@Callback true
    }

    private var sendHandler: Handler? = null
    private val sendHandlerCallback = Handler.Callback {
        this.sendEncryptedMessage(data = it.obj as ByteArray, length = it.arg2)
        return@Callback true
    }

    val isAlive: Boolean
        get() = pollThread?.isAlive ?: false

    private fun triggerFocusCycleRecovery() {
        AaLog.w("AapTransport: Requesting recovery keyframe via focus cycle.")
        send(VideoFocusEvent(gain = false, unsolicited = false))
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAlive) send(VideoFocusEvent(gain = true, unsolicited = true))
        }, 100)
    }

    init {
        aapVideo = AapVideo(videoDecoder) { triggerFocusCycleRecovery() }
        videoDecoder.onDecoderError = { triggerFocusCycleRecovery() }
        audioOutput.start()
    }

    internal fun startSensor(type: Int) { startedSensors.add(type) }

    /** The last night value we reported (also the value sent when AA starts the NIGHT sensor). */
    @Volatile var nightMode: Boolean = false

    /**
     * Report day/night to Android Auto so Maps switches its map theme. No-op until AA has actually
     * started the NIGHT sensor (it requests it during channel setup); [AapControlSensor] sends the
     * initial value at that point, and this handles later toggles.
     */
    fun sendNightMode(isNight: Boolean) {
        nightMode = isNight
        if (!startedSensors.contains(SENSOR_NIGHT)) return
        send(NightModeEvent(isNight))
    }

    private fun sendEncryptedMessage(data: ByteArray, length: Int): Int {
        val ba = ssl.encrypt(AapMessage.HEADER_SIZE, length - AapMessage.HEADER_SIZE, data) ?: return -1
        ba.data[0] = data[0]
        ba.data[1] = data[1]
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)
        val size = connection?.sendBlocking(ba.data, ba.limit, 250) ?: -1
        if (AaLog.LOG_VERBOSE) AaLog.v("Sent size: %d", size)
        return 0
    }

    internal fun stop() {
        AaLog.i("AapTransport stopping and sending byebye")
        val byebye = Control.ByeByeRequest.newBuilder().setReason(Control.ByeByeReason.USER_SELECTION).build()
        send(AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, byebye))
        SystemClock.sleep(150)
        quit()
    }

    internal fun quit(clean: Boolean = false) {
        val cb = onQuit ?: return
        onQuit = null
        AaLog.i("AapTransport quitting (clean=$clean)")
        cb.invoke(clean)
        pollThread?.quit()
        sendThread?.quit()
        aapVideo.release()
        audioOutput.stop()
        videoDecoder.onDecoderError = null
        try {
            if (Thread.currentThread() != pollThread) pollThread?.join(1000)
            sendThread?.join(1000)
        } catch (e: InterruptedException) {
            AaLog.e("Failed to join threads", e)
        }
        aapRead = null
        ssl.release()
        pollHandler = null
        sendHandler = null
        pollThread = null
        sendThread = null
    }

    /** Phase 1: create send/poll threads and run the version + SSL handshake. */
    internal fun startHandshake(connection: AccessoryConnection): Boolean {
        AaLog.i("Start Aap transport handshake for $connection")
        this.connection = connection
        wasUserExit = false

        sendThread = HandlerThread("AapTransport:Handler::Send", Process.THREAD_PRIORITY_AUDIO)
        sendThread!!.start()
        sendHandler = Handler(sendThread!!.looper, sendHandlerCallback)

        pollThread = HandlerThread("AapTransport:Handler::Poll", Process.THREAD_PRIORITY_AUDIO)
        pollThread!!.start()
        pollHandler = Handler(pollThread!!.looper, pollHandlerCallback)

        if (!handshake(connection)) {
            quit()
            AaLog.e("Handshake failed")
            return false
        }
        return true
    }

    /** Phase 2: begin the inbound message loop. Call after the decoder surface is set. */
    internal fun startReading() {
        AaLog.i("Start Aap transport read loop")
        aapRead = AapRead.Factory.create(connection!!, this, aapVideo)
        pollHandler?.sendEmptyMessage(MSG_POLL)
    }

    private fun handshake(connection: AccessoryConnection): Boolean {
        try {
            if (!connection.isSingleMessage) SystemClock.sleep(500)

            val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)

            if (!connection.isSingleMessage) {
                var drained = 0
                while (true) {
                    val n = try { connection.recvBlocking(buffer, buffer.size, 50, false) } catch (e: Exception) { -1 }
                    if (n <= 0) break
                    drained += n
                }
                if (drained > 0) AaLog.i("Handshake: Drained $drained bytes of stale data before version request")
            }

            AaLog.d("Handshake: Starting version request. TS: ${SystemClock.elapsedRealtime()}")
            val version = Messages.versionRequest
            var ret: Int
            var attempt = 0
            var received = false
            val versionDeadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
            while (attempt < 3 && connection.isConnected) {
                if (SystemClock.elapsedRealtime() >= versionDeadline) {
                    AaLog.e("Handshake: Version exchange timed out after $attempt attempt(s).")
                    return false
                }
                attempt++
                ret = connection.sendBlocking(version, version.size, 2000)
                AaLog.d("Handshake: Version request sent. ret: $ret. attempt: $attempt.")
                if (ret < 0) {
                    AaLog.w("Handshake: Version request send failed (ret=$ret), attempt $attempt")
                    SystemClock.sleep(200)
                    continue
                }

                val recvDeadline = SystemClock.elapsedRealtime() + 2000
                while (SystemClock.elapsedRealtime() < recvDeadline) {
                    val remaining = (recvDeadline - SystemClock.elapsedRealtime()).toInt().coerceAtLeast(100)
                    ret = connection.recvBlocking(buffer, buffer.size, remaining, false)
                    if (ret <= 0) break
                    if (ret >= 6 && buffer[0] == 0.toByte() && buffer[4] == 0.toByte() && buffer[5] == 2.toByte()) {
                        AaLog.i("Handshake: Version response received (ret=$ret, attempt=$attempt).")
                        received = true
                        break
                    }
                    val ch = buffer[0].toInt() and 0xFF
                    val type = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                    AaLog.w("Handshake: Ignoring unexpected message (ch=$ch, type=0x${type.toString(16)}, len=$ret). Waiting for VERSION_RESPONSE.")
                }
                if (received) break
                AaLog.w("Handshake: No VERSION_RESPONSE within 2s (attempt $attempt)")
                SystemClock.sleep(200)
            }

            if (!received) {
                AaLog.e("Handshake: Version request/response failed after $attempt attempt(s).")
                return false
            }

            AaLog.d("Handshake: Starting SSL handshake via performHandshake().")
            if (!ssl.performHandshake(connection)) {
                AaLog.e("Handshake: SSL performHandshake failed.")
                return false
            }
            ssl.postHandshakeReset()
            AaLog.d("Handshake: SSL buffers reset after handshake.")

            val status = Messages.statusOk
            ret = connection.sendBlocking(status, status.size, 2000)
            if (ret < 0) {
                AaLog.e("Handshake: Status OK send failed ret: $ret")
                return false
            }
            AaLog.i("Handshake: Status OK sent (%d). Handshake successful.", ret)
            return true
        } catch (e: Exception) {
            AaLog.e("Handshake failed with exception", e)
            return false
        }
    }

    fun send(message: AapMessage) {
        val handler = sendHandler
        if (handler == null) {
            AaLog.i("Cannot send message, handler is null (quitting?)")
        } else {
            if (AaLog.LOG_VERBOSE) AaLog.v(message.toString())
            handler.sendMessage(handler.obtainMessage(MSG_SEND, 0, message.size, message.data))
        }
    }

    internal fun sendMediaAck(channel: Int) = send(MediaAck(channel, sessionIds.get(channel)))

    internal fun setSessionId(channel: Int, sessionId: Int) = sessionIds.put(channel, sessionId)

    companion object {
        private const val MSG_POLL = 1
        private const val MSG_SEND = 2
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        /** Sensors.SensorType.NIGHT value (see proto). */
        private const val SENSOR_NIGHT = 10
    }
}
