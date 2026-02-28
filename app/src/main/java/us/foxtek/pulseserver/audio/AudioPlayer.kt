package us.foxtek.pulseserver.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import us.foxtek.pulseserver.proto.PASampleFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioPlayer"

/**
 * Wraps AudioTrack for PCM playback.
 * All formats are converted to S16LE before writing to avoid JNI float-write SIGSEGV.
 * write() is called directly from the PA read loop (blocking write, naturally paced).
 */
class AudioPlayer(
    private val sampleRate: Int,
    private val channels: Int,
    private val paFormat: Int = PASampleFormat.S16LE,
    private val minBufferSizeProvider: (Int, Int, Int) -> Int = { rate, mask, encoding ->
        AudioTrack.getMinBufferSize(rate, mask, encoding)
    },
    private val trackFactory: (AudioAttributes, AudioFormat, Int, Int, Int) -> AudioTrack =
        { attrs, fmt, bufSize, mode, sessionId ->
            AudioTrack(attrs, fmt, bufSize, mode, sessionId)
        }
) {
    private var track: AudioTrack? = null

    private val bytesPerFrame: Int get() = when (paFormat) {
        PASampleFormat.FLOAT32LE, PASampleFormat.FLOAT32BE -> 4 * channels
        PASampleFormat.S32LE,     PASampleFormat.S32BE     -> 4 * channels
        else                                               -> 2 * channels
    }

    fun start() {
        val channelMask = if (channels == 1)
            AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        // Always S16LE — avoids JNI float write SIGSEGV on this device
        val minBuf = minBufferSizeProvider(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, sampleRate * 2 * channels / 5) // ~200ms

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build()

        track = trackFactory(
            attrs,
            fmt,
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track?.play()
        Log.i(TAG, "AudioTrack started: rate=$sampleRate ch=$channels paFormat=$paFormat bufSize=$bufSize")
    }

    fun pause() {
        try { track?.pause() } catch (e: Exception) { Log.w(TAG, "pause: ${e.message}") }
    }

    fun setVolume(vol: Float) { track?.setVolume(vol.coerceIn(0f, 1f)) }
    fun setMute(mute: Boolean) { track?.setVolume(if (mute) 0f else 1f) }

    /** Convert incoming PCM to S16LE for AudioTrack. */
    private fun toS16(pcm: ByteArray): ByteArray = when (paFormat) {
        PASampleFormat.FLOAT32LE, PASampleFormat.FLOAT32BE -> {
            val bb = ByteBuffer.wrap(pcm).order(
                if (paFormat == PASampleFormat.FLOAT32LE) ByteOrder.LITTLE_ENDIAN
                else ByteOrder.BIG_ENDIAN)
            val out = ByteBuffer.allocate((pcm.size / 4) * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 4)
                out.putShort((bb.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.array()
        }
        PASampleFormat.S32LE, PASampleFormat.S32BE -> {
            val bb = ByteBuffer.wrap(pcm).order(
                if (paFormat == PASampleFormat.S32LE) ByteOrder.LITTLE_ENDIAN
                else ByteOrder.BIG_ENDIAN)
            val out = ByteBuffer.allocate(pcm.size / 2).order(ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 4) out.putShort((bb.int ushr 16).toShort())
            out.array()
        }
        PASampleFormat.S16BE -> {
            val out = pcm.copyOf()
            var i = 0
            while (i < out.size - 1) { val t = out[i]; out[i] = out[i+1]; out[i+1] = t; i += 2 }
            out
        }
        else -> pcm // S16LE — pass through
    }

    /** Write PCM to speaker. Called from PA read loop; blocks until hardware consumes data. */
    fun write(pcm: ByteArray) {
        val t = track ?: return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return
        val s16 = toS16(pcm)
        var offset = 0
        while (offset < s16.size) {
            val n = t.write(s16, offset, s16.size - offset)
            if (n < 0) { Log.w(TAG, "write error $n"); break }
            offset += n
        }
    }

    fun stop() {
        try { track?.stop(); track?.release() }
        catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
        track = null
        Log.i(TAG, "AudioTrack stopped")
    }
}
