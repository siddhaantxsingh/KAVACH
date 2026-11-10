package kavach.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kavach.shared.Speaker
import kavach.shared.Ui

/**
 * KAVACH Dialer (opt-in): Shield can be chosen as the phone's call app (ROLE_DIALER).
 * Then KAVACH sees every call directly and can:
 *  - show a big-button call screen with the live KAVACH level;
 *  - for an UNKNOWN number, say out loud "KAVACH is turning on the speaker so the Guardian can
 *    listen" and switch the call to speaker (setting, on by default) - this removes the
 *    "victim must remember to press speaker" step;
 *  - on STOP, offer a one-tap "End call".
 * Android still never gives call AUDIO to an app; the Guardian phone listens through the speaker.
 */
object Dialer {
    @Volatile var call: Call? = null
    @Volatile var service: KavachInCallService? = null
    @Volatile var unknown = true
    @Volatile var number: String = ""
    @Volatile var speakerAuto = false

    fun isDefault(ctx: Context): Boolean =
        (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == ctx.packageName

    fun autoSpeaker(ctx: Context) = ctx.getSharedPreferences("kavach", Context.MODE_PRIVATE).getBoolean("auto_speaker", true)

    fun requestRole(a: Activity) {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = a.getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER))
                a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 41)
        } else {
            @Suppress("DEPRECATION")
            a.startActivity(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, a.packageName))
        }
    }

    fun isContact(ctx: Context, num: String): Boolean {
        if (num.isBlank() || ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (_: Throwable) { false }
    }

    fun end() { call?.let { if (it.state == Call.STATE_RINGING) it.reject(false, null) else it.disconnect() } }
}

class KavachInCallService : InCallService() {
    private var speaker: Speaker? = null
    private val main = Handler(Looper.getMainLooper())

    private val cb = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) {
            if (state == Call.STATE_ACTIVE) onActive(c)
            InCallActivity.refresh()
        }
    }

    override fun onCreate() { super.onCreate(); speaker = Speaker(this) } // TTS needs a moment to initialise

    override fun onCallAdded(c: Call) {
        Dialer.call = c; Dialer.service = this; Dialer.speakerAuto = false
        Dialer.number = c.details.handle?.schemeSpecificPart ?: ""
        Dialer.unknown = !Dialer.isContact(this, Dialer.number)
        c.registerCallback(cb)
        val outgoing = Build.VERSION.SDK_INT >= 29 && c.details.callDirection == Call.Details.DIRECTION_OUTGOING
        ShieldHub.onCallStarted(this, Dialer.number, Dialer.unknown, outgoing)
        startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (c.state == Call.STATE_ACTIVE) onActive(c)
    }

    private fun onActive(c: Call) {
        if (!Dialer.unknown || Dialer.speakerAuto || !Dialer.autoSpeaker(this)) return
        Dialer.speakerAuto = true
        val hi = ShieldHub.lang(this) == "hi"
        // spoken notice first, so the user knows why the speaker turned on
        val sp = speaker ?: Speaker(this).also { speaker = it }
        sp.speak(if (hi) "अनजान नंबर। कवच स्पीकर चालू कर रहा है ताकि गार्डियन सुन सके।"
                 else "Unknown number. KAVACH is turning on the speaker so the Guardian can listen.", if (hi) "hi" else "en")
        main.postDelayed({ if (Dialer.call === c) setAudioRoute(CallAudioState.ROUTE_SPEAKER) }, 2500)
    }

    override fun onCallRemoved(c: Call) {
        c.unregisterCallback(cb)
        if (Dialer.call === c) { Dialer.call = null; ShieldHub.onCallEnded(this) }
        InCallActivity.finishAll()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) { InCallActivity.refresh() }

    override fun onDestroy() { speaker?.shutdown(); Dialer.service = null; super.onDestroy() }
}

