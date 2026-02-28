package us.foxtek.pulseserver.ui

import android.content.Context
import us.foxtek.pulseserver.proto.PAConst

private const val PREFS_NAME = "pulse_server_prefs"
private const val KEY_PORT   = "server_port"

object PortPreferences {
    fun load(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PORT, PAConst.DEFAULT_PORT)
    }

    fun save(context: Context, port: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PORT, port)
            .apply()
    }
}
