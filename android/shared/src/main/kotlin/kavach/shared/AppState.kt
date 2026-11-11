package kavach.shared

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings

/** Small pieces of state both parts of the one KAVACH app read (the call listener writes them). */
object AppState {
    /** idle | standby | listening | demo | models_missing (written by the call listener) */
    @Volatile var callMode: String = "idle"
    @Volatile var callLevel: String = "none"
    /** The one Llama instance of the app (1.1 GB in memory): loaded by call protection and shared
     *  with the message/network explanations, never loaded twice. Null when not loaded. */
    @Volatile var llama: LlamaAdvisor? = null

    const val CALL_SERVICE = "kavach.guardian.GuardianService"
    const val ACTION_STANDBY = "kavach.guardian.STANDBY"
    const val ACTION_STOP = "kavach.guardian.STOP"
    const val ACCESS_SERVICE = "kavach.guardian.CallListenAccessService"

    /** Android gives the microphone to a phone call; only an accessibility service may keep
     *  hearing it at the same time (developer.android.com/media/platform/sharing-audio-input). */
    fun callAccessEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx.packageName, ACCESS_SERVICE)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun openCallAccessSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Call protection: the listener waits in the background and starts on a call (must be started
     *  while a KAVACH screen is visible: Android's rule for using the microphone later). */
    fun startCallProtection(ctx: Context, lang: String) {
        ctx.startForegroundService(Intent().setClassName(ctx, CALL_SERVICE).setAction(ACTION_STANDBY).putExtra("lang", lang))
        ctx.getSharedPreferences("guardian", Context.MODE_PRIVATE).edit().putBoolean("protect_calls", true).apply()
    }

    fun stopCallProtection(ctx: Context) {
        ctx.startService(Intent().setClassName(ctx, CALL_SERVICE).setAction(ACTION_STOP))
        ctx.getSharedPreferences("guardian", Context.MODE_PRIVATE).edit().putBoolean("protect_calls", false).apply()
    }

    fun inPhoneCall(ctx: Context): Boolean {
        val m = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }
}
