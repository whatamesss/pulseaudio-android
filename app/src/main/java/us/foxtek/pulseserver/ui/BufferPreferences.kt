package us.foxtek.pulseserver.ui

import android.content.Context
import us.foxtek.pulseserver.audio.AudioBuffer

private const val PREFS_NAME = "pulse_server_prefs"
private const val KEY_BUFFER_MS = "audio_buffer_ms"

object BufferPreferences {
    fun load(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AudioBuffer.clampMillis(
            prefs.getInt(KEY_BUFFER_MS, AudioBuffer.DEFAULT_MILLIS)
        )
    }

    fun save(context: Context, millis: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BUFFER_MS, AudioBuffer.clampMillis(millis))
            .apply()
    }
}
