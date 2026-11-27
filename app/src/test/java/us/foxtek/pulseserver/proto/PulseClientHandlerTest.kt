package us.foxtek.pulseserver.proto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import us.foxtek.pulseserver.audio.AudioBuffer
import us.foxtek.pulseserver.audio.pcmBytesPerFrame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class PulseClientHandlerTest {

    @Test
    fun authAndSetClientNameReplyWithExpectedFields() {
        withHarness { h ->
            h.sendCommand(
                command = PACommand.AUTH,
                tag = 1,
                body = {
                    it.writeU32(30)
                    it.writeArbitrary(ByteArray(PAConst.COOKIE_LENGTH))
                }
            )

            val authReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), authReply.readU32())
            assertEquals(1L, authReply.readU32())
            assertEquals(PAConst.PROTOCOL_VERSION.toLong(), authReply.readU32())

            h.sendCommand(
                command = PACommand.SET_CLIENT_NAME,
                tag = 2,
                body = {
                    it.writeProplistStrings(mapOf("application.name" to "unit-test-client"))
                }
            )

            val setNameReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), setNameReply.readU32())
            assertEquals(2L, setNameReply.readU32())
            assertEquals(0L, setNameReply.readU32())
            assertTrue(h.hasStatusContaining("PA client ready"))
        }
    }

    @Test
    fun unsupportedCommandReturnsCommandError() {
        withHarness { h ->
            h.authV16()
            h.sendCommand(command = 999, tag = 77)

            val reply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.ERROR.toLong(), reply.readU32())
            assertEquals(77L, reply.readU32())
            assertEquals(PAError.COMMAND.toLong(), reply.readU32())
        }
    }

    @Test
    fun setSinkVolumeAndMuteUpdateSharedStateAndEmitSubscribeEvents() {
        withHarness { h ->
            resetAudioState()
            h.authV16()

            h.sendCommand(
                command = PACommand.SET_SINK_VOLUME,
                tag = 3,
                body = {
                    it.writeU32(0)
                    it.writeString("AndroidSink")
                    it.writeCVolume(longArrayOf(22_000L, 55_000L))
                }
            )

            val volumeReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), volumeReply.readU32())
            assertEquals(3L, volumeReply.readU32())

            val volumeEvent = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.SUBSCRIBE_EVENT.toLong(), volumeEvent.readU32())
            assertEquals(0L, volumeEvent.readU32())
            assertEquals((PASubscription.EVENT_CHANGE or PASubscription.FACILITY_SINK).toLong(), volumeEvent.readU32())
            assertEquals(0L, volumeEvent.readU32())
            assertEquals(55_000L, ServerAudioState.sinkVolume.get())

            h.sendCommand(
                command = PACommand.SET_SINK_MUTE,
                tag = 4,
                body = {
                    it.writeU32(0)
                    it.writeString("AndroidSink")
                    it.writeBoolean(true)
                }
            )

            val muteReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), muteReply.readU32())
            assertEquals(4L, muteReply.readU32())
            val muteEvent = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.SUBSCRIBE_EVENT.toLong(), muteEvent.readU32())
            assertEquals(0L, muteEvent.readU32())
            assertEquals((PASubscription.EVENT_CHANGE or PASubscription.FACILITY_SINK).toLong(), muteEvent.readU32())
            assertEquals(0L, muteEvent.readU32())
            assertEquals(true, ServerAudioState.sinkMuted.get())
        }
    }

    @Test
    fun getServerInfoReturnsConfiguredNamesAndDefaults() {
        withHarness { h ->
            h.authV16()
            h.sendCommand(command = PACommand.GET_SERVER_INFO, tag = 9)

            val r = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), r.readU32())
            assertEquals(9L, r.readU32())
            assertEquals("PulseAudio (Android)", r.readString())
            assertEquals("15.0", r.readString())
            assertEquals("android", r.readString())
            assertEquals("android-device", r.readString())
            val (fmt, ch, rate) = r.readSampleSpec()
            assertEquals(PAConst.SAMPLE_FORMAT, fmt)
            assertEquals(PAConst.CHANNELS, ch)
            assertEquals(PAConst.SAMPLE_RATE.toLong(), rate)
            assertEquals("AndroidSink", r.readString())
            assertEquals("AndroidSource", r.readString())
            assertEquals(0L, r.readU32())
            assertEquals(byteArrayOf(1, 2).toList(), r.readChannelMap().toList())
        }
    }

    @Test
    fun latencyHandlersEchoClientTimestamp() {
        withHarness { h ->
            h.authV16()
            h.sendCommand(
                command = PACommand.GET_PLAYBACK_LATENCY,
                tag = 11,
                body = {
                    it.writeU32(0)
                    it.writeTimeval(123L, 456L)
                }
            )

            val playback = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), playback.readU32())
            assertEquals(11L, playback.readU32())
            readUsec(playback)
            readUsec(playback)
            playback.readBoolean()
            assertEquals(Pair(123L, 456L), playback.readTimeval())

            h.sendCommand(
                command = PACommand.GET_RECORD_LATENCY,
                tag = 12,
                body = {
                    it.writeU32(1)
                    it.writeTimeval(999L, 111L)
                }
            )

            val record = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), record.readU32())
            assertEquals(12L, record.readU32())
            readUsec(record)
            readUsec(record)
            record.readBoolean()
            assertEquals(Pair(999L, 111L), record.readTimeval())
        }
    }

    @Test
    fun lookupSinkReturnsIndexAndSourceIsMissing() {
        withHarness { h ->
            h.authV16()
            h.sendCommand(
                command = PACommand.LOOKUP_SINK,
                tag = 20,
                body = { it.writeString("AndroidSink") }
            )
            val sink = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), sink.readU32())
            assertEquals(20L, sink.readU32())
            assertEquals(0L, sink.readU32())

            h.sendCommand(
                command = PACommand.LOOKUP_SOURCE,
                tag = 21,
                body = { it.writeString("AndroidSource") }
            )
            val source = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.ERROR.toLong(), source.readU32())
            assertEquals(21L, source.readU32())
            assertEquals(PAError.NO_ENTITY.toLong(), source.readU32())
        }
    }

    @Test
    fun getSinkInfoReflectsSharedVolumeAndMuteAndSourceIsMissing() {
        withHarness { h ->
            h.authV16()
            ServerAudioState.sinkVolume.set(44_444L)
            ServerAudioState.sinkMuted.set(true)

            h.sendCommand(
                command = PACommand.GET_SINK_INFO,
                tag = 30,
                body = {
                    it.writeU32(0)
                    it.writeString("AndroidSink")
                }
            )

            val sink = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), sink.readU32())
            assertEquals(30L, sink.readU32())
            assertEquals(0L, sink.readU32())
            assertEquals("AndroidSink", sink.readString())
            assertEquals("Android Speaker Sink", sink.readString())
            sink.readSampleSpec()
            sink.readChannelMap()
            sink.readU32()
            val sinkVol = sink.readCVolume()
            assertEquals(44_444L, sinkVol[0])
            assertEquals(true, sink.readBoolean())

            h.sendCommand(
                command = PACommand.GET_SOURCE_INFO,
                tag = 31,
                body = {
                    it.writeU32(0)
                    it.writeString("AndroidSource")
                }
            )

            val source = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.ERROR.toLong(), source.readU32())
            assertEquals(31L, source.readU32())
            assertEquals(PAError.NO_ENTITY.toLong(), source.readU32())
        }
    }

    @Test
    fun createAndDeletePlaybackStreamReplyAndAck() {
        withHarness { h ->
            h.authV16()
            h.sendCommand(
                command = PACommand.CREATE_PLAYBACK_STREAM,
                tag = 40,
                body = { writePlaybackCreateBodyV16(it, corked = true) }
            )

            val expectedTl = AudioBuffer.bytesForMillis(
                AudioBuffer.DEFAULT_MILLIS,
                44_100,
                pcmBytesPerFrame(PASampleFormat.S16LE, 2)
            ).toLong()
            val createReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), createReply.readU32())
            assertEquals(40L, createReply.readU32())
            assertEquals(0L, createReply.readU32())
            assertEquals(0L, createReply.readU32())
            assertEquals(expectedTl, createReply.readU32())
            assertEquals(PAConst.MAX_BYTES.toLong(), createReply.readU32())
            assertEquals(expectedTl, createReply.readU32())
            assertEquals(8_192L, createReply.readU32())
            assertEquals(4_096L, createReply.readU32())

            h.sendCommand(
                command = PACommand.DELETE_PLAYBACK_STREAM,
                tag = 41,
                body = { it.writeU32(0) }
            )
            val deleteReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), deleteReply.readU32())
            assertEquals(41L, deleteReply.readU32())
        }
    }

    @Test
    fun createPlaybackUsesConfiguredBufferForCredit() {
        withHarness(bufferMillis = 500) { h ->
            h.authV16()
            h.sendCommand(
                command = PACommand.CREATE_PLAYBACK_STREAM,
                tag = 42,
                body = { writePlaybackCreateBodyV16(it, corked = false) }
            )

            val expectedTl = AudioBuffer.bytesForMillis(
                500,
                44_100,
                pcmBytesPerFrame(PASampleFormat.S16LE, 2)
            ).toLong()
            val reply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), reply.readU32())
            assertEquals(42L, reply.readU32())
            assertEquals(0L, reply.readU32())
            assertEquals(0L, reply.readU32())
            assertEquals(expectedTl, reply.readU32())        // requested_bytes
            assertEquals(PAConst.MAX_BYTES.toLong(), reply.readU32())
            assertEquals(expectedTl, reply.readU32())        // tlength
            assertEquals(8_192L, reply.readU32())            // prebuf
            assertEquals(4_096L, reply.readU32())            // minreq
        }
    }

    @Test
    fun setSourceVolumeAndMuteAreAckedWithoutEvents() {
        withHarness { h ->
            h.authV16()

            h.sendCommand(
                command = PACommand.SET_SOURCE_VOLUME,
                tag = 50,
                body = {
                    it.writeU32(0)
                    it.writeString("AndroidSource")
                    it.writeCVolume(longArrayOf(31_000L))
                }
            )
            val volumeReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), volumeReply.readU32())
            assertEquals(50L, volumeReply.readU32())
            assertEquals(0, volumeReply.remaining())

            h.sendCommand(
                command = PACommand.SET_SOURCE_MUTE,
                tag = 51,
                body = {
                    it.writeU32(0)
                    it.writeString("AndroidSource")
                    it.writeBoolean(true)
                }
            )
            val muteReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), muteReply.readU32())
            assertEquals(51L, muteReply.readU32())
            assertEquals(0, muteReply.remaining())
        }
    }

    @Test
    fun statAndAckCommandsReturnReply() {
        withHarness { h ->
            h.authV16()
            h.sendCommand(command = PACommand.STAT, tag = 60)
            val stat = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), stat.readU32())
            assertEquals(60L, stat.readU32())
            repeat(5) { assertEquals(0L, stat.readU32()) }

            h.sendCommand(command = PACommand.SUBSCRIBE, tag = 61)
            val sub = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), sub.readU32())
            assertEquals(61L, sub.readU32())

            h.sendCommand(command = PACommand.GET_CARD_INFO_LIST, tag = 62)
            val card = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), card.readU32())
            assertEquals(62L, card.readU32())
        }
    }

    @Test
    fun createRecordStreamIsRejectedAndRecordCommandsAck() {
        withHarness { h ->
            h.authV16()
            h.sendCommand(
                command = PACommand.CREATE_RECORD_STREAM,
                tag = 70,
                body = { writeRecordCreateBodyV16(it, peakDetect = true) }
            )

            val createReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.ERROR.toLong(), createReply.readU32())
            assertEquals(70L, createReply.readU32())
            assertEquals(PAError.NOT_SUPPORTED.toLong(), createReply.readU32())

            h.sendCommand(
                command = PACommand.CORK_PLAYBACK_STREAM,
                tag = 71,
                body = {
                    it.writeU32(0)
                    it.writeBoolean(true)
                }
            )
            val corkReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), corkReply.readU32())
            assertEquals(71L, corkReply.readU32())

            h.sendCommand(
                command = PACommand.DELETE_RECORD_STREAM,
                tag = 72,
                body = { it.writeU32(1) }
            )
            val deleteReply = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), deleteReply.readU32())
            assertEquals(72L, deleteReply.readU32())
        }
    }

    @Test
    fun ackOnlyCommandsReturnReply() {
        withHarness { h ->
            h.authV16()
            h.sendCommand(command = PACommand.DRAIN_PLAYBACK_STREAM, tag = 80) {
                it.writeU32(0)
            }
            assertReplyTag(h.readCommandFrame(), 80)

            h.sendCommand(command = PACommand.FLUSH_PLAYBACK_STREAM, tag = 81) {
                it.writeU32(0)
            }
            assertReplyTag(h.readCommandFrame(), 81)

            h.sendCommand(command = PACommand.TRIGGER_PLAYBACK_STREAM, tag = 82)
            assertReplyTag(h.readCommandFrame(), 82)

            h.sendCommand(command = PACommand.PREBUF_PLAYBACK_STREAM, tag = 83)
            assertReplyTag(h.readCommandFrame(), 83)

            h.sendCommand(command = PACommand.CORK_RECORD_STREAM, tag = 84)
            assertReplyTag(h.readCommandFrame(), 84)

            h.sendCommand(command = PACommand.FLUSH_RECORD_STREAM, tag = 85)
            assertReplyTag(h.readCommandFrame(), 85)
        }
    }

    @Test
    fun sourceAndSinkInfoListCommandsReply() {
        withHarness { h ->
            h.authV16()

            h.sendCommand(command = PACommand.GET_SINK_INFO_LIST, tag = 90)
            val sink = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), sink.readU32())
            assertEquals(90L, sink.readU32())
            assertEquals(0L, sink.readU32())

            h.sendCommand(command = PACommand.GET_SOURCE_INFO_LIST, tag = 91)
            val source = TagStructReader(h.readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), source.readU32())
            assertEquals(91L, source.readU32())
            assertEquals(0, source.remaining())
        }
    }

    private fun readUsec(r: TagStructReader): Long {
        assertEquals(PATag.USEC, r.readTag())
        val hi = r.readRawU32()
        val lo = r.readRawU32()
        return (hi shl 32) or lo
    }

    private fun writePlaybackCreateBodyV16(w: TagStructWriter, corked: Boolean) {
        w.writeSampleSpec(PASampleFormat.S16LE, 2, 44_100)
        w.writeChannelMap(byteArrayOf(1, 2))
        w.writeU32(PAConst.INVALID_INDEX)
        w.writeString("AndroidSink")
        w.writeU32(PAConst.MAX_BYTES.toLong())
        w.writeBoolean(corked)
        w.writeU32(16_384L)
        w.writeU32(8_192L)
        w.writeU32(4_096L)
        w.writeU32(0L)
        w.writeCVolume(longArrayOf(PAConst.VOLUME_NORM, PAConst.VOLUME_NORM))
        repeat(7) { w.writeBoolean(false) }
        w.writeBoolean(false)
        w.writeBoolean(false)
        w.writeProplistStrings(mapOf("application.name" to "unit-test-client"))
        w.writeBoolean(false)
        w.writeBoolean(false)
        w.writeBoolean(false)
        w.writeBoolean(false)
        w.writeBoolean(false)
    }

    private fun writeRecordCreateBodyV16(w: TagStructWriter, peakDetect: Boolean) {
        w.writeSampleSpec(PASampleFormat.S16LE, 1, 44_100)
        w.writeChannelMap(byteArrayOf(3))
        w.writeU32(PAConst.INVALID_INDEX)
        w.writeString("AndroidSource")
        w.writeU32(PAConst.MAX_BYTES.toLong())
        w.writeBoolean(false)
        w.writeU32(4_096L)
        repeat(7) { w.writeBoolean(false) }
        w.writeBoolean(peakDetect)
        w.writeBoolean(false)
        w.writeProplistStrings(mapOf("application.name" to "unit-test-client"))
        w.writeU32(0)
        w.writeBoolean(false)
        w.writeBoolean(false)
        w.writeBoolean(false)
    }

    private fun assertReplyTag(frame: PulseFrame, tag: Long) {
        val r = TagStructReader(frame.payload)
        assertEquals(PACommand.REPLY.toLong(), r.readU32())
        assertEquals(tag, r.readU32())
    }

    private fun withHarness(
        bufferMillis: Int = AudioBuffer.DEFAULT_MILLIS,
        block: (Harness) -> Unit
    ) {
        val harness = Harness(bufferMillis)
        try {
            block(harness)
        } finally {
            harness.close()
        }
    }

    private fun resetAudioState() {
        ServerAudioState.sinkVolume.set(PAConst.VOLUME_NORM)
        ServerAudioState.sinkMuted.set(false)
    }

    private class Harness(bufferMillis: Int = AudioBuffer.DEFAULT_MILLIS) {
        private val serverSocket = ServerSocket(0)
        private val clientSocket = Socket("127.0.0.1", serverSocket.localPort).apply { soTimeout = 2000 }
        private val serverClient = serverSocket.accept()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val statuses = mutableListOf<String>()
        private val handler = PulseClientHandler(
            serverClient,
            { msg -> synchronized(statuses) { statuses.add(msg) } },
            scope,
            bufferMillis
        )
        private val runner = thread(start = true, name = "PulseClientHandlerTest") { handler.run() }
        private val input = BufferedInputStream(clientSocket.getInputStream())
        private val output = BufferedOutputStream(clientSocket.getOutputStream())

        fun authV16() {
            sendCommand(
                command = PACommand.AUTH,
                tag = 100,
                body = {
                    it.writeU32(16)
                    it.writeArbitrary(ByteArray(PAConst.COOKIE_LENGTH))
                }
            )
            val reply = TagStructReader(readCommandFrame().payload)
            assertEquals(PACommand.REPLY.toLong(), reply.readU32())
            assertEquals(100L, reply.readU32())
        }

        fun sendCommand(command: Int, tag: Long, body: (TagStructWriter) -> Unit = {}) {
            val w = TagStructWriter()
            w.writeU32(command.toLong())
            w.writeU32(tag)
            body(w)
            PulseFrame.command(w.toByteArray()).writeTo(output)
        }

        fun readCommandFrame(): PulseFrame {
            repeat(10) {
                val frame = PulseFrame.read(input)
                if (frame.isCommand) return frame
            }
            throw AssertionError("Expected command frame")
        }

        fun hasStatusContaining(text: String): Boolean {
            synchronized(statuses) {
                return statuses.any { msg -> msg.contains(text) }
            }
        }

        fun awaitStatusContaining(text: String, timeoutMs: Long = 1000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (hasStatusContaining(text)) return true
                Thread.sleep(10)
            }
            return hasStatusContaining(text)
        }

        fun close() {
            runCatching { clientSocket.close() }
            runCatching { serverClient.close() }
            runCatching { serverSocket.close() }
            scope.cancel()
            runner.join(1000)
        }
    }
}
