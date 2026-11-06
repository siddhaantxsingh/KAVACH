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
    private val links: List<KavachLink> get() = listOfNotNull(btLink, localLink)
    private var advisor: LlamaAdvisor? = null
    private var lastStopAt = 0L

    fun now(): Double = SystemClock.elapsedRealtime() / 1000.0

    fun attach(c: KavachCore) {
        if (core != null) return
        core = c
        killChain = KillChain(c.lexicon.stageOf)
    }

    fun addListener(l: Listener) { main.post { listeners.add(l) } }
    fun removeListener(l: Listener) { main.post { listeners.remove(l) } }
    private fun changed() { main.post { listeners.toList().forEach { it.onChanged() } } }

    private fun record(it: Item) { synchronized(items) { items.add(0, it); while (items.size > 50) items.removeAt(items.size - 1) }; changed() }

    fun lang(ctx: Context): String = ctx.getSharedPreferences("kavach", Context.MODE_PRIVATE).getString("lang", "hi") ?: "hi"

    // ------------------------------------------------------------------ inputs
    fun onMessage(ctx: Context, source: String, text: String) = exec.execute {
        val c = core ?: return@execute
        val v = c.shield.check(text)
        if (v.level == "safe") return@execute
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
    fun onCallStarted(ctx: Context, number: String?, unknown: Boolean, outgoing: Boolean) = exec.execute {
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
        callState = "no call"
        links.forEach { it.send(linkedMapOf("type" to "call_ended")) }
        feed(ctx, KcEvent(now(), "phone", KillChain.CALL_END, 0.0, ""), forward = true)
    }

    fun onPaymentAppOpened(ctx: Context, pkg: String) = exec.execute {
        feed(ctx, KcEvent(now(), "app", KillChain.PAYMENT_OPEN, 0.0, pkg), forward = true)
    }

    /** Messages from the Guardian about the live call. */
    private fun onGuardianMessage(ctx: Context, m: Map<String, Any?>) = exec.execute {
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
                // Same phone: the Guardian screen already shows STOP and has spoken; don't cover it.
                if (m["same_device"] == true) familyAlert(ctx, say) else showStop(ctx, force = true, reason = say)
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
        val text = reason?.takeIf { it.isNotBlank() }
            ?: LlmContract.fallbackVerdict("stop", a?.active ?: emptyMap(), retrieved, labels, a?.stages ?: emptyList(), lang).say
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
    fun startAdvisor(ctx: Context) {
        if (advisor != null) return
        val dir = File(ctx.getExternalFilesDir(null), "models/llama")
        advisor = LlamaAdvisor(File(dir, "kavach_llama3_2_1b.pte").path, File(dir, "tokenizer.model").path).also { a ->
            a.loadAsync { st -> Log.i(TAG, "Llama $st"); core?.let { c -> if (st == LlamaAdvisor.State.READY) a.warm(LlmContract.formatLlama3(LlmContract.systemPrompt(c.playbook.typeIds), "").first) } }
        }
    }

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
        // same-phone link (Guardian installed on this phone) - always on, costs nothing
        if (localLink == null) {
            localLink = LocalLink(app, LocalLink.GUARDIAN_PACKAGE, onMessage = { m -> onGuardianMessage(app, m) },
                onState = { s -> linkState = s; changed() }).also { it.start() }
        }
        // Bluetooth link for a Guardian on another phone
        if (btLink != null) return
        if (android.os.Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        btLink = DeviceLink(app, onMessage = { m -> onGuardianMessage(app, m) }, onState = { s -> linkState = s; changed() }).also { it.startServer() }
    }

    fun stopLink() { btLink?.stop(); btLink = null; localLink?.stop(); localLink = null; calls?.stop(); calls = null }
}
