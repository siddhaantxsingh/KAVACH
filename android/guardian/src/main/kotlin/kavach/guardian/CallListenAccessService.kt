package kavach.guardian

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * One phone, one app: during a phone call Android gives the microphone to the call, and every
 * other app records silence. The one documented exception is an accessibility service
 * (developer.android.com/media/platform/sharing-audio-input, "Voice call + ordinary app"):
 * while this service is enabled, KAVACH can keep hearing a call the user has put on speaker.
 *
 * The service does nothing else: it asks for no screen content (canRetrieveWindowContent=false),
 * performs no gestures and handles no events. Audio is processed in memory and never stored.
 */
class CallListenAccessService : AccessibilityService() {
    override fun onServiceConnected() { connected = true }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { connected = false; super.onDestroy() }

    companion object {
        @Volatile var connected = false
    }
}
