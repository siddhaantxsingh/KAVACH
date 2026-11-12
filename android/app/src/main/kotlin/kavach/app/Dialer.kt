package kavach.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArrayList
import kavach.shared.Speaker
import kavach.shared.Ui

/**
 * KAVACH Phone (opt-in): KAVACH can be chosen as the phone's call app (ROLE_DIALER). It then has
 * to do everything a phone app does, or calls cannot be answered:
 *  - incoming calls: a full-screen call screen AND a high-priority call notification with
 *    Answer / Decline (Xiaomi and Android 14 can block a background app from opening a screen, so the
 *    notification is the path that always works);
 *  - in a call: mute, speaker, keypad tones, hold, end, call timer, SIM choice on dual-SIM phones,
 *    proximity sensor turns the screen off at the ear;
 *  - Keypad, Recents (the phone's call log) and Contacts;
 *  - for an UNKNOWN number: say "KAVACH is turning on the speaker so it can listen" and switch to
 *    speaker (setting, on by default), show the live KAVACH level, one-tap End call on STOP.
 * Telecom itself rings, vibrates and writes the call log. KAVACH never records the call.
 */
object Dialer {
    val calls = CopyOnWriteArrayList<Call>()
    @Volatile var service: KavachInCallService? = null
    @Volatile var speakerAuto = false
    private val names = HashMap<String, String?>()
    private val connectedAt = HashMap<Call, Long>()

    /** The call the screen is about: ringing first, then active, then one being set up. */
    val call: Call? get() = calls.firstOrNull { it.state == Call.STATE_RINGING }
        ?: calls.firstOrNull { it.state == Call.STATE_ACTIVE }
        ?: calls.firstOrNull { it.state == Call.STATE_SELECT_PHONE_ACCOUNT || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING }
        ?: calls.firstOrNull()

    fun number(c: Call?): String = c?.details?.handle?.schemeSpecificPart ?: ""
    val number: String get() = number(call)
    val unknown: Boolean get() = contactName(service, number) == null

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

    /** Contact display name for a number, or null if it is not a saved contact (cached per call). */
    fun contactName(ctx: Context?, num: String): String? {
        if (ctx == null || num.isBlank()) return null
        synchronized(names) { if (names.containsKey(num)) return names[num] }
        val name = if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) null else try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Throwable) { null }
        synchronized(names) { names[num] = name }
        return name
    }

    fun isContact(ctx: Context, num: String): Boolean = contactName(ctx, num) != null
    fun clearNames() = synchronized(names) { names.clear() }

    fun markActive(c: Call) { synchronized(connectedAt) { connectedAt.putIfAbsent(c, System.currentTimeMillis()) } }
    fun forget(c: Call) { synchronized(connectedAt) { connectedAt.remove(c) } }
    fun elapsed(c: Call?): Long? = c?.let { synchronized(connectedAt) { connectedAt[it] } }?.let { System.currentTimeMillis() - it }

    fun answer() { calls.firstOrNull { it.state == Call.STATE_RINGING }?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun decline() { calls.firstOrNull { it.state == Call.STATE_RINGING }?.reject(false, null) }
    fun end() { call?.let { if (it.state == Call.STATE_RINGING) it.reject(false, null) else it.disconnect() } }

    /** Start a call. As the default phone app Telecom hands the call back to KavachInCallService. */
    fun place(a: Activity, num: String) {
        val n = num.trim()
        if (n.isEmpty()) return
        if (a.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 5); return
        }
        try { (a.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel", n, null), Bundle()) }
        catch (_: SecurityException) { a.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null))) }
    }

    fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    /**
     * Xiaomi's own permissions, which Android's public API cannot see: 10021 = "display pop-up windows while
     * running in the background", 10020 = "show on lock screen". Read through AppOpsManager's hidden
     * checkOpNoThrow(int, int, String); null when the phone does not answer (then we cannot tell).
     */
    fun miuiOpsAllowed(ctx: Context): Boolean? = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val m = android.app.AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        val uid = android.os.Process.myUid()
        listOf(10021, 10020).all { (m.invoke(ops, it, uid, ctx.packageName) as Int) == android.app.AppOpsManager.MODE_ALLOWED }
    } catch (_: Throwable) { null }

    /** Xiaomi blocks "pop-up windows while running in the background" and "show on lock screen" by default. */
    fun openPopupPermission(a: Activity) {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", a.packageName)
        try { a.startActivity(miui) } catch (_: Throwable) {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
        }
    }

    fun canFullScreen(ctx: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

    fun openFullScreenPermission(a: Activity) {
        if (Build.VERSION.SDK_INT >= 34) try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", a.packageName, null)))
        } catch (_: Throwable) { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null))) }
    }
}

