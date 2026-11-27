package us.foxtek.pulseserver.audio

/**
 * Playback buffer sizing shared by the UI, the PA protocol handler, and
 * [AudioPlayer].
 */
object AudioBuffer {
    const val MIN_MILLIS = 0
    const val MAX_MILLIS = 3_000
    const val DEFAULT_MILLIS = 1_000

    fun clampMillis(millis: Int): Int = millis.coerceIn(MIN_MILLIS, MAX_MILLIS)

    /** Bytes needed to hold [millis] of PCM, never less than one sample frame. */
    fun bytesForMillis(millis: Int, sampleRate: Int, bytesPerFrame: Int): Int {
        val ms = millis.coerceAtLeast(0)
        val bytes = sampleRate.toLong() * bytesPerFrame * ms / 1000
        return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            .coerceAtLeast(bytesPerFrame)
    }

    /** Duration of [bytes] of PCM, rounded down to whole milliseconds. */
    fun millisForBytes(bytes: Long, sampleRate: Int, bytesPerFrame: Int): Int {
        if (bytes <= 0 || sampleRate <= 0 || bytesPerFrame <= 0) return 0
        return (bytes * 1000 / (sampleRate.toLong() * bytesPerFrame))
            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
}
