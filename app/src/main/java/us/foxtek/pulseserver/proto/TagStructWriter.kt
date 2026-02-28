package us.foxtek.pulseserver.proto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a PulseAudio tagstruct payload as a byte array.
 */
class TagStructWriter {
    private val out = ByteArrayOutputStream(256)

    fun toByteArray(): ByteArray = out.toByteArray()

    fun writeU32(v: Long) {
        out.write(PATag.U32.toInt())
        writeRawU32(v)
    }

    fun writeRawU32(v: Long) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v.toInt()).array()
        out.write(b)
    }

    fun writeU8(v: Int) {
        out.write(PATag.U8.toInt())
        out.write(v and 0xFF)
    }

    fun writeBoolean(v: Boolean) {
        out.write(if (v) PATag.BOOLEAN_TRUE.toInt() else PATag.BOOLEAN_FALSE.toInt())
    }

    fun writeString(s: String?) {
        if (s == null) {
            out.write(PATag.STRING_NULL.toInt())
        } else {
            out.write(PATag.STRING.toInt())
            out.write(s.toByteArray(Charsets.UTF_8))
            out.write(0) // NUL terminator
        }
    }

    fun writeArbitrary(data: ByteArray) {
        out.write(PATag.ARBITRARY.toInt())
        writeRawU32(data.size.toLong())
        out.write(data)
    }

    fun writeSampleSpec(format: Int, channels: Int, rate: Long) {
        out.write(PATag.SAMPLE_SPEC.toInt())
        out.write(format and 0xFF)
        out.write(channels and 0xFF)
        writeRawU32(rate)
    }

    fun writeChannelMap(positions: ByteArray) {
        out.write(PATag.CHANNEL_MAP.toInt())
        out.write(positions.size and 0xFF)
        out.write(positions)
    }

    fun writeCVolume(volumes: LongArray) {
        out.write(PATag.CVOLUME.toInt())
        out.write(volumes.size and 0xFF)
        for (v in volumes) writeRawU32(v)
    }

    fun writeVolume(v: Long) {
        out.write(PATag.VOLUME.toInt())
        writeRawU32(v)
    }

    fun writeUsec(usec: Long) {
        out.write(PATag.USEC.toInt())
        writeRawU32(usec ushr 32)
        writeRawU32(usec and 0xFFFFFFFFL)
    }

    fun writeTimeval(sec: Long, usec: Long) {
        out.write(PATag.TIMEVAL.toInt())
        writeRawU32(sec)
        writeRawU32(usec)
    }

    /**
     * Write a FORMAT_INFO for raw PCM (encoding=0).
     * The proplist encodes the negotiated sample spec as PA expects for PCM format.
     */
    fun writeFormatInfo(sampleFormat: Int, channels: Int, sampleRate: Int) {
        out.write(PATag.FORMAT_INFO.toInt())
        out.write(0)  // PA_ENCODING_PCM = 0
        // Inline proplist entries (no outer PROPLIST tag — FORMAT_INFO embeds them directly)
        // "format.sample_format" → U32 value of the PA sample format
        writeString("format.sample_format")
        val fmtBytes = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put(PATag.U32).putInt(sampleFormat).array()
        writeU32(fmtBytes.size.toLong()); writeArbitrary(fmtBytes)
        // "format.rate" → U32 sample rate
        writeString("format.rate")
        val rateBytes = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put(PATag.U32).putInt(sampleRate).array()
        writeU32(rateBytes.size.toLong()); writeArbitrary(rateBytes)
        // "format.channels" → U32 channel count
        writeString("format.channels")
        val chBytes = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put(PATag.U32).putInt(channels).array()
        writeU32(chBytes.size.toLong()); writeArbitrary(chBytes)
        // sentinel
        out.write(PATag.STRING_NULL.toInt())
    }

    fun writeS64(v: Long) {
        out.write(PATag.S64.toInt())
        val b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()
        out.write(b)
    }

    fun writeU64(v: Long) {
        out.write(PATag.U64.toInt())
        val b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()
        out.write(b)
    }

    /** Write a PROPLIST: PA_TAG_PROPLIST, key/value pairs, then STRING_NULL sentinel */
    fun writeProplist(props: Map<String, ByteArray>) {
        out.write(PATag.PROPLIST.toInt())
        for ((key, value) in props) {
            writeString(key)         // STRING key
            writeU32(value.size.toLong())  // U32 len
            writeArbitrary(value)    // ARBITRARY value
        }
        out.write(PATag.STRING_NULL.toInt()) // sentinel
    }

    /** Convenience: write proplist with string values */
    fun writeProplistStrings(props: Map<String, String>) {
        writeProplist(props.mapValues { (_, v) -> (v + "\u0000").toByteArray(Charsets.UTF_8) })
    }
}
