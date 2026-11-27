package us.foxtek.pulseserver.audio

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded PCM queue with oldest-audio eviction.
 *
 * Capacity is measured in bytes rather than packet count. A packet larger
 * than the whole capacity is still accepted on its own so very small buffer
 * settings play audio instead of silently dropping every write.
 */
class PcmAudioQueue(
    private val maxBytes: Int
) {
    private val queue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private val lock = Object()

    /** Number of packets (frames) evicted because the queue was full. */
    val droppedFrames = AtomicLong(0)

    init {
        require(maxBytes > 0) { "Maximum queue size must be positive" }
    }

    fun offer(pcm: ByteArray): Boolean = synchronized(lock) {
        if (pcm.size > maxBytes) {
            // The packet cannot fit within the configured capacity. Keep it
            // anyway (evicting anything older) so a tiny buffer still plays.
            droppedFrames.addAndGet(queue.size.toLong())
            queue.clear()
            queue.addLast(pcm)
            queuedBytes = pcm.size
            lock.notifyAll()
            return@synchronized true
        }

        while (queuedBytes + pcm.size > maxBytes) {
            val removed = queue.pollFirst() ?: break
            queuedBytes -= removed.size
            droppedFrames.incrementAndGet()
        }

        queue.addLast(pcm)
        queuedBytes += pcm.size
        lock.notifyAll()
        true
    }

    fun take(): ByteArray = synchronized(lock) {
        while (queue.isEmpty()) lock.wait()
        val pcm = queue.removeFirst()
        queuedBytes -= pcm.size
        pcm
    }

    fun clear() = synchronized(lock) {
        queue.clear()
        queuedBytes = 0
        lock.notifyAll()
    }

    fun size(): Int = synchronized(lock) { queue.size }

    fun queuedBytes(): Int = synchronized(lock) { queuedBytes }

    fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }
}
