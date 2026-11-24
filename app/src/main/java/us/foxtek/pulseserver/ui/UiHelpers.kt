package us.foxtek.pulseserver.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal fun hasRequiredPermissions(context: Context): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun buildConnectionHint(ip: String, port: Int, cookieHex: String): String = """
    On your Linux PC, install this server's auth cookie:

    mkdir -p ~/.config/pulse
    printf '%s' '$cookieHex' | xxd -r -p > ~/.config/pulse/cookie

    Then add to /etc/pulse/default.pa:

    load-module module-tunnel-sink   server=tcp:$ip:$port
    load-module module-tunnel-source server=tcp:$ip:$port

    Or play one file with paplay:
    PULSE_SERVER=tcp:$ip:$port paplay /path/to/file.wav
""".trimIndent()