/** Big-button call screen. */
class InCallActivity : Activity(), ShieldHub.Listener {
    private lateinit var who: TextView
    private lateinit var level: TextView
    private lateinit var stopBox: LinearLayout
    private lateinit var speakerBtn: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        Ui.keepScreenOn(this)
        Ui.theme(this)
        current = this
        val hi = ShieldHub.lang(this) == "hi"
        val col = Ui.column(this, 20)
        who = Ui.text(this, "", 30f, bold = true).apply { gravity = Gravity.CENTER }
        col.addView(who)
        level = Ui.banner(this); col.addView(level)
        stopBox = Ui.card(this).apply { visibility = android.view.View.GONE
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.dp(this@InCallActivity, 20).toFloat(); setColor(Ui.BAD) } }
        stopBox.addView(Ui.text(this, if (hi) "रुकिए! यह कॉल स्कैम लग रहा है।" else "STOP! This call looks like a scam.", 26f, true, Color.WHITE))
        stopBox.addView(Ui.solid(this, if (hi) "कॉल काटें" else "END CALL NOW", Color.WHITE, Ui.BAD) { Dialer.end() }.apply { textSize = 28f })
        col.addView(stopBox)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.solid(this, if (hi) "उठाएँ" else "Answer", Ui.GOOD, Color.WHITE) { Dialer.call?.answer(VideoProfile.STATE_AUDIO_ONLY) }.apply { textSize = 22f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speakerBtn = Ui.button(this, "") { toggleSpeaker() }.apply { textSize = 24f }
        row.addView(speakerBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        col.addView(Ui.solid(this, if (hi) "कॉल काटें" else "End call", Ui.BAD, Color.WHITE) { Dialer.end() }.apply { textSize = 28f })
        col.addView(Ui.text(this, if (hi) "कवच कॉल की आवाज़ रिकॉर्ड नहीं करता।" else "KAVACH never records the call.", 14f, color = Color.DKGRAY))
        setContentView(Ui.scroll(this, col))
        render()
    }

    private fun toggleSpeaker() {
        val s = Dialer.service ?: return
        val on = s.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
        s.setAudioRoute(if (on) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
    }

    private fun render() {
        val hi = ShieldHub.lang(this) == "hi"
        val c = Dialer.call
        val state = when (c?.state) {
            Call.STATE_RINGING -> if (hi) "आ रही कॉल" else "Incoming call"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> if (hi) "लगा रहे हैं…" else "Calling…"
            Call.STATE_ACTIVE -> if (hi) "बात हो रही है" else "On call"
            else -> ""
        }
        who.text = (Dialer.number.ifBlank { if (hi) "छुपा नंबर" else "Hidden number" }) + "\n" +
            (if (Dialer.unknown) (if (hi) "अनजान नंबर" else "Unknown number") else (if (hi) "संपर्क" else "Contact")) + " · " + state
        val lv = if (ShieldHub.guardianLevel == "stop") "stop" else ShieldHub.last?.level ?: "none"
        Ui.setBanner(level, lv, ShieldHub.lang(this))
        stopBox.visibility = if (lv == "stop") android.view.View.VISIBLE else android.view.View.GONE
        val sp = Dialer.service?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
        speakerBtn.text = if (sp) (if (hi) "स्पीकर: चालू" else "Speaker: ON") else (if (hi) "स्पीकर: बंद" else "Speaker: OFF")
    }

    override fun onChanged() { runOnUiThread { render() } }
    override fun onResume() { super.onResume(); ShieldHub.addListener(this); render() }
    override fun onPause() { ShieldHub.removeListener(this); super.onPause() }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

    companion object {
        @Volatile private var current: InCallActivity? = null
        fun refresh() { current?.let { a -> a.runOnUiThread { a.render() } } }
        fun finishAll() { current?.let { a -> a.runOnUiThread { a.finish() } } }
    }
}

/** Keypad (needed to hold the dialer role) + the opt-in switches. */
class DialerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hi = ShieldHub.lang(this) == "hi"
        Ui.theme(this)
        val col = Ui.column(this, 20)
        col.addView(Ui.header(this, "KAVACH Phone", if (hi) "अनजान कॉल पर अपने-आप सुरक्षा" else "Safer calls from unknown numbers"))
        val num = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; textSize = 30f
            setText(intent?.data?.schemeSpecificPart ?: "") }
        col.addView(num)
        col.addView(Ui.primary(this, if (hi) "कॉल करें" else "Call") {
            val n = num.text.toString().trim()
            if (n.isEmpty()) return@primary
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 5); return@primary
            }
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel", n, null), Bundle())
        }.apply { textSize = 26f })
        val isDef = Dialer.isDefault(this)
        col.addView(Ui.text(this, if (isDef) (if (hi) "कवच अभी आपका फ़ोन ऐप है।" else "KAVACH is your phone app.")
            else (if (hi) "कवच को फ़ोन ऐप बनाने से अनजान कॉल अपने-आप स्पीकर पर आएँगी और STOP पर एक बटन से कॉल कटेगी।"
                  else "Make KAVACH your phone app: unknown calls go on speaker by themselves and STOP gets a one-tap End call."), 16f))
        if (!isDef) col.addView(Ui.primary(this, if (hi) "कवच को फ़ोन ऐप बनाएँ" else "Make KAVACH my phone app") { Dialer.requestRole(this) })
        val p = getSharedPreferences("kavach", Context.MODE_PRIVATE)
        col.addView(Ui.button(this, (if (hi) "अनजान कॉल पर अपने-आप स्पीकर: " else "Auto speaker for unknown calls: ") +
            if (Dialer.autoSpeaker(this)) "ON" else "OFF") {
            p.edit().putBoolean("auto_speaker", !Dialer.autoSpeaker(this)).apply(); recreate()
        })
        setContentView(Ui.scroll(this, col))
    }
}

/** When KAVACH is NOT the phone app: a nudge to press speaker on an unknown call. */
object SpeakerNudge {
    fun show(ctx: Context) {
        val hi = ShieldHub.lang(ctx) == "hi"
        Ui.ensureChannel(ctx, "nudge", "KAVACH call tips", NotificationManager.IMPORTANCE_HIGH)
        val pi = PendingIntent.getActivity(ctx, 9, Intent(ctx, DialerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, "nudge").setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentTitle(if (hi) "अनजान नंबर: स्पीकर चालू करें" else "Unknown number: turn on the speaker")
            .setContentText(if (hi) "ताकि कवच गार्डियन बातचीत सुनकर आपको स्कैम से बचा सके।" else "So KAVACH Guardian can listen and warn you about scams.")
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(60_000).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(77, n)
    }
}
