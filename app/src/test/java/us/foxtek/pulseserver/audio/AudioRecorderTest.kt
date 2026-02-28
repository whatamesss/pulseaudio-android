package us.foxtek.pulseserver.audio

import android.media.AudioRecord
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecorderTest {

    @Test
    fun startInitializesAndStartsRecording() {
        val audioRecord = mockk<AudioRecord>(relaxed = true)
        every { audioRecord.state } returns AudioRecord.STATE_INITIALIZED
        every { audioRecord.startRecording() } just runs

        val recorder = AudioRecorder(
            sampleRate = 44_100,
            channels = 1,
            minBufferSizeProvider = { _, _, _ -> 512 },
            recordFactory = { _, _, _, _, _ -> audioRecord }
        )

        recorder.start()
        verify { audioRecord.startRecording() }
    }

    @Test
    fun startDropsRecordWhenNotInitialized() {
        val audioRecord = mockk<AudioRecord>(relaxed = true)
        every { audioRecord.state } returns AudioRecord.STATE_UNINITIALIZED

        val recorder = AudioRecorder(
            sampleRate = 44_100,
            channels = 2,
            minBufferSizeProvider = { _, _, _ -> 256 },
            recordFactory = { _, _, _, _, _ -> audioRecord }
        )

        recorder.start()
        verify(exactly = 0) { audioRecord.startRecording() }
        assertEquals(0, recorder.read(ByteArray(64)))
    }

    @Test
    fun readReturnsZeroWhenNotStartedAndStopIsSafe() {
        val recorder = AudioRecorder(44_100, 1)
        val buf = ByteArray(256)
        assertEquals(0, recorder.read(buf))
        recorder.stop()
    }

    @Test
    fun readReturnsPositiveCountFromRecord() {
        val recorder = AudioRecorder(44_100, 1)
        val audioRecord = mockk<AudioRecord>(relaxed = true)
        every { audioRecord.read(any<ByteArray>(), 0, any()) } returns 64
        setPrivateRecord(recorder, audioRecord)

        assertEquals(64, recorder.read(ByteArray(256)))
    }

    @Test
    fun readReturnsZeroForNegativeRead() {
        val recorder = AudioRecorder(44_100, 1)
        val audioRecord = mockk<AudioRecord>(relaxed = true)
        every { audioRecord.read(any<ByteArray>(), 0, any()) } returns -3
        setPrivateRecord(recorder, audioRecord)

        assertEquals(0, recorder.read(ByteArray(256)))
    }

    @Test
    fun stopDelegatesToRecordAndClearsState() {
        val recorder = AudioRecorder(44_100, 1)
        val audioRecord = mockk<AudioRecord>(relaxed = true)
        every { audioRecord.stop() } just runs
        every { audioRecord.release() } just runs
        setPrivateRecord(recorder, audioRecord)

        recorder.stop()
        verify { audioRecord.stop() }
        verify { audioRecord.release() }
        assertEquals(0, recorder.read(ByteArray(32)))
    }

    @Test
    fun stopSwallowsRecordExceptions() {
        val recorder = AudioRecorder(44_100, 1)
        val audioRecord = mockk<AudioRecord>(relaxed = true)
        every { audioRecord.stop() } throws RuntimeException("boom")
        setPrivateRecord(recorder, audioRecord)

        recorder.stop()
        assertEquals(0, recorder.read(ByteArray(32)))
    }

    private fun setPrivateRecord(recorder: AudioRecorder, audioRecord: AudioRecord?) {
        val f = AudioRecorder::class.java.getDeclaredField("record")
        f.isAccessible = true
        f.set(recorder, audioRecord)
    }
}
