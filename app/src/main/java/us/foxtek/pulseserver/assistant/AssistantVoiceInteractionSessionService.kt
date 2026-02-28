package us.foxtek.pulseserver.assistant

import android.content.Context
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

private const val TAG = "AssistantVIS.SessionSvc"

/**
 * Factory service that the framework calls to create VoiceInteractionSession instances.
 * Must be declared in the manifest and referenced from voice_interaction_service.xml.
 */
class AssistantVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession {
        Log.i(TAG, "onNewSession")
        return AssistantVoiceInteractionSession(this)
    }
}
