package us.foxtek.pulseserver.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PulseFrameTest {

    @Test
    fun writeAndReadRoundTrip() {
        val original = PulseFrame(
            channel = 7L,
            flags = 2L,
            offsetHi = 1L,
            offsetLo = 3L,
            payload = byteArrayOf(1, 2, 3, 4)
        )

        val bytes = ByteArrayOutputStream().also { original.writeTo(it) }.toByteArray()
        val decoded = PulseFrame.read(ByteArrayInputStream(bytes))

        assertEquals(original.channel, decoded.channel)
        assertEquals(original.flags, decoded.flags)
        assertEquals(original.offsetHi, decoded.offsetHi)
        assertEquals(original.offsetLo, decoded.offsetLo)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun readHandlesFragmentedInput() {
        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val bytes = ByteArrayOutputStream().also {
            PulseFrame.audio(channelId = 3, pcm = payload, seekMode = 1L).writeTo(it)
        }.toByteArray()

        val fragmented = ChunkedInputStream(ByteArrayInputStream(bytes), chunkSize = 1)
        val frame = PulseFrame.read(fragmented)

        assertEquals(3L, frame.channel)
        assertEquals(1L, frame.flags)
        assertArrayEquals(payload, frame.payload)
    }

    @Test(expected = EOFException::class)
    fun readThrowsOnHeaderEof() {
        PulseFrame.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
    }

    @Test(expected = IOException::class)
    fun readRejectsOversizedPayload() {
        val header = ByteBuffer.allocate(PulseFrame.DESCRIPTOR_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(PulseFrame.MAX_PAYLOAD_SIZE + 1)
            .putInt(PAConst.CHANNEL_CMD.toInt())
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()

        PulseFrame.read(ByteArrayInputStream(header))
    }

    @Test(expected = EOFException::class)
    fun readThrowsOnPayloadEof() {
        val bytes = ByteArrayOutputStream().also {
            PulseFrame.command(byteArrayOf(1, 2, 3, 4, 5)).writeTo(it)
        }.toByteArray()

        PulseFrame.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 2)))
    }

    @Test
    fun commandAndAudioFactoriesSetExpectedFlags() {
        val cmd = PulseFrame.command(byteArrayOf(1))
        assertTrue(cmd.isCommand)
        assertEquals(PAConst.CHANNEL_CMD, cmd.channel)

        val audio = PulseFrame.audio(channelId = 9, pcm = byteArrayOf(1, 2), seekMode = 5L)
        assertFalse(audio.isCommand)
        assertEquals(9L, audio.channel)
        assertEquals(5L, audio.flags)
    }

    @Test
    fun equalsIgnoresOffsetsButIncludesPayloadAndFlags() {
        val a = PulseFrame(1L, 2L, 10L, 11L, byteArrayOf(1, 2, 3))
        val b = PulseFrame(1L, 2L, 99L, 88L, byteArrayOf(1, 2, 3))
        val c = PulseFrame(1L, 3L, 10L, 11L, byteArrayOf(1, 2, 3))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }

    private class ChunkedInputStream(
        private val delegate: InputStream,
        private val chunkSize: Int
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return delegate.read(b, off, minOf(chunkSize, len))
        }
    }
}
