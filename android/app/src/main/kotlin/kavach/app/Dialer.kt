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
    private lateinit var sub: TextView
    private lateinit var level: Ui.Hero
    private lateinit var stopBox: LinearLayout
    private lateinit var answerBtn: Ui.Btn
    private lateinit var speakerBtn: Ui.Btn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        Ui.keepScreenOn(this)
        Ui.theme(this)
        current = this
        val hi = ShieldHub.lang(this) == "hi"
        val col = Ui.column(this, 22)
        col.addView(Ui.space(this, 28))
        who = Ui.text(this, "", 32f, weight = 700).apply { gravity = Gravity.CENTER }
        col.addView(who)
        sub = Ui.text(this, "", 15f, color = Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, Ui.dp(this@InCallActivity, 22)) }
        col.addView(sub)
        level = Ui.banner(this, compact = true); col.addView(level)
        stopBox = Ui.card(this).apply { visibility = android.view.View.GONE; background = Ui.shape(this@InCallActivity, Ui.P.stop, 22f) }
        stopBox.addView(Ui.text(this, if (hi) "रुकिए! यह कॉल स्कैम लग रहा है।" else "Stop! This call looks like a scam.", 22f, color = Color.WHITE, weight = 700))
        stopBox.addView(Ui.solid(this, if (hi) "अभी कॉल काटें" else "End call now", Color.WHITE, Ui.P.stop, Ui.Ic.phoneOff) { Dialer.end() })
        col.addView(stopBox)
        col.addView(Ui.space(this, 10))
        answerBtn = Ui.solid(this, if (hi) "उठाएँ" else "Answer", 0xFF1F7A45.toInt(), Color.WHITE, Ui.Ic.phone) { Dialer.call?.answer(VideoProfile.STATE_AUDIO_ONLY) }
        speakerBtn = Ui.button(this, "", Ui.Ic.volume) { toggleSpeaker() }
        col.addView(Ui.row(this, answerBtn, speakerBtn))
        col.addView(Ui.solid(this, if (hi) "कॉल काटें" else "End call", Ui.P.stop, Color.WHITE, Ui.Ic.phoneOff) { Dialer.end() })
        col.addView(Ui.footnote(this, if (hi) "कवच कॉल की आवाज़ रिकॉर्ड नहीं करता" else "KAVACH never records the call"))
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
        who.text = Dialer.number.ifBlank { if (hi) "छुपा नंबर" else "Hidden number" }
        sub.text = listOf(if (Dialer.unknown) (if (hi) "अनजान नंबर" else "Unknown number") else (if (hi) "संपर्क" else "Contact"), state).filter { it.isNotEmpty() }.joinToString(" · ")
        val lv = if (ShieldHub.guardianLevel == "stop") "stop" else ShieldHub.last?.level ?: "none"
        if (lv == "stop" || lv == "caution") Ui.setBanner(level, lv, ShieldHub.lang(this))
        else Ui.setState(level, "ready", if (hi) "कवच इस कॉल पर नज़र रख रहा है" else "KAVACH is watching this call", if (hi) "अभी कोई खतरे का संकेत नहीं। यह गारंटी नहीं है।" else "No warning signs so far. Not a guarantee.")
        stopBox.visibility = if (lv == "stop") android.view.View.VISIBLE else android.view.View.GONE
        answerBtn.visibility = if (c?.state == Call.STATE_RINGING) android.view.View.VISIBLE else android.view.View.GONE
        val sp = Dialer.service?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
        speakerBtn.text = if (sp) (if (hi) "स्पीकर चालू" else "Speaker on") else (if (hi) "स्पीकर बंद" else "Speaker off")
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
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); build() }
    override fun onResume() { super.onResume(); build() }

    private fun build() {
        val hi = ShieldHub.lang(this) == "hi"
        Ui.theme(this)
        val col = Ui.column(this)
        col.addView(Ui.backHeader(this, if (hi) "कवच फ़ोन" else "KAVACH Phone"))
        val card = Ui.card(this)
        val num = Ui.input(this, if (hi) "नंबर डालें" else "Enter a number", InputType.TYPE_CLASS_PHONE).apply {
            textSize = 26f; gravity = Gravity.CENTER; typeface = Ui.font(600); setText(intent?.data?.schemeSpecificPart ?: "") }
        card.addView(num)
        card.addView(Ui.primary(this, if (hi) "कॉल करें" else "Call", Ui.Ic.phone) {
            val n = num.text.toString().trim()
            if (n.isEmpty()) return@primary
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 5); return@primary
            }
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel", n, null), Bundle())
        })
        col.addView(card)
        col.addView(Ui.label(this, if (hi) "अनजान कॉल पर सुरक्षा" else "Protection on unknown calls"))
        val isDef = Dialer.isDefault(this)
        val p = getSharedPreferences("kavach", Context.MODE_PRIVATE)
        col.addView(Ui.group(this,
            if (isDef) Ui.status(this, if (hi) "कवच आपका फ़ोन ऐप है" else "KAVACH is your phone app", true, if (hi) "Stop पर एक बटन से कॉल कटेगी" else "A Stop warning gets a one-tap End call")
            else Ui.item(this, Ui.Ic.phone, if (hi) "कवच को फ़ोन ऐप बनाएँ" else "Make KAVACH your phone app",
                if (hi) "अनजान कॉल अपने-आप स्पीकर पर; Stop पर एक बटन से कॉल कटेगी" else "Unknown calls go on speaker; a Stop warning gets a one-tap End call",
                trailing = Ui.small(this, if (hi) "चुनें" else "Set") { Dialer.requestRole(this) }),
            Ui.item(this, Ui.Ic.volume, if (hi) "अपने-आप स्पीकर" else "Auto speaker", if (hi) "अनजान नंबर की कॉल स्पीकर पर रखें" else "Put calls from unknown numbers on speaker",
                trailing = Ui.toggle(this, Dialer.autoSpeaker(this), "Auto speaker") { p.edit().putBoolean("auto_speaker", it).apply() })))
        col.addView(Ui.footnote(this, if (hi) "कवच कॉल की आवाज़ रिकॉर्ड नहीं करता" else "KAVACH never records the call"))
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
