package us.foxtek.pulseserver.assistant

import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.util.Log

private const val TAG = "AssistantVIS"

/**
 * VoiceInteractionService entry point.
 *
 * The framework binds this service when it is set as the active
 * voice_interaction_service.  Holding this binding is what causes
 * the framework to grant CAPTURE_AUDIO_HOTWORD and related privileged
 * permissions to this package via the role/VIS grant path.
 *
 * Phase 1: minimal skeleton — proves the bind works and permissions are granted.
 * Phase 2: wire in openWakeWord engine once permissions confirmed.
 */
class AssistantVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "onReady — VoiceInteractionService bound by framework")

        // Phase 1: log the permission state so we can verify in logcat
        logPermissionState()
    }

    override fun onShutdown() {
        Log.i(TAG, "onShutdown")
        super.onShutdown()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    private fun logPermissionState() {
        val pm = packageManager
        val perms = listOf(
            "android.permission.CAPTURE_AUDIO_HOTWORD",
            "android.permission.MANAGE_HOTWORD_DETECTION",
            "android.permission.BIND_HOTWORD_DETECTION_SERVICE",
            "android.permission.MANAGE_VOICE_KEYPHRASES",
            "android.permission.SOUNDTRIGGER_DELEGATE_IDENTITY",
            "android.permission.RECORD_AUDIO",
        )
        for (perm in perms) {
            val result = pm.checkPermission(perm, packageName)
            val granted = result == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "  $perm => ${if (granted) "GRANTED" else "DENIED"}")
        }
    }
}
