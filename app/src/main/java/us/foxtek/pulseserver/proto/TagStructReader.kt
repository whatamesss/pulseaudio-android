package us.foxtek.pulseserver.proto

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads tagged values from a PulseAudio tagstruct byte stream.
 * All multi-byte integers are big-endian on the wire.
 */
class TagStructReader(private val buf: ByteArray, private var pos: Int = 0) {

    fun remaining(): Int = buf.size - pos

    fun readTag(): Byte {
        if (pos >= buf.size) error("TagStruct underflow reading tag at pos $pos, size=${buf.size}")
        return buf[pos++]
    }

    fun peekTag(): Byte = buf[pos]

    /** Read U32 field: tag byte 'L' + 4 bytes big-endian */
    fun readU32(): Long {
        val tag = readTag()
        check(tag == PATag.U32) { "Expected U32 tag 0x${PATag.U32.toString(16)} got 0x${tag.toString(16)}" }
        return readRawU32()
    }

    /** Read raw 4 bytes as unsigned 32-bit big-endian (returned as Long to stay unsigned) */
    fun readRawU32(): Long {
        val b = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        pos += 4
        return b
    }

    /** Read U8 field: tag byte 'B' + 1 byte */
    fun readU8(): Int {
        val tag = readTag()
        check(tag == PATag.U8) { "Expected U8 tag" }
        return buf[pos++].toInt() and 0xFF
    }

    /** Read boolean field: tag byte '1' or '0' */
    fun readBoolean(): Boolean {
        val tag = readTag()
        return when (tag) {
            PATag.BOOLEAN_TRUE -> true
            PATag.BOOLEAN_FALSE -> false
            else -> error("Expected boolean tag, got 0x${tag.toString(16)}")
        }
    }

    /** Read nullable string field: tag byte 't' + NUL-terminated UTF-8, or 'N' for null */
    fun readString(): String? {
        val tag = readTag()
        if (tag == PATag.STRING_NULL) return null
        check(tag == PATag.STRING) { "Expected STRING or STRING_NULL tag, got 0x${tag.toString(16)}" }
        val start = pos
        while (buf[pos] != 0.toByte()) pos++
        val s = String(buf, start, pos - start, Charsets.UTF_8)
        pos++ // consume NUL
        return s
    }

    /** Read SAMPLE_SPEC field: tag 'a' + 1B format + 1B channels + 4B rate */
    fun readSampleSpec(): Triple<Int, Int, Long> {
        val tag = readTag()
        check(tag == PATag.SAMPLE_SPEC) { "Expected SAMPLE_SPEC tag" }
        val format = buf[pos++].toInt() and 0xFF
        val channels = buf[pos++].toInt() and 0xFF
        val rate = readRawU32()
        return Triple(format, channels, rate)
    }

    /** Read CHANNEL_MAP field: tag 'm' + 1B n + n×1B positions */
    fun readChannelMap(): ByteArray {
        val tag = readTag()
        check(tag == PATag.CHANNEL_MAP) { "Expected CHANNEL_MAP tag" }
        val n = buf[pos++].toInt() and 0xFF
        val positions = ByteArray(n) { buf[pos++] }
        return positions
    }

    /** Read CVOLUME field: tag 'v' + 1B n + n×4B volumes */
    fun readCVolume(): LongArray {
        val tag = readTag()
        check(tag == PATag.CVOLUME) { "Expected CVOLUME tag" }
        val n = buf[pos++].toInt() and 0xFF
        return LongArray(n) { readRawU32() }
    }

    /** Read ARBITRARY field: tag 'x' + 4B length + that many bytes */
    fun readArbitrary(): ByteArray {
        val tag = readTag()
        check(tag == PATag.ARBITRARY) { "Expected ARBITRARY tag" }
        val len = readRawU32().toInt()
        val data = buf.copyOfRange(pos, pos + len)
        pos += len
        return data
    }

    /** Read TIMEVAL field: tag 'T' + 4B sec + 4B usec */
    fun readTimeval(): Pair<Long, Long> {
        val tag = readTag()
        check(tag == PATag.TIMEVAL) { "Expected TIMEVAL tag, got 0x${tag.toString(16)}" }
        val sec  = readRawU32()
        val usec = readRawU32()
        return Pair(sec, usec)
    }

