package com.andrerinas.openheadunit.decoder

import android.content.Context
import android.media.AudioManager
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The MG4's cabin mic only reaches an app recording for Android Auto once the audio HAL's speech
 * path is switched on; MG's own Android Auto (AllGo AapAudioRecorder) does this around every
 * recording, and without it the recording is silent.
 */
object CarVoicePath {
    private var enabledKey: String? = null

    fun parameter(wireless: Boolean, enabled: Boolean) =
        "${if (wireless) "SSE_AA_VR_Wifi" else "SSE_AA_VR"}=$enabled"

    @Synchronized
    fun enable(context: Context, wireless: Boolean) {
        if (enabledKey != null) return
        val param = parameter(wireless, enabled = true)
        try {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setParameters(param)
            enabledKey = param.substringBefore("=")
            AppLog.i("CarVoicePath: set $param")
            Thread.sleep(100) // As AllGo does before it starts recording.
        } catch (e: Exception) {
            AppLog.w("CarVoicePath: could not set $param: ${e.message}")
        }
    }

    @Synchronized
    fun disable(context: Context) {
        val key = enabledKey ?: return
        enabledKey = null
        try {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setParameters("$key=false")
            AppLog.i("CarVoicePath: set $key=false")
        } catch (e: Exception) {
            AppLog.w("CarVoicePath: could not clear $key: ${e.message}")
        }
    }
}
