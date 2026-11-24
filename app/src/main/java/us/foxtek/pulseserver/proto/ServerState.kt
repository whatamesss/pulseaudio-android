package us.foxtek.pulseserver.proto

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared audio state across all concurrent PulseClientHandler instances.
 * Volume values are in PA units (0..65536, where 65536 = 100%).
 */
object ServerAudioState {
    val sinkVolume = AtomicLong(PAConst.VOLUME_NORM)
    val sinkMuted  = AtomicBoolean(false)
}
