package us.foxtek.pulseserver.audio

import org.junit.Assert.*
import org.junit.Test

class PcmAudioQueueTest {

    @Test
    fun offerAndTakePreserveFifoOrder() {
        val queue = PcmAudioQueue(3)
        val a = byteArrayOf(1)
        val b = byteArrayOf(2)

        queue.offer(a)
        queue.offer(b)

        assertArrayEquals(a, queue.take())
        assertArrayEquals(b, queue.take())
    }

    @Test
    fun fullQueueDropsOldestFrame() {
        val queue = PcmAudioQueue(2)

        queue.offer(byteArrayOf(1))
        queue.offer(byteArrayOf(2))
        queue.offer(byteArrayOf(3))

        assertEquals(1, queue.droppedFrames.get())
        assertArrayEquals(byteArrayOf(2), queue.take())
        assertArrayEquals(byteArrayOf(3), queue.take())
    }

    @Test
    fun oversizedFrameIsKeptWhenItCannotFit() {
        val queue = PcmAudioQueue(2)

        queue.offer(byteArrayOf(1))
        assertTrue(queue.offer(byteArrayOf(2, 3, 4)))

        assertEquals(1, queue.droppedFrames.get())
        assertArrayEquals(byteArrayOf(2, 3, 4), queue.take())
    }

    @Test
    fun clearRemovesAllFrames() {
        val queue = PcmAudioQueue(4)

        queue.offer(byteArrayOf(1))
        queue.offer(byteArrayOf(2))
        queue.clear()

        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroCapacity() {
        PcmAudioQueue(0)
    }
}
