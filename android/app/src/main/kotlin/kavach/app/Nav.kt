package kavach.app

import android.app.Activity
import android.content.Intent
import kavach.shared.Ui

/** The one bottom bar of the KAVACH app. "Calls" is the call-listening screen (GuardianActivity,
 *  in the app module that includes this library); the others are tabs of MainActivity. */
object Nav {
    const val HOME = 0
    const val CALLS = 1
    const val CHECK = 2
    const val ACTIVITY = 3
    const val SETTINGS = 4
    const val CALLS_ACTIVITY = "kavach.guardian.GuardianActivity"

    fun labels(hi: Boolean) = if (hi) listOf("होम", "कॉल", "जाँचें", "गतिविधि", "सेटिंग") else listOf("Home", "Calls", "Check", "Activity", "Settings")
    val icons get() = listOf(Ui.Ic.home, Ui.Ic.phoneCall, Ui.Ic.search, Ui.Ic.bell, Ui.Ic.settings)

    /** Switch tab without an animation, so the bar feels like one screen. */
    fun go(from: Activity, index: Int, extras: Intent.() -> Unit = {}) {
        val i = if (index == CALLS) Intent().setClassName(from, CALLS_ACTIVITY)
        else Intent(from, MainActivity::class.java).putExtra("tab", index).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        i.extras()
        from.startActivity(i)
        @Suppress("DEPRECATION") from.overridePendingTransition(0, 0)
        if (from !is MainActivity) { from.finish(); @Suppress("DEPRECATION") from.overridePendingTransition(0, 0) }
    }
}
