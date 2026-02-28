package us.foxtek.pulseserver.audio

import android.media.AudioTrack
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import us.foxtek.pulseserver.proto.PASampleFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class AudioPlayerTest {

    @Test
    fun startCreatesTrackAndPlays() {
        val track = mockk<AudioTrack>(relaxed = true)
        val player = AudioPlayer(
            sampleRate = 48_000,
            channels = 2,
            paFormat = PASampleFormat.S16LE,
            minBufferSizeProvider = { _, _, _ -> 1024 },
            trackFactory = { _, _, _, _, _ -> track }
        )

        player.start()
        verify { track.play() }
    }

    @Test
    fun bytesPerFrameMatchesFormats() {
        assertEquals(8, invokeBytesPerFrame(AudioPlayer(44_100, 2, PASampleFormat.FLOAT32LE)))
        assertEquals(8, invokeBytesPerFrame(AudioPlayer(44_100, 2, PASampleFormat.S32BE)))
        assertEquals(4, invokeBytesPerFrame(AudioPlayer(44_100, 2, PASampleFormat.S16LE)))
    }

    @Test
    fun toS16ConvertsFloat32LittleEndian() {
        val player = AudioPlayer(44_100, 1, PASampleFormat.FLOAT32LE)
        val input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(-1f)
            .putFloat(0f)
            .putFloat(1f)
            .array()

        val out = invokeToS16(player, input)
        val shorts = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32767, shorts.short.toInt())
        assertEquals(0, shorts.short.toInt())
        assertEquals(32767, shorts.short.toInt())
    }

    @Test
    fun toS16ConvertsS32BigEndian() {
        val player = AudioPlayer(44_100, 1, PASampleFormat.S32BE)
        val input = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x7FFF0000)
            .putInt(0x80000000.toInt())
            .array()

        val out = invokeToS16(player, input)
        val shorts = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x7FFF.toShort().toInt(), shorts.short.toInt())
        assertEquals(0x8000.toShort().toInt(), shorts.short.toInt())
    }

    @Test
    fun toS16SwapsBigEndianS16() {
        val player = AudioPlayer(44_100, 1, PASampleFormat.S16BE)
        val input = byteArrayOf(0x12, 0x34, 0x56, 0x78).map { it.toByte() }.toByteArray()
        val out = invokeToS16(player, input)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56).map { it.toByte() }.toByteArray(), out)
    }

    @Test
    fun toS16PassesThroughS16LeAndNullTrackOperationsAreSafe() {
        val player = AudioPlayer(44_100, 2, PASampleFormat.S16LE)
        val input = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(input, invokeToS16(player, input))

        player.setVolume(2f)
        player.setMute(true)
        player.pause()
        player.write(byteArrayOf(1, 2, 3))
        player.stop()
    }

    @Test
    fun writeUsesTrackAndLoopsUntilAllBytesConsumed() {
        val player = AudioPlayer(44_100, 1, PASampleFormat.S16LE)
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.playState } returns AudioTrack.PLAYSTATE_PLAYING
        every { track.write(any<ByteArray>(), any(), any()) } returnsMany listOf(2, 2)

        setPrivateTrack(player, track)
        player.write(byteArrayOf(1, 2, 3, 4))

        verify(exactly = 2) { track.write(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun writeStopsOnNegativeWriteResult() {
        val player = AudioPlayer(44_100, 1, PASampleFormat.S16LE)
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.playState } returns AudioTrack.PLAYSTATE_PLAYING
        every { track.write(any<ByteArray>(), any(), any()) } returns -1

        setPrivateTrack(player, track)
        player.write(byteArrayOf(1, 2, 3, 4))

        verify(exactly = 1) { track.write(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun writeReturnsWhenTrackNotPlaying() {
        val player = AudioPlayer(44_100, 1, PASampleFormat.S16LE)
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.playState } returns AudioTrack.PLAYSTATE_STOPPED

        setPrivateTrack(player, track)
        player.write(byteArrayOf(1, 2, 3, 4))

        verify(exactly = 0) { track.write(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun pauseVolumeMuteAndStopDelegateToTrack() {
        val player = AudioPlayer(44_100, 1, PASampleFormat.S16LE)
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.pause() } just runs
        every { track.stop() } just runs
        every { track.release() } just runs
        every { track.setVolume(any()) } returns 0

        setPrivateTrack(player, track)

        player.pause()
        player.setVolume(2f)
        player.setMute(true)
        player.stop()

        verify { track.pause() }
        verify { track.setVolume(1f) }
        verify { track.setVolume(0f) }
        verify { track.stop() }
        verify { track.release() }
    }

    @Test
    fun stopHandlesTrackExceptions() {
        val player = AudioPlayer(44_100, 1, PASampleFormat.S16LE)
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.stop() } throws RuntimeException("boom")

        setPrivateTrack(player, track)
        player.stop()
    }

    private fun invokeToS16(player: AudioPlayer, pcm: ByteArray): ByteArray {
        val m = AudioPlayer::class.java.getDeclaredMethod("toS16", ByteArray::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(player, pcm) as ByteArray
    }

    private fun invokeBytesPerFrame(player: AudioPlayer): Int {
        val m = AudioPlayer::class.java.getDeclaredMethod("getBytesPerFrame")
        m.isAccessible = true
        return m.invoke(player) as Int
    }

    private fun setPrivateTrack(player: AudioPlayer, track: AudioTrack?) {
        val f = AudioPlayer::class.java.getDeclaredField("track")
        f.isAccessible = true
        f.set(player, track)
    }
}
