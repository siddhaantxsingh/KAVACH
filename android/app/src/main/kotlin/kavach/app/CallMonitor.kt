package kavach.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Call awareness for the user's phone: knows THAT a call is going on and whether the number
 * is unknown (not in contacts). It never hears the call — Android does not allow that for
 * normal apps; the Guardian phone does the listening.
 *
 * Uses the phone-state broadcast (READ_PHONE_STATE). The caller's number needs READ_CALL_LOG
 * on Android 9+, and the contacts check needs READ_CONTACTS; without them every call is
 * treated as unknown, which is safe because call awareness never raises an alarm on its own
 * (it only gives context to actions taken during the call).
 */
class CallMonitor(private val ctx: Context) {
    private var registered = false
    private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    private var ringingNumber: String? = null
    private var inCall = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> { if (!number.isNullOrBlank()) ringingNumber = number }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> if (!inCall) {
                    inCall = true
                    val outgoing = lastState == TelephonyManager.EXTRA_STATE_IDLE
                    val num = ringingNumber ?: number
                    val unknown = num.isNullOrBlank() || !isContact(num)
                    ShieldHub.onCallStarted(ctx, num, unknown, outgoing)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    if (inCall) ShieldHub.onCallEnded(ctx)
                    inCall = false
                    ringingNumber = null
                }
            }
            lastState = state
        }
    }

    fun start() {
        if (registered) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted: call awareness off"); return
        }
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(receiver, f)
        registered = true
    }

    fun stop() {
        if (registered) try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }
        registered = false
    }

    private fun isContact(number: String): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (t: Throwable) { false }
    }

    companion object { private const val TAG = "CallMonitor" }
}
