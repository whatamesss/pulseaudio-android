package us.foxtek.pulseserver.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServerAudioStateTest {

    @Test
    fun defaultsMatchPulseNormAndUnmuted() {
        resetState()
        assertEquals(PAConst.VOLUME_NORM, ServerAudioState.sinkVolume.get())
        assertFalse(ServerAudioState.sinkMuted.get())
    }

    @Test
    fun stateIsSharedAndMutable() {
        resetState()

        ServerAudioState.sinkVolume.set(12_345L)
        ServerAudioState.sinkMuted.set(true)

        assertEquals(12_345L, ServerAudioState.sinkVolume.get())
        assertEquals(true, ServerAudioState.sinkMuted.get())

        resetState()
    }

    private fun resetState() {
        ServerAudioState.sinkVolume.set(PAConst.VOLUME_NORM)
        ServerAudioState.sinkMuted.set(false)
    }
}