/** Call notifications: incoming (full-screen intent + Answer/Decline) and ongoing (Hang up). */
object CallNotifier {
    private const val CH_IN = "kavach_call_incoming"
    private const val CH_ON = "kavach_call_ongoing"
    private const val ID = 4401

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_IN) == null) nm.createNotificationChannel(
            NotificationChannel(CH_IN, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false) // Telecom already rings and vibrates
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        if (nm.getNotificationChannel(CH_ON) == null) nm.createNotificationChannel(
            NotificationChannel(CH_ON, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
    }

    private fun action(ctx: Context, what: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, CallActionReceiver::class.java).setAction(what),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun screen(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 4402, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun who(ctx: Context, c: Call): Pair<String, String> {
        val hi = ShieldHub.lang(ctx) == "hi"
        val num = Dialer.number(c)
        val name = Dialer.contactName(ctx, num)
        val title = name ?: num.ifBlank { if (hi) "छुपा नंबर" else "Hidden number" }
        val tag = if (name == null) (if (hi) "अनजान नंबर · कवच तैयार है" else "Unknown number · KAVACH is ready") else num
        return title to tag
    }

    fun incoming(ctx: Context, c: Call) {
        channels(ctx)
        val hi = ShieldHub.lang(ctx) == "hi"
        val (title, tag) = who(ctx, c)
        // Answer opens the call screen directly (Android 12+ blocks starting a screen from a receiver)
        val answer = PendingIntent.getActivity(ctx, 4403, Intent(ctx, InCallActivity::class.java).setAction(CallActionReceiver.ANSWER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = action(ctx, CallActionReceiver.DECLINE, 2)
        val b = Notification.Builder(ctx, CH_IN).setSmallIcon(Ui.Ic.phoneCall)
            .setContentTitle(title).setContentText((if (hi) "आ रही कॉल · " else "Incoming call · ") + tag)
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setAutoCancel(false)
            .setContentIntent(screen(ctx)).setFullScreenIntent(screen(ctx), true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= 31) {
            val person = Person.Builder().setName(title).setImportant(true).build()
            b.setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
        } else {
            b.addAction(Notification.Action.Builder(null, if (hi) "काटें" else "Decline", decline).build())
            b.addAction(Notification.Action.Builder(null, if (hi) "उठाएँ" else "Answer", answer).build())
        }
        post(ctx, b)
    }

    fun ongoing(ctx: Context, c: Call) {
        channels(ctx)
        val hi = ShieldHub.lang(ctx) == "hi"
        val (title, tag) = who(ctx, c)
        val state = when (c.state) {
            Call.STATE_ACTIVE -> if (hi) "बात हो रही है" else "On call"
            Call.STATE_HOLDING -> if (hi) "होल्ड पर" else "On hold"
            else -> if (hi) "लगा रहे हैं…" else "Calling…"
        }
        val b = Notification.Builder(ctx, CH_ON).setSmallIcon(Ui.Ic.phoneCall)
            .setContentTitle(title).setContentText("$state · $tag")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(screen(ctx))
            .addAction(Notification.Action.Builder(null, if (hi) "कॉल काटें" else "Hang up", action(ctx, CallActionReceiver.HANG_UP, 3)).build())
        Dialer.elapsed(c)?.let { b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - it).setShowWhen(true) }
        post(ctx, b)
    }

    private fun post(ctx: Context, b: Notification.Builder) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(ID, b.build()) } catch (_: Throwable) {
            // e.g. a phone that rejects CallStyle: post the same notification without the style
            try { nm.notify(ID, b.setStyle(null).build()) } catch (_: Throwable) { }
        }
    }

    fun cancel(ctx: Context) { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) }
}

