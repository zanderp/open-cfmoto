// Adapted from headunit-revived (AGPLv3): aap/AapMessageHandlerType.kt (video + control only)
package dev.zanderp.opencfmoto.aa

internal class AapMessageHandlerType(
    private val transport: AapTransport,
    private val aapVideo: AapVideo,
    private val audioOutput: AaAudioOutput,
) : AapMessageHandler {

    private val aapControl: AapControl = AapControlGateway(transport)

    @Throws(AapMessageHandler.HandleException::class)
    override fun handle(message: AapMessage) {
        val msgType = message.type

        // 1. Video stream first (highest priority for smooth display).
        if (message.channel == Channel.ID_VID) {
            if (aapVideo.process(message)) {
                if (msgType == 0 || msgType == 1) transport.sendMediaAck(message.channel)
                return
            }
        }

        // 2. Audio: play AA's projected PCM on the phone (nav voice / media / system) instead of
        //    discarding it — modern AA sends it to the "car" (us), not to a local speaker, so the
        //    phone was silent. Still ACK so AA's unacked window never stalls. The PCM sits after the
        //    2-byte msg-type, plus an 8-byte timestamp when msgType==0 (same header as video).
        if (message.isAudio && (msgType == 0 || msgType == 1)) {
            val payloadOffset = if (msgType == 0) 10 else 2
            if (message.size > payloadOffset) {
                audioOutput.submit(message.channel, message.data, payloadOffset, message.size - payloadOffset)
            }
            transport.sendMediaAck(message.channel)
            return
        }

        // 3. Control message fallback.
        if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AaLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AaLog.e("Unknown msg_type: %d, flags: %d, channel: %d", msgType, message.flags, message.channel)
        }
    }
}
