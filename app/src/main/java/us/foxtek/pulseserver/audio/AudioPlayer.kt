package us.foxtek.pulseserver.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import us.foxtek.pulseserver.proto.PASampleFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioPlayer"

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
    private var playbackJob: Job? = null
    private var pcmQueue: PcmAudioQueue? = null

    @Volatile
    private var playbackPaused = false

    private val bytesPerFrame: Int get() = when (paFormat) {
        PASampleFormat.FLOAT32LE, PASampleFormat.FLOAT32BE -> 4 * channels
        PASampleFormat.S32LE, PASampleFormat.S32BE -> 4 * channels
        else -> 2 * channels
    }

    fun start() {
        val channelMask = if (channels == 1)
            AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        val minBuf = minBufferSizeProvider(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate * 2 * channels / 5)

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

        playbackPaused = false
        track?.play()

        Log.i(TAG, "AudioTrack started: rate=$sampleRate ch=$channels paFormat=$paFormat bufSize=$bufSize")
    }

    /**
     * Starts a dedicated audio writer.
     * Network reception can now enqueue PCM without waiting for AudioTrack.
     */
    fun startBuffered(scope: CoroutineScope, bufferMillis: Int = 300) {
        if (track == null) start()

        val capacity = (
            sampleRate.toLong() * bytesPerFrame * bufferMillis / 1000
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(bytesPerFrame)

        pcmQueue = PcmAudioQueue(capacity)

        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Buffered playback started: capacity=$capacity bytes")

            try {
                while (isActive) {
                    if (playbackPaused) {
                        delay(10)
                        continue
                    }

                    val pcm = runInterruptible {
                        pcmQueue?.take() ?: return@runInterruptible null
                    } ?: continue

                    write(pcm)
                }
            } catch (e: InterruptedException) {
                // Expected during shutdown.
            } finally {
                Log.i(TAG, "Buffered playback worker stopped")
            }
        }
    }

    /** Non-blocking enqueue for the TCP receive loop. */
    fun enqueue(pcm: ByteArray): Boolean {
        return pcmQueue?.offer(pcm) ?: false
    }

    fun queuedBytes(): Int = pcmQueue?.queuedBytes() ?: 0

    fun droppedFrames(): Long = pcmQueue?.droppedFrames?.get() ?: 0L

    fun pause() {
        playbackPaused = true
        try {
            track?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "pause: ${e.message}")
        }
    }

    fun resume() {
        playbackPaused = false
        try {
            track?.play()
        } catch (e: Exception) {
            Log.w(TAG, "resume: ${e.message}")
        }
    }

    fun setVolume(vol: Float) {
        track?.setVolume(vol.coerceIn(0f, 1f))
    }

    fun setMute(mute: Boolean) {
        track?.setVolume(if (mute) 0f else 1f)
    }

    private fun toS16(pcm: ByteArray): ByteArray = when (paFormat) {
        PASampleFormat.FLOAT32LE, PASampleFormat.FLOAT32BE -> {
            val bb = ByteBuffer.wrap(pcm).order(
                if (paFormat == PASampleFormat.FLOAT32LE)
                    ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            )
            val out = ByteBuffer.allocate((pcm.size / 4) * 2)
                .order(ByteOrder.LITTLE_ENDIAN)

            while (bb.remaining() >= 4) {
                out.putShort(
                    (bb.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                )
            }
            out.array()
        }

        PASampleFormat.S32LE, PASampleFormat.S32BE -> {
            val bb = ByteBuffer.wrap(pcm).order(
                if (paFormat == PASampleFormat.S32LE)
                    ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            )
            val out = ByteBuffer.allocate(pcm.size / 2)
                .order(ByteOrder.LITTLE_ENDIAN)

            while (bb.remaining() >= 4) {
                out.putShort((bb.int ushr 16).toShort())
            }
            out.array()
        }

        PASampleFormat.S16BE -> {
            val out = pcm.copyOf()
            var i = 0
            while (i < out.size - 1) {
                val t = out[i]
                out[i] = out[i + 1]
                out[i + 1] = t
                i += 2
            }
            out
        }

        else -> pcm
    }

    /** Blocking write, called only by the playback worker in buffered mode. */
    fun write(pcm: ByteArray) {
        val t = track ?: return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return

        val s16 = toS16(pcm)
        var offset = 0

        while (offset < s16.size) {
            val n = t.write(s16, offset, s16.size - offset)
            if (n < 0) {
                Log.w(TAG, "AudioTrack write error $n")
                break
            }
            if (n == 0) break
            offset += n
        }
    }

    fun stop() {
        playbackPaused = true
        playbackJob?.cancel()
        playbackJob = null
        pcmQueue?.clear()
        pcmQueue = null

        try {
            track?.stop()
            track?.release()
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
        }

        track = null
        Log.i(TAG, "AudioTrack stopped")
    }
}
