package us.foxtek.pulseserver.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TagStructReaderWriterTest {

    @Test
    fun roundTripPrimitiveAndCompositeFields() {
        val writer = TagStructWriter()
        writer.writeU32(0xFFFFFFFFL)
        writer.writeU8(255)
        writer.writeBoolean(true)
        writer.writeBoolean(false)
        writer.writeString("hello")
        writer.writeString(null)
        writer.writeSampleSpec(PASampleFormat.S16LE, 2, 44_100)
        writer.writeChannelMap(byteArrayOf(1, 2))
        writer.writeCVolume(longArrayOf(0x10000L, 0x8000L))
        writer.writeArbitrary(byteArrayOf(10, 20, 30))

        val reader = TagStructReader(writer.toByteArray())
        assertEquals(0xFFFFFFFFL, reader.readU32())
        assertEquals(255, reader.readU8())
        assertTrue(reader.readBoolean())
        assertFalse(reader.readBoolean())
        assertEquals("hello", reader.readString())
        assertEquals(null, reader.readString())

        val (format, channels, rate) = reader.readSampleSpec()
        assertEquals(PASampleFormat.S16LE, format)
        assertEquals(2, channels)
        assertEquals(44_100L, rate)

        assertArrayEquals(byteArrayOf(1, 2), reader.readChannelMap())
        assertArrayEquals(longArrayOf(0x10000L, 0x8000L), reader.readCVolume())
        assertArrayEquals(byteArrayOf(10, 20, 30), reader.readArbitrary())
        assertEquals(0, reader.remaining())
    }

    @Test
    fun writeFormatInfoProducesReadableTaggedU32Values() {
        val writer = TagStructWriter()
        writer.writeFormatInfo(sampleFormat = PASampleFormat.S16LE, channels = 2, sampleRate = 48_000)

        val reader = TagStructReader(writer.toByteArray())
        val (encoding, props) = reader.readFormatInfo()

        assertEquals(0, encoding)
        assertEquals(setOf("format.sample_format", "format.rate", "format.channels"), props.keys)
        assertEquals(PASampleFormat.S16LE, decodeTaggedU32(props.getValue("format.sample_format")))
        assertEquals(48_000, decodeTaggedU32(props.getValue("format.rate")))
        assertEquals(2, decodeTaggedU32(props.getValue("format.channels")))
        assertEquals(0, reader.remaining())
    }

    @Test
    fun skipFieldSkipsProplistAndFormatInfo() {
        val first = TagStructWriter().apply {
            writeU8(7)
            writeProplistStrings(mapOf("application.name" to "client"))
            writeU32(9)
        }

        val firstReader = TagStructReader(first.toByteArray())
        assertEquals(7, firstReader.readU8())
        firstReader.skipField()
        assertEquals(9L, firstReader.readU32())
        assertEquals(0, firstReader.remaining())

        val second = TagStructWriter().apply {
            writeString("before")
            writeFormatInfo(sampleFormat = PASampleFormat.S16LE, channels = 1, sampleRate = 44_100)
            writeBoolean(true)
        }

        val secondReader = TagStructReader(second.toByteArray())
        assertEquals("before", secondReader.readString())
        secondReader.skipField()
        assertTrue(secondReader.readBoolean())
        assertEquals(0, secondReader.remaining())
    }

    private fun decodeTaggedU32(value: ByteArray): Int {
        assertEquals(5, value.size)
        assertEquals(PATag.U32, value[0])
        return ByteBuffer.wrap(value, 1, 4).order(ByteOrder.BIG_ENDIAN).int
    }
}
