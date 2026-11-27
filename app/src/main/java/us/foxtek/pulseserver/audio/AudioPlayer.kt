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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import us.foxtek.pulseserver.proto.PASampleFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioPlayer"

internal fun pcmBytesPerFrame(paFormat: Int, channels: Int): Int = when (paFormat) {
    PASampleFormat.FLOAT32LE, PASampleFormat.FLOAT32BE -> 4 * channels
    PASampleFormat.S32LE, PASampleFormat.S32BE -> 4 * channels
    else -> 2 * channels
}

@Suppress("TooManyFunctions")
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

    /** Set by stop(): the worker releases the track once its write/wait returns. */
    @Volatile
    private var releasePending = false

    @Volatile
    private var volume = 1f

    @Volatile
    private var muted = false

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

        releasePending = false
        track = trackFactory(
            attrs,
            fmt,
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        playbackPaused = false
        track?.play()
        applyVolume()

        Log.i(TAG, "AudioTrack started: rate=$sampleRate ch=$channels paFormat=$paFormat bufSize=$bufSize")
    }

    /**
     * Starts a dedicated audio writer.
     * Network reception can now enqueue PCM without waiting for AudioTrack.
     */
    fun startBuffered(scope: CoroutineScope, bufferMillis: Int = 300) {
        stopWorker()
        if (track == null) start()

        val capacity = AudioBuffer.bytesForMillis(
            bufferMillis, sampleRate, pcmBytesPerFrame(paFormat, channels)
        )

        pcmQueue = PcmAudioQueue(capacity)

        playbackJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Buffered playback started: capacity=$capacity bytes")

            try {
                while (isActive) {
                    val pcm = awaitPcm() ?: continue
                    write(pcm)
                }
            } catch (e: InterruptedException) {
                // Expected during shutdown.
            } finally {
                Log.i(TAG, "Buffered playback worker stopped")
                if (releasePending) releaseTrack()
            }
        }
    }

    /** Non-blocking enqueue for the TCP receive loop. */
    fun enqueue(pcm: ByteArray): Boolean {
        return pcmQueue?.offer(pcm) ?: false
    }

    fun queuedBytes(): Int = pcmQueue?.queuedBytes() ?: 0

    fun droppedFrames(): Long = pcmQueue?.droppedFrames?.get() ?: 0L

    /** Drop buffered audio without touching the AudioTrack (PA FLUSH). */
    fun flush() {
        pcmQueue?.clear()
    }

    /**
     * Wait until the queue has been consumed by the writer, or [timeoutMs]
     * elapses. Returns true when the queue is empty.
     */
    fun awaitDrained(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queuedBytes() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        return queuedBytes() == 0
    }

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
        if (!vol.isFinite()) return
        volume = vol.coerceIn(0f, 1f)
        applyVolume()
    }

    fun setMute(mute: Boolean) {
        muted = mute
        applyVolume()
    }

    private fun applyVolume() {
        track?.setVolume(if (muted) 0f else volume)
    }

    private suspend fun awaitPcm(): ByteArray? {
        val queue = if (playbackPaused) null else pcmQueue
        if (queue == null) {
            delay(10)
            return null
        }
        return runInterruptible { queue.take() }
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
        var writing = true

        while (offset < s16.size && writing) {
            val n = t.write(s16, offset, s16.size - offset)
            if (n > 0) {
                offset += n
            } else {
                writing = false
                if (n < 0) Log.w(TAG, "AudioTrack write error $n")
            }
        }
    }

    fun stop() {
        playbackPaused = true
        releasePending = true
        pcmQueue?.clear()
        pcmQueue = null

        val job = playbackJob
        playbackJob = null
        job?.cancel()

        if (job != null) {
            runBlocking { withTimeoutOrNull(1_000L) { job.join() } }
        }

        // The worker releases the track itself if it was inside write() when
        // the join timed out; releasing here would race with the native call.
        if (job == null || job.isCompleted) releaseTrack()
        Log.i(TAG, "AudioTrack stopped")
    }

    private fun stopWorker() {
        val job = playbackJob ?: return
        playbackJob = null
        job.cancel()
        runBlocking { withTimeoutOrNull(1_000L) { job.join() } }
    }

    @Synchronized
    private fun releaseTrack() {
        val t = track ?: return
        track = null
        try {
            t.stop()
            t.release()
        } catch (e: Exception) {
            Log.w(TAG, "release: ${e.message}")
        }
    }
}
