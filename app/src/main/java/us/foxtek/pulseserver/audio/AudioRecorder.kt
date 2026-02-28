package us.foxtek.pulseserver.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

private const val TAG = "AudioRecorder"

@SuppressLint("MissingPermission")
private fun createAudioRecordDefault(
    source: Int,
    rate: Int,
    channelMask: Int,
    encoding: Int,
    bufSize: Int
): AudioRecord = AudioRecord(source, rate, channelMask, encoding, bufSize)

/**
 * Wraps AudioRecord to capture mic audio and send it to a PA record client (the "source").
 * Format: S16LE, configurable sample rate and channel count.
 */
class AudioRecorder(
    private val sampleRate: Int,
    private val channels: Int,
    private val minBufferSizeProvider: (Int, Int, Int) -> Int = { rate, channelMask, encoding ->
        AudioRecord.getMinBufferSize(rate, channelMask, encoding)
    },
    private val recordFactory: (Int, Int, Int, Int, Int) -> AudioRecord = ::createAudioRecordDefault
) {
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val channelMask = if (channels == 1)
            AudioFormat.CHANNEL_IN_MONO
        else
            AudioFormat.CHANNEL_IN_STEREO

        val minBuf = minBufferSizeProvider(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Target ~40ms of ring buffer — low latency while avoiding underruns
        val targetBytes = (sampleRate * channels * 2 * 40L / 1000L).toInt()
        val bufSize = maxOf(minBuf, targetBytes)

        record = recordFactory(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (rate=$sampleRate ch=$channels)")
            record = null
            return
        }

        record?.startRecording()
        Log.i(TAG, "AudioRecord started: rate=$sampleRate ch=$channels bufSize=$bufSize")
    }

    /**
     * Read up to [buf].size bytes of PCM into [buf].
     * Returns the number of bytes read, or 0 on error.
     * Blocking call.
     */
    fun read(buf: ByteArray): Int {
        val r = record ?: return 0
        val n = r.read(buf, 0, buf.size)
        return if (n > 0) n else 0
    }

    fun stop() {
        try {
            record?.stop()
            record?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        record = null
        Log.i(TAG, "AudioRecord stopped")
    }
}
