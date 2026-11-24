package us.foxtek.pulseserver.proto

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PulseAudio wire frame (20-byte descriptor + payload).
 *
 * @param channel  0xFFFFFFFF = command packet; otherwise audio stream channel ID
 * @param flags    seek mode (low 8 bits) + SHM flags (high bits)
 * @param offsetHi high 32 bits of seek offset
 * @param offsetLo low  32 bits of seek offset
 * @param payload  the raw bytes (tagstruct or PCM)
 */
data class PulseFrame(
    val channel: Long,
    val flags: Long,
    val offsetHi: Long,
    val offsetLo: Long,
    val payload: ByteArray
) {
    val isCommand: Boolean get() = channel == PAConst.CHANNEL_CMD

    companion object {
        const val DESCRIPTOR_SIZE = 20

        /**
         * Upper bound for a single frame payload. PulseAudio packets are normally
         * well under 100 KiB; anything larger is either a bug or an attempt to
         * exhaust memory with a 4-byte length field.
         */
        const val MAX_PAYLOAD_SIZE = 8 * 1024 * 1024

        /** Read one complete frame from an InputStream (blocking). */
        fun read(input: InputStream): PulseFrame {
            val header = readFully(input, DESCRIPTOR_SIZE, "frame header")
            val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val length    = bb.int.toLong() and 0xFFFFFFFFL
            val channel   = bb.int.toLong() and 0xFFFFFFFFL
            val offsetHi  = bb.int.toLong() and 0xFFFFFFFFL
            val offsetLo  = bb.int.toLong() and 0xFFFFFFFFL
            val flags     = bb.int.toLong() and 0xFFFFFFFFL

            if (length > MAX_PAYLOAD_SIZE) {
                throw java.io.IOException(
                    "Frame payload too large: $length bytes (max $MAX_PAYLOAD_SIZE)"
                )
            }

            val payload = readFully(input, length.toInt(), "frame payload")
            return PulseFrame(channel, flags, offsetHi, offsetLo, payload)
        }

        private fun readFully(input: InputStream, size: Int, what: String): ByteArray {
            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buffer, read, size - read)
                if (n < 0) throw java.io.EOFException("Connection closed while reading $what")
                read += n
            }
            return buffer
        }

        /** Build a command frame (CHANNEL=0xFFFFFFFF, FLAGS=0, OFFSETs=0). */
        fun command(tagstruct: ByteArray): PulseFrame =
            PulseFrame(PAConst.CHANNEL_CMD, 0L, 0L, 0L, tagstruct)

        /** Build an audio memblock frame. */
        fun audio(channelId: Int, pcm: ByteArray, seekMode: Long = 0L): PulseFrame =
            PulseFrame(channelId.toLong(), seekMode, 0L, 0L, pcm)
    }

    /** Write this frame to an OutputStream. */
    fun writeTo(output: OutputStream) {
        val bb = ByteBuffer.allocate(DESCRIPTOR_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(payload.size)
        bb.putInt(channel.toInt())
        bb.putInt(offsetHi.toInt())
        bb.putInt(offsetLo.toInt())
        bb.putInt(flags.toInt())
        output.write(bb.array())
        output.write(payload)
        output.flush()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PulseFrame) return false
        return channel == other.channel && flags == other.flags && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int = 31 * channel.hashCode() + payload.contentHashCode()
}