/** Answer / Decline / Hang up from the notification (not exported: only our own PendingIntents reach it). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            ANSWER -> Dialer.answer()
            DECLINE -> Dialer.decline()
            HANG_UP -> Dialer.end()
        }
    }
    companion object {
        const val ANSWER = "kavach.call.ANSWER"
        const val DECLINE = "kavach.call.DECLINE"
        const val HANG_UP = "kavach.call.HANG_UP"
    }
}

class KavachInCallService : InCallService() {
    private var speaker: Speaker? = null
    private val main = Handler(Looper.getMainLooper())

    private val cb = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) {
            if (state == Call.STATE_ACTIVE) { Dialer.markActive(c); onActive(c) }
            notifyFor(Dialer.call)
            InCallActivity.refresh()
        }
        override fun onDetailsChanged(c: Call, details: Call.Details) { InCallActivity.refresh() }
    }

    override fun onCreate() { super.onCreate(); speaker = Speaker(this) } // TTS needs a moment to initialise

    override fun onCallAdded(c: Call) {
        Dialer.service = this
        if (Dialer.calls.isEmpty()) { Dialer.speakerAuto = false; Dialer.clearNames() }
        Dialer.calls.add(c)
        c.registerCallback(cb)
        val num = Dialer.number(c)
        val unknown = !Dialer.isContact(this, num)
        val outgoing = Build.VERSION.SDK_INT >= 29 && c.details.callDirection == Call.Details.DIRECTION_OUTGOING
        ShieldHub.onCallStarted(this, num, unknown, outgoing)
        notifyFor(c)
        // Allowed for an InCallService, but some phones (Xiaomi "pop-up" setting) still block it:
        // the incoming notification above is the fallback that always works.
        try { startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
        if (c.state == Call.STATE_ACTIVE) { Dialer.markActive(c); onActive(c) }
    }

    private fun notifyFor(c: Call?) {
        if (c == null) { CallNotifier.cancel(this); return }
        if (c.state == Call.STATE_RINGING) CallNotifier.incoming(this, c) else CallNotifier.ongoing(this, c)
    }

    private fun onActive(c: Call) {
        val num = Dialer.number(c)
        if (Dialer.isContact(this, num) || Dialer.speakerAuto || !Dialer.autoSpeaker(this)) return
        Dialer.speakerAuto = true
        val hi = ShieldHub.lang(this) == "hi"
        // spoken notice first, so the user (and anyone nearby) knows why the speaker turned on
        val sp = speaker ?: Speaker(this).also { speaker = it }
        sp.speak(if (hi) "अनजान नंबर। कवच स्पीकर चालू कर रहा है ताकि वह सुन सके।"
                 else "Unknown number. KAVACH is turning on the speaker so it can listen.", if (hi) "hi" else "en")
        main.postDelayed({ if (Dialer.calls.contains(c)) setAudioRoute(CallAudioState.ROUTE_SPEAKER) }, 2500)
    }

    override fun onCallRemoved(c: Call) {
        c.unregisterCallback(cb)
        Dialer.calls.remove(c); Dialer.forget(c)
        if (Dialer.calls.isEmpty()) {
            CallNotifier.cancel(this)
            ShieldHub.onCallEnded(this)
            InCallActivity.finishAll()
        } else {
            notifyFor(Dialer.call); InCallActivity.refresh()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) { InCallActivity.refresh() }

    override fun onDestroy() { speaker?.shutdown(); Dialer.service = null; super.onDestroy() }
}

/** The call screen: who is calling, KAVACH's level, Answer / Decline, and in-call controls. */
class InCallActivity : Activity(), ShieldHub.Listener {
    private lateinit var who: TextView
    private lateinit var sub: TextView
    private lateinit var timer: TextView
    private lateinit var level: Ui.Hero
    private lateinit var stopBox: LinearLayout
    private lateinit var ringBox: LinearLayout
    private lateinit var simBox: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var keypad: View
    private lateinit var tones: TextView
    private lateinit var muteBtn: Ui.Btn
    private lateinit var speakerBtn: Ui.Btn
    private lateinit var holdBtn: Ui.Btn
    private lateinit var keyBtn: Ui.Btn
    private lateinit var endBtn: Ui.Btn
    private val main = Handler(Looper.getMainLooper())
    private var prox: PowerManager.WakeLock? = null
    private val tick = object : Runnable { override fun run() { renderTimer(); main.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        Ui.keepScreenOn(this)
        Ui.theme(this)
        current = this
        val hi = ShieldHub.lang(this) == "hi"
        val col = Ui.column(this, 22)
        col.addView(Ui.space(this, 24))
        who = Ui.text(this, "", 30f, weight = 700).apply { gravity = Gravity.CENTER }
        col.addView(who)
        sub = Ui.text(this, "", 15f, color = Ui.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(sub)
        timer = Ui.text(this, "", 15f, color = Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, Ui.dp(this@InCallActivity, 16)) }
        col.addView(timer)
        level = Ui.banner(this, compact = true); col.addView(level)
        stopBox = Ui.card(this).apply { visibility = View.GONE; background = Ui.shape(this@InCallActivity, Ui.P.stop, 22f) }
        stopBox.addView(Ui.text(this, if (hi) "रुकिए! यह कॉल स्कैम लग रहा है।" else "Stop! This call looks like a scam.", 22f, color = Color.WHITE, weight = 700))
        stopBox.addView(Ui.solid(this, if (hi) "अभी कॉल काटें" else "End call now", Color.WHITE, Ui.P.stop, Ui.Ic.phoneOff) { Dialer.end() })
        col.addView(stopBox)
        col.addView(Ui.space(this, 8))

        // ringing: Decline | Answer
        ringBox = Ui.row(this,
            Ui.solid(this, if (hi) "काटें" else "Decline", Ui.P.stop, Color.WHITE, Ui.Ic.phoneOff) { Dialer.decline() },
            Ui.solid(this, if (hi) "उठाएँ" else "Answer", 0xFF1F7A45.toInt(), Color.WHITE, Ui.Ic.phone) { Dialer.answer() })
        col.addView(ringBox)

        // dual SIM: Telecom asks the phone app which SIM to use
        simBox = Ui.card(this).apply { visibility = View.GONE }
        col.addView(simBox)

        // in call: Mute | Speaker / Keypad | Hold
        muteBtn = Ui.button(this, "", Ui.Ic.micOff) { toggleMute() }
        speakerBtn = Ui.button(this, "", Ui.Ic.volume) { toggleSpeaker() }
        keyBtn = Ui.button(this, if (hi) "कीपैड" else "Keypad", Ui.Ic.phone) { keypad.visibility = if (keypad.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        holdBtn = Ui.button(this, "", Ui.Ic.play) { toggleHold() }
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(Ui.row(this@InCallActivity, muteBtn, speakerBtn)); addView(Ui.row(this@InCallActivity, keyBtn, holdBtn)) }
        col.addView(controls)
        tones = Ui.text(this, "", 22f, weight = 600).apply { gravity = Gravity.CENTER }
        keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(tones); addView(PhoneUi.keyGrid(this@InCallActivity, small = true) { d ->
                tones.append(d.toString()); Dialer.call?.let { it.playDtmfTone(d); main.postDelayed({ it.stopDtmfTone() }, 150) } }) }
        col.addView(keypad)
        endBtn = Ui.solid(this, if (hi) "कॉल काटें" else "End call", Ui.P.stop, Color.WHITE, Ui.Ic.phoneOff) { Dialer.end() }
        col.addView(endBtn)
        col.addView(Ui.footnote(this, if (hi) "कवच कॉल की आवाज़ रिकॉर्ड नहीं करता" else "KAVACH never records the call"))
        setContentView(Ui.scroll(this, col))
        answerIfAsked(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); answerIfAsked(intent); render() }

    private fun answerIfAsked(i: Intent?) { if (i?.action == CallActionReceiver.ANSWER) { Dialer.answer(); i.action = null } }

    private fun toggleMute() { Dialer.service?.let { it.setMuted(it.callAudioState?.isMuted != true) } }

    private fun toggleSpeaker() {
        val s = Dialer.service ?: return
        val on = s.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
        s.setAudioRoute(if (on) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
    }

    private fun toggleHold() { Dialer.call?.let { if (it.state == Call.STATE_HOLDING) it.unhold() else if (it.state == Call.STATE_ACTIVE) it.hold() } }

    @SuppressLint("MissingPermission")
    private fun renderSims(c: Call) {
        val hi = ShieldHub.lang(this) == "hi"
        simBox.removeAllViews()
        simBox.addView(Ui.text(this, if (hi) "किस सिम से कॉल करें?" else "Call with which SIM?", 18f, weight = 600))
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handles: List<PhoneAccountHandle> = try {
            @Suppress("DEPRECATION")
            c.details.intentExtras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS)
                ?: tm.callCapablePhoneAccounts
        } catch (_: Throwable) { emptyList() }
        handles.forEachIndexed { i, h ->
            val label = try { tm.getPhoneAccount(h)?.label?.toString() } catch (_: Throwable) { null } ?: "SIM ${i + 1}"
            simBox.addView(Ui.button(this, label, Ui.Ic.signal) { c.phoneAccountSelected(h, false) })
        }
        if (handles.isEmpty()) simBox.addView(Ui.text(this, if (hi) "सिम नहीं मिली" else "No SIM found", 14f, color = Ui.MUTED))
    }

    private fun renderTimer() {
        val c = Dialer.call
        val ms = Dialer.elapsed(c)
        if (c?.state == Call.STATE_ACTIVE && ms != null) timer.text = DateUtils.formatElapsedTime(ms / 1000)
        else if (c?.state != Call.STATE_HOLDING) timer.text = ""
    }

    private fun render() {
        val hi = ShieldHub.lang(this) == "hi"
        val c = Dialer.call
        val st = c?.state
        val state = when (st) {
            Call.STATE_RINGING -> if (hi) "आ रही कॉल" else "Incoming call"
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> if (hi) "लगा रहे हैं…" else "Calling…"
            Call.STATE_ACTIVE -> if (hi) "बात हो रही है" else "On call"
            Call.STATE_HOLDING -> if (hi) "होल्ड पर" else "On hold"
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> if (hi) "कॉल खत्म" else "Call ended"
            else -> ""
        }
        val num = Dialer.number(c)
        val name = Dialer.contactName(this, num)
        who.text = name ?: num.ifBlank { if (hi) "छुपा नंबर" else "Hidden number" }
        val waiting = if (Dialer.calls.size > 1) (if (hi) " · ${Dialer.calls.size} कॉल" else " · ${Dialer.calls.size} calls") else ""
        sub.text = listOf(if (name == null) (if (hi) "अनजान नंबर" else "Unknown number") else num, state).filter { it.isNotEmpty() }.joinToString(" · ") + waiting
        if (st == Call.STATE_HOLDING) timer.text = if (hi) "होल्ड पर" else "On hold"
        val lv = if (ShieldHub.guardianLevel == "stop") "stop" else ShieldHub.last?.level ?: "none"
        if (lv == "stop" || lv == "caution") Ui.setBanner(level, lv, ShieldHub.lang(this))
        else Ui.setState(level, "ready", if (hi) "कवच इस कॉल पर नज़र रख रहा है" else "KAVACH is watching this call",
            if (hi) "अभी कोई खतरे का संकेत नहीं। यह गारंटी नहीं है।" else "No warning signs so far. Not a guarantee.")
        stopBox.visibility = if (lv == "stop") View.VISIBLE else View.GONE
        val ringing = st == Call.STATE_RINGING
        val choosing = st == Call.STATE_SELECT_PHONE_ACCOUNT
        ringBox.visibility = if (ringing) View.VISIBLE else View.GONE
        simBox.visibility = if (choosing) View.VISIBLE else View.GONE
        if (choosing && c != null) renderSims(c)
        controls.visibility = if (ringing || choosing) View.GONE else View.VISIBLE
        if (ringing || choosing) keypad.visibility = View.GONE
        endBtn.visibility = if (ringing) View.GONE else View.VISIBLE
        val audio = Dialer.service?.callAudioState
        val sp = audio?.route == CallAudioState.ROUTE_SPEAKER
        speakerBtn.text = if (sp) (if (hi) "स्पीकर चालू" else "Speaker on") else (if (hi) "स्पीकर" else "Speaker")
        muteBtn.text = if (audio?.isMuted == true) (if (hi) "म्यूट है" else "Muted") else (if (hi) "म्यूट" else "Mute")
        holdBtn.text = if (st == Call.STATE_HOLDING) (if (hi) "वापस लें" else "Resume") else (if (hi) "होल्ड" else "Hold")
        holdBtn.isEnabled = st == Call.STATE_ACTIVE || st == Call.STATE_HOLDING
        holdBtn.alpha = if (holdBtn.isEnabled) 1f else 0.5f
        renderTimer()
        proximity(st == Call.STATE_ACTIVE && !sp)
    }

    /** Screen off at the ear (earpiece only), like any phone app. */
    private fun proximity(on: Boolean) {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        if (on && prox == null) prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "kavach:incall").apply { acquire(3 * 60 * 60 * 1000L) }
        if (!on) { prox?.let { if (it.isHeld) it.release() }; prox = null }
    }

