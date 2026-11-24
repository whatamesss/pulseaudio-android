package us.foxtek.pulseserver.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "UiHelpers"

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

internal fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Ask the system to exempt this app from battery optimization. Falls back to
 * the general battery optimization settings screen if the direct request is
 * unavailable. Returns false when neither screen could be opened.
 */
internal fun requestIgnoreBatteryOptimizations(activity: Activity): Boolean {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${activity.packageName}"))
    return runCatching { activity.startActivity(direct) }
        .recoverCatching { error ->
            Log.w(TAG, "Direct battery exemption request unavailable", error)
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        .isSuccess
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun buildConnectionHint(ip: String, port: Int): String = """
    On your Linux PC, add to /etc/pulse/default.pa:

    load-module module-tunnel-sink   server=tcp:$ip:$port
    load-module module-tunnel-source server=tcp:$ip:$port

    Or play one file with paplay:
    PULSE_SERVER=tcp:$ip:$port paplay /path/to/file.wav
""".trimIndent()
