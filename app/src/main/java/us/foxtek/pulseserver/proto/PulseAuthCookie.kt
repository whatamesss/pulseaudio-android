package us.foxtek.pulseserver.proto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

private const val PREFS_NAME = "pulse_server_prefs"
private const val KEY_COOKIE = "auth_cookie"

/**
 * Persistent PulseAudio authentication cookie.
 *
 * PulseAudio clients send the 256-byte cookie during AUTH. The server rejects
 * every connection that does not present the exact same bytes, which replaces
 * the previous "accept any cookie" behaviour.
 */
object PulseAuthCookie {

    @Volatile
    private var cached: ByteArray? = null

    @Synchronized
    fun get(context: Context): ByteArray {
        cached?.let { return it.copyOf() }

        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_COOKIE, null)
        val cookie = stored
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == PAConst.COOKIE_LENGTH }
            ?: generate(prefs)

        cached = cookie
        return cookie.copyOf()
    }

    private fun generate(prefs: SharedPreferences): ByteArray {
        val cookie = ByteArray(PAConst.COOKIE_LENGTH)
        SecureRandom().nextBytes(cookie)
        prefs.edit()
            .putString(KEY_COOKIE, Base64.encodeToString(cookie, Base64.NO_WRAP))
            .apply()
        return cookie
    }

    /** Constant-time comparison to avoid leaking the cookie through timing. */
    fun matches(expected: ByteArray, provided: ByteArray): Boolean =
        MessageDigest.isEqual(expected, provided)

    fun toHex(cookie: ByteArray): String =
        cookie.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
