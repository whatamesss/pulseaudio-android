package us.foxtek.pulseserver.mpd

import android.content.Context

private const val PREFS_NAME = "pulse_server_prefs"
private const val KEY_HOST   = "mpd_host"
private const val KEY_PORT   = "mpd_port"
private const val KEY_OUTPUT = "mpd_output"

object MpdPreferences {

    const val DEFAULT_PORT = 6600

    data class Config(val host: String, val port: Int, val outputName: String) {
        val isConfigured: Boolean get() = host.isNotBlank() && outputName.isNotBlank()
    }

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Config(
            host = prefs.getString(KEY_HOST, "").orEmpty().trim(),
            port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
            outputName = prefs.getString(KEY_OUTPUT, "").orEmpty().trim(),
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_OUTPUT, config.outputName.trim())
            .apply()
    }

    /** Parses a `host` or `host:port` field value; defaults to [DEFAULT_PORT]. */
    fun configFrom(server: String, outputName: String): Config {
        val raw = server.trim()
        val separator = raw.lastIndexOf(':')
        val port = if (separator > 0) {
            raw.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..65535 }
        } else {
            null
        }
        return Config(
            host = if (port != null) raw.substring(0, separator) else raw,
            port = port ?: DEFAULT_PORT,
            outputName = outputName.trim(),
        )
    }
}