    override fun onChanged() { runOnUiThread { render() } }
    override fun onResume() { super.onResume(); ShieldHub.addListener(this); main.post(tick); render() }
    override fun onPause() { ShieldHub.removeListener(this); main.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { proximity(false); if (current === this) current = null; super.onDestroy() }

    companion object {
        @Volatile private var current: InCallActivity? = null
        fun refresh() { current?.let { a -> a.runOnUiThread { a.render() } } }
        fun finishAll() { current?.let { a -> a.runOnUiThread { a.proximity(false); a.finish() } } }
    }
}

/** Shared bits of the phone screens. */
object PhoneUi {
    private val LETTERS = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")

    /** 4 × 3 dial pad. */
    fun keyGrid(a: Activity, small: Boolean = false, onKey: (Char) -> Unit): GridLayout = GridLayout(a).apply {
        columnCount = 3; useDefaultMargins = false
        val keys = "123456789*0#"
        val h = Ui.dp(a, if (small) 58 else 72)
        for (k in keys) {
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = h
                background = Ui.shape(a, Ui.P.cardHi, 20f, Ui.P.stroke)
                addView(TextView(a).apply { text = k.toString(); textSize = if (small) 24f else 28f; typeface = Ui.font(500); setTextColor(Ui.P.text); gravity = Gravity.CENTER })
                LETTERS[k]?.let { l -> addView(TextView(a).apply { text = l; textSize = 11f; typeface = Ui.font(600); setTextColor(Ui.P.muted); gravity = Gravity.CENTER; letterSpacing = 0.15f }) }
                isClickable = true; isFocusable = true; contentDescription = k.toString()
                setOnClickListener { onKey(k) }
                if (k == '0') setOnLongClickListener { onKey('+'); true }
            }
            val m = Ui.dp(a, 5)
            addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = h; setMargins(m, m, m, m) })
        }
    }

    class Entry(val title: String, val subtitle: String, val number: String, val missed: Boolean = false)

    /** Simple list (recycles nothing: lists are capped at a few hundred rows). */
    fun list(a: Activity, items: List<Entry>, onTap: (Entry) -> Unit): ListView = ListView(a).apply {
        divider = null; setBackgroundColor(Ui.P.bg); isVerticalScrollBarEnabled = false
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
                val e = items[p]
                return Ui.item(a, if (e.missed) Ui.Ic.phoneOff else Ui.Ic.phone, e.title, e.subtitle,
                    trailing = Ui.small(a, if (ShieldHub.lang(a) == "hi") "कॉल" else "Call") { onTap(e) },
                    fg = if (e.missed) Ui.P.bad else Ui.P.goldText, bg = if (e.missed) Ui.P.badSoft else Ui.P.goldSoft) { onTap(e) }
            }
        }
    }
}

