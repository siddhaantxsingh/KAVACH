package kavach.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import android.util.Log
import kavach.core.Assessment
import kavach.core.KavachCore
import kavach.core.KcEvent
import kavach.core.KillChain
import kavach.core.LlmContract
import kavach.core.MessageVerdict
import kavach.shared.DeviceLink
import kavach.shared.KavachLink
import kavach.shared.LlamaAdvisor
import kavach.shared.LocalLink
import kavach.shared.Ui
import java.io.File
import java.util.concurrent.Executors

/**
 * The user's phone: one kill-chain across every channel this phone sees (messages, links,
 * apps, screen sharing) plus whatever the Guardian reports about the live call.
 * All state changes happen on one thread ("kavach-hub").
 */
object ShieldHub {
    private const val TAG = "ShieldHub"
    const val ALERT_CHANNEL = "alerts"

    data class Item(val wallMs: Long, val channel: String, val level: String, val title: String, val detail: String)

    interface Listener { fun onChanged() }

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "kavach-hub") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    val items = ArrayList<Item>()
    @Volatile var core: KavachCore? = null
    @Volatile var killChain: KillChain? = null
    @Volatile var last: Assessment? = null
    @Volatile var linkState = "not started"
    @Volatile var guardianLevel = "none"
    @Volatile var callState = "no call"
    /** kinds forwarded to the Guardian so the call it is listening to is judged with them */
    private val FORWARD = KillChain.CROSS_KINDS + setOf(KillChain.CALL_START, KillChain.CALL_END, KillChain.PAYMENT_OPEN)
    private var btLink: DeviceLink? = null
    private var localLink: LocalLink? = null
    private var inProc: kavach.shared.InProcessLink? = null
    private val links: List<KavachLink> get() = listOfNotNull(inProc, btLink)
    private val advisor: LlamaAdvisor? get() = kavach.shared.AppState.llama
    private var lastStopAt = 0L

    fun now(): Double = SystemClock.elapsedRealtime() / 1000.0

    fun attach(c: KavachCore) {
        if (core != null) return
        core = c
        killChain = KillChain(c.lexicon.stageOf)
    }

    /** The user has read the warning and dismissed it: start watching afresh (the activity list is kept). */
    fun clearWarning() = exec.execute {
        core?.let { killChain = KillChain(it.lexicon.stageOf) }
        last = null; guardianLevel = "none"
        changed()
    }

    fun addListener(l: Listener) { main.post { listeners.add(l) } }
    fun removeListener(l: Listener) { main.post { listeners.remove(l) } }
    private fun changed() { main.post { listeners.toList().forEach { it.onChanged() } } }

    private fun record(it: Item) {
        synchronized(items) { items.add(0, it); while (items.size > 50) items.removeAt(items.size - 1) }
        if (tone(it.level) != "info") appCtx?.let { c -> bump(c, "stat_alerts") }
        changed()
    }

    /** "urgent" | "caution" | "info" for any of the level names used by the detectors. */
    fun tone(level: String): String = when (level) { "stop", "dangerous", "urgent" -> "urgent"; "caution", "suspicious" -> "caution"; else -> "info" }

    // real counters shown on the home screen (kept on this phone only)
    @Volatile private var appCtx: Context? = null
    fun stat(ctx: Context, key: String): Int = ctx.getSharedPreferences("kavach", Context.MODE_PRIVATE).getInt(key, 0)
    private fun bump(ctx: Context, key: String) {
        val p = ctx.getSharedPreferences("kavach", Context.MODE_PRIVATE)
        synchronized(this) { p.edit().putInt(key, p.getInt(key, 0) + 1).apply() }
    }

    fun lang(ctx: Context): String { if (appCtx == null) appCtx = ctx.applicationContext; return langOf(ctx) }
    private fun langOf(ctx: Context): String = ctx.getSharedPreferences("kavach", Context.MODE_PRIVATE).getString("lang", "hi") ?: "hi"

    // ------------------------------------------------------------------ inputs
    fun onMessage(ctx: Context, source: String, text: String) = exec.execute {
        val c = core ?: return@execute
        val v = c.shield.check(text)
        bump(ctx, "stat_msgs")
        if (v.level == "safe") { changed(); return@execute }
        val lang = lang(ctx)
        val reasons = v.urls.flatMap { it.reasonText(lang) }.distinct()
        val title = if (v.level == "dangerous") (if (lang == "hi") "खतरनाक मैसेज" else "Dangerous message") else (if (lang == "hi") "संदिग्ध मैसेज" else "Suspicious message")
        val detail = buildString {
            append(text.take(140))
            if (reasons.isNotEmpty()) append("\n• ").append(reasons.joinToString("\n• "))
            if (v.topTokens.isNotEmpty()) append("\n[").append(v.topTokens.joinToString(", ") { it.first }).append("]")
        }
        record(Item(System.currentTimeMillis(), source, v.level, title, detail))
        notifyUser(ctx, title, detail, v.level)
        for (ev in v.events(now())) feed(ctx, ev, forward = true)
        if (v.level == "dangerous") explainWithLlama(ctx, v)
    }

    fun onLinkChecked(ctx: Context, url: String) = exec.execute {
        val c = core ?: return@execute
        val v = c.urlChecker.check(url)
        val lang = lang(ctx)
        bump(ctx, "stat_links")
        record(Item(System.currentTimeMillis(), "link", if (v.level == "dangerous") "dangerous" else v.level, url, v.reasonText(lang).joinToString("\n")))
        if (v.level == "dangerous") feed(ctx, KcEvent(now(), "link", "dangerous_link", 2.5, v.host), forward = true)
    }

    fun onAppRisk(ctx: Context, r: AppInspector.Result) = exec.execute {
        if (r.level == "safe") return@execute
        val title = (if (lang(ctx) == "hi") "जोखिम भरा ऐप: " else "Risky app: ") + r.label
        record(Item(System.currentTimeMillis(), "app", r.level, title, r.reasons.joinToString("\n")))
        notifyUser(ctx, title, r.reasons.joinToString("; "), r.level)
        if (r.level == "dangerous") feed(ctx, KcEvent(now(), "app", "risky_apk", 2.5, r.packageName), forward = true)
    }

    fun onScreenShareRisk(ctx: Context, remotePkg: String, paymentPkg: String) = exec.execute {
        val ev = KcEvent(now(), "screen", "payment_app_while_sharing", 3.0, "$remotePkg -> $paymentPkg")
        record(Item(System.currentTimeMillis(), "screen", "dangerous", "Payment app opened while screen is shared", "$remotePkg is active; $paymentPkg opened"))
        feed(ctx, ev, forward = true)
        showStop(ctx, force = true, reason = if (lang(ctx) == "hi")
            "कोई आपकी स्क्रीन देख रहा है और आपने पेमेंट ऐप खोला है। वह आपका पिन और ओटीपी देख सकता है। स्क्रीन शेयर बंद कीजिए और ऐप हटाइए।"
        else "Someone can see your screen and you opened a payment app. They can read your PIN and OTP. Stop screen sharing and uninstall the app.")
    }

    fun onRemoteAccessActive(ctx: Context, pkg: String) = exec.execute { feed(ctx, KcEvent(now(), "screen", "remote_access_active", 2.0, pkg), forward = true) }

    // ------------------------------------------------------------------ call awareness (one-phone mode)
    @Volatile private var callActive = false

    /** from CallMonitor (phone-state broadcast) or, when KAVACH is the phone app, from KavachInCallService; once per call */
    fun onCallStarted(ctx: Context, number: String?, unknown: Boolean, outgoing: Boolean) = exec.execute {
        if (callActive) return@execute
        callActive = true
        // nudge to press speaker only when a Guardian is actually linked (otherwise nobody would listen)
        if (unknown && !Dialer.isDefault(ctx) && linkState.contains("connected")) main.post { SpeakerNudge.show(ctx) }
        callState = (if (unknown) "call with an unknown number" else "call with a contact") + if (outgoing) " (outgoing)" else ""
        links.forEach { it.send(linkedMapOf("type" to "call_started", "unknown" to unknown, "outgoing" to outgoing)) }
        if (unknown) {
            record(Item(System.currentTimeMillis(), "call", "info", if (lang(ctx) == "hi") "अनजान नंबर से कॉल" else "Call from an unknown number",
                if (lang(ctx) == "hi") "KAVACH इस कॉल के दौरान लिंक, ऐप, स्क्रीन शेयर और पेमेंट पर नज़र रखेगा।"
                else "KAVACH watches links, apps, screen sharing and payments during this call."))
            feed(ctx, KcEvent(now(), "phone", KillChain.CALL_START, 0.0, if (outgoing) "outgoing" else "incoming"), forward = true)
        } else changed()
    }

    fun onCallEnded(ctx: Context) = exec.execute {
        if (!callActive) return@execute
        callActive = false
        callState = "no call"
        links.forEach { it.send(linkedMapOf("type" to "call_ended")) }
        feed(ctx, KcEvent(now(), "phone", KillChain.CALL_END, 0.0, ""), forward = true)
    }

    // ------------------------------------------------------------------ Network Watch (app traffic)
    private val netWatch = kavach.core.NetWatch()
    /** apps that raised a Network Watch alert: package -> label (for the "I trust this app" buttons) */
    val netAlertApps = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun trustApp(ctx: Context, pkg: String) {
        val p = ctx.getSharedPreferences("kavach", Context.MODE_PRIVATE)
        p.edit().putStringSet("net_trusted", (p.getStringSet("net_trusted", emptySet()) ?: emptySet()) + pkg).apply()
        netAlertApps.remove(pkg); changed()
    }

    fun onAppTraffic(ctx: Context, pkg: String, tx: Double, rx: Double, foreground: Boolean, suspect: Boolean) = exec.execute {
        for (ev in netWatch.observe(now(), pkg, tx, rx, foreground, suspect)) {
            val hi = lang(ctx) == "hi"
            val label = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
            val (title, body) = when (ev.kind) {
                "suspicious_upload" -> (if (hi) "\"$label\" छुपकर बहुत डेटा भेज रहा है" else "\"$label\" is secretly sending a lot of data") to
                    (if (hi) "यह ऐप ऐप-स्टोर से नहीं है या नया है। अगर आपने इसे किसी के कहने पर डाला है, तो इसे अभी हटाएँ।"
                     else "This app is not from an app store or is new. If someone asked you to install it, uninstall it now.")
                else -> (if (hi) "\"$label\" बार-बार छुपकर इंटरनेट से जुड़ रहा है" else "\"$label\" keeps contacting the internet in the background") to
                    (if (hi) "यह रिमोट-कंट्रोल ऐप जैसा व्यवहार है। अगर आप इसे नहीं पहचानते, तो हटा दें।"
                     else "This looks like a remote-control app. If you don't recognise it, uninstall it.")
            }
            netAlertApps[pkg] = label
            record(Item(System.currentTimeMillis(), "network", "caution", title, ev.detail))
            notifyUser(ctx, title, body, "caution")
            feed(ctx, ev.copy(detail = label + ": " + ev.detail), forward = true)
            explainNetworkWithLlama(ctx, ev.kind, label + ": " + ev.detail)
        }
    }

    private fun explainNetworkWithLlama(ctx: Context, kind: String, detail: String) {
        val a = advisor ?: return
        val c = core ?: return
        if (a.state != LlamaAdvisor.State.READY) return
        val retrieved = c.playbook.entries.filter { it.id == "remote_support" || it.id == "loan_app" }
        val inCall = callState.startsWith("call with an unknown")
        val user = LlmContract.networkUserPrompt(listOf(kind to detail), inCall, retrieved, lang(ctx))
        val (prefix, suffix) = LlmContract.formatLlama3(LlmContract.systemPrompt(c.playbook.typeIds), user)
        Executors.newSingleThreadExecutor().execute {
            val verdict = LlmContract.validate(a.generate(prefix, suffix), detail, c.playbook.typeIds).first ?: return@execute
            if (verdict.say.isNotBlank()) record(Item(System.currentTimeMillis(), "llm", "info", "Llama 3.2 1B explains", verdict.say))
        }
    }

    fun onPaymentAppOpened(ctx: Context, pkg: String) = exec.execute {
        feed(ctx, KcEvent(now(), "app", KillChain.PAYMENT_OPEN, 0.0, pkg), forward = true)
    }

    /** Messages from the Guardian about the live call. */
    private fun onGuardianMessage(ctx: Context, m: Map<String, Any?>) = exec.execute {
        // Network Watch's Wi-Fi check: a device on the home network with a risky open service
        if (m["type"] == "finding") {
            val title = m["title"] as? String ?: return@execute
            val detail = m["detail"] as? String ?: ""
            record(Item(System.currentTimeMillis(), "network", m["level"] as? String ?: "caution", title, detail))
            explainNetworkWithLlama(ctx, "risky_device", "$title. $detail")
            return@execute
        }
        DeviceLink.eventFrom(m, now())?.let { feed(ctx, it, forward = false) }
        if (m["type"] == "level") {
            val lv = m["level"] as? String ?: return@execute
            guardianLevel = lv
            val say = m["say"] as? String ?: ""
            record(Item(System.currentTimeMillis(), "call", lv, "Guardian: $lv", say))
            // the risky call itself becomes cross-channel evidence on this phone: a dangerous
            // link or APK during / after a suspicious call escalates to STOP (cross-channel-multi)
            if (lv == "caution" || lv == "stop") feed(ctx, KcEvent(now(), "call", "scam_call", 2.0, "guardian:$lv"), forward = false)
            if (lv == "stop") {
                // Same app: if the call screen is open it already shows STOP; otherwise (e.g. the user is
                // on the in-call screen) the full-screen stop card is raised.
                if (m["same_device"] == true && m["show_stop"] != true) familyAlert(ctx, say) else showStop(ctx, force = true, reason = say)
            }
        }
    }

    // ------------------------------------------------------------------ fusion
    private fun feed(ctx: Context, ev: KcEvent, forward: Boolean) {
        val kc = killChain ?: return
        kc.add(ev)
        if (forward && ev.kind in FORWARD) links.forEach { it.sendEvent(ev) }
        val a = kc.assess(now())
        val prev = last?.level
        last = a
        changed()
        if (a.level == "stop" && prev != "stop") showStop(ctx, force = false, reason = null)
        else if (a.level == "caution" && prev != "caution" && prev != "stop")
            notifyUser(ctx, KillChain.ruleLabel(a.rule, lang(ctx)), if (lang(ctx) == "hi") "सावधान रहें। किसी अनजान कॉल पर पैसे या ओटीपी न दें।"
                else "Be careful. Never pay or share an OTP during a call from an unknown number.", "caution")
    }

    fun showStop(ctx: Context, force: Boolean, reason: String?) {
        val t = SystemClock.elapsedRealtime()
        if (!force && t - lastStopAt < 60_000) return
        lastStopAt = t
        val c = core
        val a = last
        val lang = lang(ctx)
        val retrieved = if (c != null && a != null) c.playbook.rank(a.active).map { it.first } else emptyList()
        val labels = c?.lexicon?.categories?.mapValues { it.value.labelEn to it.value.labelHi } ?: emptyMap()
        // Shield usually has no speech cues of its own: without them, say plainly what was seen
        // (a message / link, or risky activity during a call) instead of naming a scam type it cannot know.
        val inCall = callActive || guardianLevel == "stop"
        val generic = when {
            inCall && lang == "hi" -> "रुकिए! इस कॉल के दौरान स्कैम के संकेत मिले हैं। पैसे मत भेजिए, ओटीपी या पिन मत बताइए, कोई ऐप इंस्टॉल मत कीजिए। कॉल काटिए और 1930 पर कॉल कीजिए।"
            inCall -> "Stop! There are scam signs during this call. Do not send money, do not share any OTP or PIN, and do not install any app. Hang up and call 1930."
            lang == "hi" -> "रुकिए! यह मैसेज या लिंक स्कैम जैसा लग रहा है। लिंक मत खोलिए, ओटीपी या पिन मत बताइए और पैसे मत भेजिए। अगर पैसे जा चुके हैं तो तुरंत 1930 पर कॉल कीजिए।"
            else -> "Stop! This message or link looks like a scam. Do not open the link, do not share any OTP or PIN, and do not send money. If money has already gone, call 1930 right away."
        }
        val text = reason?.takeIf { it.isNotBlank() }
            ?: if (!inCall || a == null || a.active.isEmpty()) generic
            else LlmContract.fallbackVerdict("stop", a.active, retrieved, labels, a.stages, lang).say.ifBlank { generic }
        val i = Intent(ctx, StopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(StopActivity.EXTRA_TEXT, text).putExtra(StopActivity.EXTRA_RULE, a?.rule?.let { KillChain.ruleLabel(it, lang) } ?: "")
        // full-screen intent works from the background on Android 10+ (USE_FULL_SCREEN_INTENT)
        Ui.ensureChannel(ctx, ALERT_CHANNEL, "KAVACH alerts", NotificationManager.IMPORTANCE_HIGH)
        val pi = PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, ALERT_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (lang == "hi") "रुकिए — स्कैम" else "STOP — scam").setContentText(text.take(120))
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setFullScreenIntent(pi, true).setAutoCancel(true).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(99, n)
        try { ctx.startActivity(i) } catch (t: Throwable) { Log.w(TAG, "background start blocked; notification shown", t) }
        familyAlert(ctx, text)
    }

    /** Optional: SMS a trusted family member on STOP (off unless a number is configured and SEND_SMS granted). */
    fun familyAlert(ctx: Context, text: String) {
        val p = ctx.getSharedPreferences("kavach", Context.MODE_PRIVATE)
        val number = p.getString("family_number", null)?.takeIf { it.isNotBlank() } ?: return
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        try {
            @Suppress("DEPRECATION")
            val sms = ctx.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            val msg = "KAVACH: your family member may be talking to a scammer right now. Please call them. (" + text.take(80) + ")"
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(msg), null, null)
            record(Item(System.currentTimeMillis(), "family", "info", "Family alerted", number))
        } catch (t: Throwable) { Log.w(TAG, "family SMS failed", t) }
    }

    private fun notifyUser(ctx: Context, title: String, body: String, level: String) {
        Ui.ensureChannel(ctx, ALERT_CHANNEL, "KAVACH alerts", NotificationManager.IMPORTANCE_HIGH)
        val pi = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, ALERT_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("KAVACH: $title").setContentText(body.take(100)).setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify((System.nanoTime() % 10000).toInt() + 100, n)
    }

    // ------------------------------------------------------------------ Llama explanations for messages
    /** Llama is loaded once, by call protection (kavach.shared.AppState.llama), and shared here:
     *  two copies would not fit in a phone's memory. Nothing to start. */
    @Suppress("UNUSED_PARAMETER") fun startAdvisor(ctx: Context) {}

    private fun explainWithLlama(ctx: Context, v: MessageVerdict) {
        val a = advisor ?: return
        val c = core ?: return
        if (a.state != LlamaAdvisor.State.READY) return
        val active = v.cues.mapValues { it.value.toDouble() * 1.0 }
        val retrieved = c.playbook.rank(active, v.text).map { it.first }
        val lang = lang(ctx)
        val user = LlmContract.messageUserPrompt(v.text, active, v.urls.flatMap { it.reasons }, retrieved, lang)
        val (prefix, suffix) = LlmContract.formatLlama3(LlmContract.systemPrompt(c.playbook.typeIds), user)
        Executors.newSingleThreadExecutor().execute {
            val verdict = LlmContract.validate(a.generate(prefix, suffix), v.text, c.playbook.typeIds).first ?: return@execute
            if (verdict.say.isNotBlank()) record(Item(System.currentTimeMillis(), "llm", "info", "Llama 3.2 1B explains", verdict.say))
        }
    }

    // ------------------------------------------------------------------ link to the Guardian
    private var calls: CallMonitor? = null

    fun startCallAwareness(ctx: Context) {
        if (calls == null) calls = CallMonitor(ctx.applicationContext).also { it.start() }
    }

    fun startLink(ctx: Context) {
        val app = ctx.applicationContext
        // the call listener is part of this same app: in-memory link
        if (inProc == null) {
            inProc = kavach.shared.InProcessLink(kavach.shared.InProcessBus.SHIELD, onMessage = { m -> onGuardianMessage(app, m) },
                onState = { s -> linkState = s; changed() })
        }
        // KAVACH Network Watch (optional separate app) reports network events by broadcast
        if (localLink == null) {
            localLink = LocalLink(app, LocalLink.NETWATCH_PACKAGE, onMessage = { m -> onGuardianMessage(app, m) },
                onState = { }).also { it.start() }
        }
        // Bluetooth link for a Guardian on another phone
        if (btLink != null) return
        if (android.os.Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        btLink = DeviceLink(app, onMessage = { m -> onGuardianMessage(app, m) }, onState = { s -> linkState = s; changed() }).also { it.startServer() }
    }

    fun stopLink() { btLink?.stop(); btLink = null; localLink?.stop(); localLink = null; inProc?.stop(); inProc = null; calls?.stop(); calls = null }
}
