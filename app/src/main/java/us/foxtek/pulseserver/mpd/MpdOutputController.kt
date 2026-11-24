package us.foxtek.pulseserver.mpd

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Keeps the configured MPD output in sync with the server lifecycle: enabled
 * while the PulseAudio server runs, disabled once it stops.
 */
class MpdOutputController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var active = false

    fun enable() {
        val config = MpdPreferences.load(context)
        if (!config.isConfigured) return
        active = true
        scope.launch(Dispatchers.IO) {
            MpdClient.setOutputEnabled(config, true)
                .onFailure { Log.w(TAG, "MPD enable failed for '${config.outputName}'", it) }
        }
    }

    /**
     * Runs before the server socket is closed so MPD stops writing into a dead
     * tunnel. Bounded by [MpdClient]'s socket timeouts.
     */
    fun disable() {
        if (!active) return
        active = false
        val config = MpdPreferences.load(context)
        if (!config.isConfigured) return
        runBlocking {
            withContext(Dispatchers.IO) {
                MpdClient.setOutputEnabled(config, false)
                    .onFailure { Log.w(TAG, "MPD disable failed for '${config.outputName}'", it) }
            }
        }
    }

    private companion object {
        const val TAG = "MpdOutput"
    }
}