/** KAVACH Phone: Keypad, Recents, Contacts and Setup. Also the screen the DIAL intent opens. */
class DialerActivity : Activity() {
    private var tab = 0
    private var digits = StringBuilder()
    private var query = ""
    private val hi get() = ShieldHub.lang(this) == "hi"
    private fun t(h: String, e: String) = if (hi) h else e

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        askPermissions()
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handle(intent); build() }
    override fun onResume() { super.onResume(); build() }

    private fun handle(i: Intent?) {
        val n = i?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
        if (!n.isNullOrBlank()) { digits = StringBuilder(n); tab = 0 }
        else if (i?.action == Intent.ACTION_VIEW && i.type == CallLog.Calls.CONTENT_TYPE) tab = 1 // "missed call" notification
    }

    private fun askPermissions() {
        val want = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 6)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); build()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun build() {
        Ui.theme(this)
        val labels = listOf(t("कीपैड", "Keypad"), t("हाल की", "Recents"), t("संपर्क", "Contacts"), t("सेटअप", "Setup"))
        val icons = listOf(Ui.Ic.phone, Ui.Ic.history, Ui.Ic.users, Ui.Ic.settings)
        val (root, holder) = Ui.tabs(this, labels, icons, tab) { tab = it; build() }
        holder.addView(when (tab) { 1 -> recents(); 2 -> contacts(); 3 -> setup(); else -> keypad() })
        setContentView(root)
    }

    private fun header(col: LinearLayout, title: String, sub: String) {
        col.addView(Ui.text(this, title, 26f, weight = 700).apply { setPadding(0, Ui.dp(this@DialerActivity, 14), 0, 0) })
        col.addView(Ui.text(this, sub, 14f, color = Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(this@DialerActivity, 10)) })
    }

    private fun defaultBanner(col: LinearLayout) {
        if (Dialer.isDefault(this)) return
        col.addView(Ui.group(this, Ui.item(this, Ui.Ic.phone, t("कवच को फ़ोन ऐप बनाएँ", "Make KAVACH your phone app"),
            t("तभी कवच कॉल उठा और दिखा सकता है", "Needed to show and answer calls here"),
            trailing = Ui.small(this, t("चुनें", "Set")) { Dialer.requestRole(this) })))
    }

    // ----------------------------------------------------------------- keypad
    private fun keypad(): View {
        val col = Ui.column(this)
        header(col, t("कवच फ़ोन", "KAVACH Phone"), t("अनजान नंबर पर कवच साथ सुनता है", "KAVACH listens along on unknown numbers"))
        defaultBanner(col)
        val shown = Ui.text(this, digits.toString(), 32f, weight = 600).apply { gravity = Gravity.CENTER; minHeight = Ui.dp(this@DialerActivity, 56) }
        val name = Ui.text(this, "", 14f, color = Ui.MUTED).apply { gravity = Gravity.CENTER }
        fun refresh() {
            shown.text = digits.toString()
            name.text = if (digits.length >= 6) Dialer.contactName(this, digits.toString()) ?: "" else ""
        }
        col.addView(shown); col.addView(name)
        col.addView(PhoneUi.keyGrid(this) { k -> digits.append(k); refresh() })
        val back = Ui.button(this, t("मिटाएँ", "Delete"), Ui.Ic.x) { if (digits.isNotEmpty()) digits.setLength(digits.length - 1); refresh() }
        back.setOnLongClickListener { digits.setLength(0); refresh(); true }
        val call = Ui.solid(this, t("कॉल करें", "Call"), 0xFF1F7A45.toInt(), Color.WHITE, Ui.Ic.phone) {
            if (digits.isEmpty()) lastDialed()?.let { digits = StringBuilder(it); refresh() } else Dialer.place(this, digits.toString()) }
        col.addView(Ui.row(this, back, call))
        refresh()
        return Ui.scroll(this, col)
    }

    /** Pressing Call with nothing typed fills in the last number dialled, like most phone apps. */
    private fun lastDialed(): String? {
        if (!has(Manifest.permission.READ_CALL_LOG)) return null
        return try {
            contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER), "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}",
                null, "${CallLog.Calls.DATE} DESC")?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Throwable) { null }
    }

    // ----------------------------------------------------------------- recents
    private fun recents(): View {
        val col = Ui.column(this).apply { setPadding(paddingLeft, paddingTop, paddingRight, 0) }
        header(col, t("हाल की कॉल", "Recents"), t("फ़ोन की कॉल सूची", "Your phone's call history"))
        defaultBanner(col)
        if (!has(Manifest.permission.READ_CALL_LOG)) {
            col.addView(Ui.empty(this, Ui.Ic.history, t("कॉल सूची की अनुमति चाहिए", "Call history permission needed"), t("सेटअप में अनुमति दें", "Allow it in Setup")))
            col.addView(Ui.primary(this, t("अनुमति दें", "Allow")) { askPermissions() })
            return Ui.scroll(this, col)
        }
        val items = ArrayList<PhoneUi.Entry>()
        try {
            contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC")?.use { cur ->
                while (cur.moveToNext() && items.size < 300) {
                    val num = cur.getString(0) ?: ""
                    val cached = cur.getString(1)
                    val type = cur.getInt(2)
                    val date = cur.getLong(3)
                    val dur = cur.getLong(4)
                    val kind = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> t("आई", "Incoming")
                        CallLog.Calls.OUTGOING_TYPE -> t("की गई", "Outgoing")
                        CallLog.Calls.MISSED_TYPE -> t("छूटी", "Missed")
                        CallLog.Calls.REJECTED_TYPE -> t("काटी गई", "Declined")
                        CallLog.Calls.BLOCKED_TYPE -> t("ब्लॉक", "Blocked")
                        else -> t("कॉल", "Call")
                    }
                    val whenStr = DateUtils.getRelativeTimeSpanString(date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                    val length = if (dur > 0) " · " + DateUtils.formatElapsedTime(dur) else ""
                    val title = cached?.takeIf { it.isNotBlank() } ?: num.ifBlank { t("छुपा नंबर", "Hidden number") }
                    val sub = (if (cached.isNullOrBlank()) "" else "$num · ") + "$kind · $whenStr$length"
                    items += PhoneUi.Entry(title, sub, num, type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE)
                }
            }
        } catch (_: Throwable) { }
        if (items.isEmpty()) { col.addView(Ui.empty(this, Ui.Ic.history, t("अभी कोई कॉल नहीं", "No calls yet"), "")); return Ui.scroll(this, col) }
        val lv = PhoneUi.list(this, items) { e -> if (e.number.isNotBlank()) Dialer.place(this, e.number) }
        col.addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    // ----------------------------------------------------------------- contacts
    private fun contacts(): View {
        val col = Ui.column(this).apply { setPadding(paddingLeft, paddingTop, paddingRight, 0) }
        header(col, t("संपर्क", "Contacts"), t("नाम या नंबर खोजें", "Search by name or number"))
        if (!has(Manifest.permission.READ_CONTACTS)) {
            col.addView(Ui.empty(this, Ui.Ic.users, t("संपर्क की अनुमति चाहिए", "Contacts permission needed"), ""))
            col.addView(Ui.primary(this, t("अनुमति दें", "Allow")) { askPermissions() })
            return Ui.scroll(this, col)
        }
        val all = ArrayList<PhoneUi.Entry>()
        try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC")?.use { cur ->
                val seen = HashSet<String>()
                while (cur.moveToNext()) {
                    val n = cur.getString(0) ?: continue
                    val num = cur.getString(1) ?: continue
                    if (seen.add(n + num.filter { it.isDigit() }.takeLast(10))) all += PhoneUi.Entry(n, num, num)
                }
            }
        } catch (_: Throwable) { }
        val search = Ui.input(this, t("खोजें", "Search"), InputType.TYPE_CLASS_TEXT).apply { setText(query) }
        col.addView(search)
        val box = FrameLayout(this)
        col.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fun show() {
            val q = query.trim().lowercase()
            val digitsQ = q.filter { it.isDigit() }
            val items = if (q.isEmpty()) all else all.filter { it.title.lowercase().contains(q) || (digitsQ.isNotEmpty() && it.number.filter { c -> c.isDigit() }.contains(digitsQ)) }
            box.removeAllViews()
            box.addView(if (items.isEmpty()) Ui.empty(this, Ui.Ic.users, t("कोई संपर्क नहीं मिला", "No contacts found"), "")
                        else PhoneUi.list(this, items.take(500)) { e -> Dialer.place(this, e.number) })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; show() }
        })
        show()
        return col
    }

    // ----------------------------------------------------------------- setup
    private fun setup(): View {
        val col = Ui.column(this)
        header(col, t("सेटअप", "Setup"), t("ताकि कॉल ठीक से आएँ और उठें", "So calls ring, show and can be answered"))
        val p = getSharedPreferences("kavach", Context.MODE_PRIVATE)
        val isDef = Dialer.isDefault(this)
        val rows = mutableListOf<View>(
            if (isDef) Ui.status(this, t("कवच आपका फ़ोन ऐप है", "KAVACH is your phone app"), true, t("कॉल यहीं दिखेंगी और उठेंगी", "Calls show and are answered here"))
            else Ui.item(this, Ui.Ic.phone, t("कवच को फ़ोन ऐप बनाएँ", "Make KAVACH your phone app"),
                t("अनजान कॉल अपने-आप स्पीकर पर; Stop पर एक बटन से कॉल कटेगी", "Unknown calls go on speaker; a Stop warning gets a one-tap End call"),
                trailing = Ui.small(this, t("चुनें", "Set")) { Dialer.requestRole(this) }),
            Ui.status(this, t("कॉल, संपर्क, कॉल सूची की अनुमति", "Phone, contacts and call history"),
                has(Manifest.permission.CALL_PHONE) && has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.READ_CALL_LOG),
                t("कीपैड, हाल की कॉल और नाम दिखाने के लिए", "For the keypad, recents and caller names")).apply { setOnClickListener { askPermissions() } },
        )
        if (Build.VERSION.SDK_INT >= 33) rows += Ui.status(this, t("सूचनाएँ", "Notifications"), has(Manifest.permission.POST_NOTIFICATIONS),
            t("आ रही कॉल का उठाएँ / काटें बटन", "The Answer / Decline buttons for incoming calls")).apply { setOnClickListener { askPermissions() } }
        if (Build.VERSION.SDK_INT >= 34) rows += Ui.item(this, if (Dialer.canFullScreen(this)) Ui.Ic.check else Ui.Ic.circleAlert,
            t("पूरी स्क्रीन पर कॉल", "Full-screen incoming calls"),
            if (Dialer.canFullScreen(this)) t("चालू", "Allowed") else t("बंद है: लॉक स्क्रीन पर कॉल नहीं दिखेगी", "Off: calls will not show on the lock screen"),
            trailing = if (Dialer.canFullScreen(this)) null else Ui.small(this, t("खोलें", "Open")) { Dialer.openFullScreenPermission(this) },
            fg = if (Dialer.canFullScreen(this)) Ui.P.good else Ui.P.warn, bg = if (Dialer.canFullScreen(this)) Ui.P.goodSoft else Ui.P.warnSoft)
        if (Dialer.isXiaomi()) {
            val ok = Dialer.miuiOpsAllowed(this)
            rows += Ui.item(this, if (ok == true) Ui.Ic.check else Ui.Ic.smartphone, t("Xiaomi: पॉप-अप और लॉक स्क्रीन", "Xiaomi: pop-ups and lock screen"),
                when (ok) {
                    true -> t("चालू", "Allowed")
                    false -> t("\"बैकग्राउंड में पॉप-अप\" और \"लॉक स्क्रीन पर दिखाएँ\" चालू करें, वरना कॉल स्क्रीन नहीं खुलेगी",
                               "Turn on \"Display pop-up windows while running in the background\" and \"Show on lock screen\", or the call screen cannot open")
                    null -> t("यह फ़ोन जाँचने नहीं देता; दोनों चालू हों तो ठीक है", "This phone does not let apps check this; fine if both are on")
                },
                trailing = if (ok == true) null else Ui.small(this, t("खोलें", "Open")) { Dialer.openPopupPermission(this) },
                fg = if (ok == true) Ui.P.good else Ui.P.goldText, bg = if (ok == true) Ui.P.goodSoft else Ui.P.goldSoft)
        }
        rows += Ui.item(this, Ui.Ic.volume, t("अपने-आप स्पीकर", "Auto speaker"), t("अनजान नंबर की कॉल स्पीकर पर रखें, बोलकर बताता है", "Puts calls from unknown numbers on speaker, with a spoken notice"),
            trailing = Ui.toggle(this, Dialer.autoSpeaker(this), "Auto speaker") { p.edit().putBoolean("auto_speaker", it).apply() })
        col.addView(Ui.group(this, *rows.toTypedArray()))
        col.addView(Ui.footnote(this, t("कवच कॉल की आवाज़ रिकॉर्ड नहीं करता। कॉल सूची फ़ोन की अपनी है।",
            "KAVACH never records the call. The call history is the phone's own.")))
        return Ui.scroll(this, col)
    }
}

/** When KAVACH is NOT the phone app: a nudge to press speaker on an unknown call. */
object SpeakerNudge {
    fun show(ctx: Context) {
        val hi = ShieldHub.lang(ctx) == "hi"
        Ui.ensureChannel(ctx, "nudge", "KAVACH call tips", NotificationManager.IMPORTANCE_HIGH)
        val pi = PendingIntent.getActivity(ctx, 9, Intent(ctx, DialerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, "nudge").setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentTitle(if (hi) "अनजान नंबर: स्पीकर चालू करें" else "Unknown number: turn on the speaker")
            .setContentText(if (hi) "ताकि कवच बातचीत सुनकर आपको स्कैम से बचा सके।" else "So KAVACH can listen and warn you about scams.")
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(60_000).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(77, n)
    }
}
