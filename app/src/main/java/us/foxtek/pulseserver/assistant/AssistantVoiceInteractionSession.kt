package us.foxtek.pulseserver.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

private const val TAG = "AssistantVIS.Session"

/**
 * VoiceInteractionSession handles the UI overlay when the assistant is
 * explicitly invoked (long-press home, assist gesture, etc.).
 *
 * Phase 1: minimal implementation — just logs and closes.
 */
class AssistantVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "onShow flags=$showFlags")
        // Phase 1: immediately hide — no UI yet
        hide()
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "onHide")
    }
}