    /** Read FORMAT_INFO field: tag 'f' + 1B encoding + proplist */
    fun readFormatInfo(): Pair<Int, Map<String, ByteArray>> {
        val tag = readTag()
        check(tag == PATag.FORMAT_INFO) { "Expected FORMAT_INFO tag" }
        val encoding = buf[pos++].toInt() and 0xFF
        val props = readProplistEntries()
        return Pair(encoding, props)
    }

    /** Read PROPLIST: tag 'P' followed by string/value pairs terminated by STRING_NULL */
    fun readProplist(): Map<String, ByteArray> {
        val tag = readTag()
        check(tag == PATag.PROPLIST) { "Expected PROPLIST tag, got 0x${tag.toString(16)}" }
        return readProplistEntries()
    }

    fun readProplistEntries(): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        while (true) {
            val keyTag = readTag()
            if (keyTag == PATag.STRING_NULL) break
            check(keyTag == PATag.STRING) { "Expected STRING key in proplist" }
            val keyStart = pos
            while (buf[pos] != 0.toByte()) pos++
            val key = String(buf, keyStart, pos - keyStart, Charsets.UTF_8)
            pos++ // NUL
            val lenTag = readTag()
            check(lenTag == PATag.U32) { "Expected U32 value_len in proplist" }
            val len = readRawU32().toInt()
            val arbTag = readTag()
            check(arbTag == PATag.ARBITRARY) { "Expected ARBITRARY in proplist value" }
            val arbLen = readRawU32().toInt()
            check(arbLen == len) { "Proplist value length mismatch" }
            map[key] = buf.copyOfRange(pos, pos + len)
            pos += len
        }
        return map
    }

    /** Skip one field regardless of type */
    fun skipField() {
        when (val tag = readTag()) {
            PATag.U32 -> pos += 4
            PATag.U8 -> pos += 1
            PATag.BOOLEAN_TRUE, PATag.BOOLEAN_FALSE -> { /* no payload */ }
            PATag.STRING_NULL -> { /* no payload */ }
            PATag.STRING -> { while (buf[pos] != 0.toByte()) pos++; pos++ }
            PATag.SAMPLE_SPEC -> pos += 6
            PATag.CHANNEL_MAP -> { val n = buf[pos++].toInt() and 0xFF; pos += n }
            PATag.CVOLUME -> { val n = buf[pos++].toInt() and 0xFF; pos += n * 4 }
            PATag.ARBITRARY -> { val len = readRawU32().toInt(); pos += len }
            PATag.TIMEVAL -> pos += 8
            PATag.USEC -> pos += 8
            PATag.VOLUME -> pos += 4
            PATag.U64 -> pos += 8
            PATag.S64 -> pos += 8
            PATag.PROPLIST -> {
                while (true) {
                    val keyTag = readTag()
                    if (keyTag == PATag.STRING_NULL) break
                    check(keyTag == PATag.STRING)
                    // skip key NUL-terminated string
                    while (buf[pos] != 0.toByte()) pos++; pos++
                    // U32 value_len: tag(1) + 4 bytes
                    val lenTag = readTag()
                    check(lenTag == PATag.U32)
                    val valueLen = readRawU32().toInt()
                    // ARBITRARY value: tag(1) + 4-byte length + data
                    val arbTag = readTag()
                    check(arbTag == PATag.ARBITRARY)
                    val arbLen = readRawU32().toInt()
                    pos += arbLen
                }
            }
            PATag.FORMAT_INFO -> {
                pos++ // encoding byte
                // followed by proplist entries (no outer PROPLIST tag here)
                while (true) {
                    val kt = readTag()
                    if (kt == PATag.STRING_NULL) break
                    check(kt == PATag.STRING)
                    while (buf[pos] != 0.toByte()) pos++; pos++
                    val lt = readTag(); check(lt == PATag.U32)
                    readRawU32() // value len (unused)
                    val at = readTag(); check(at == PATag.ARBITRARY)
                    val al = readRawU32().toInt()
                    pos += al
                }
            }
            else -> error("Unknown tag 0x${tag.toString(16)} at pos ${pos - 1}")
        }
    }
}
