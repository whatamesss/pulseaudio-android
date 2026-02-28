package us.foxtek.pulseserver.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServerAudioStateTest {

    @Test
    fun defaultsMatchPulseNormAndUnmuted() {
        resetState()
        assertEquals(PAConst.VOLUME_NORM, ServerAudioState.sinkVolume.get())
        assertEquals(PAConst.VOLUME_NORM, ServerAudioState.sourceVolume.get())
        assertFalse(ServerAudioState.sinkMuted.get())
        assertFalse(ServerAudioState.sourceMuted.get())
    }

    @Test
    fun stateIsSharedAndMutable() {
        resetState()

        ServerAudioState.sinkVolume.set(12_345L)
        ServerAudioState.sourceVolume.set(67_890L)
        ServerAudioState.sinkMuted.set(true)
        ServerAudioState.sourceMuted.set(true)

        assertEquals(12_345L, ServerAudioState.sinkVolume.get())
        assertEquals(67_890L, ServerAudioState.sourceVolume.get())
        assertEquals(true, ServerAudioState.sinkMuted.get())
        assertEquals(true, ServerAudioState.sourceMuted.get())

        resetState()
    }

    private fun resetState() {
        ServerAudioState.sinkVolume.set(PAConst.VOLUME_NORM)
        ServerAudioState.sourceVolume.set(PAConst.VOLUME_NORM)
        ServerAudioState.sinkMuted.set(false)
        ServerAudioState.sourceMuted.set(false)
    }
}
