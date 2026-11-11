package kavach.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kavach.shared.KavachRuntime
import kavach.shared.Speaker
import kavach.shared.AppState
import kavach.shared.Ui

/** Reads SMS / WhatsApp / Telegram notifications (no READ_SMS permission needed) and screens them. */
class ShieldListenerService : NotificationListenerService() {
    private val recent = LinkedHashMap<Int, Long>()

    override fun onListenerConnected() {
        KavachRuntime.ensureLoaded(this) { ShieldHub.attach(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        if (text.length < 8) return
        val key = (sbn.packageName + text).hashCode()
        val now = System.currentTimeMillis()
        if ((recent[key] ?: 0L) > now - 60_000) return // messaging apps re-post the same notification
        recent[key] = now
        while (recent.size > 200) recent.remove(recent.keys.first())
        if (ShieldHub.core == null) KavachRuntime.ensureLoaded(this) { ShieldHub.attach(it); ShieldHub.onMessage(this, sbn.packageName, "$title: $text") }
        else ShieldHub.onMessage(this, sbn.packageName, "$title: $text")
    }
}

private const val NETWATCH = "io.kavach.netwatch"

class MainActivity : Activity(), ShieldHub.Listener {
    private var tab = 0
    private var hero: Ui.Hero? = null
    private var whyTag: Ui.Chip? = null
    private var homeBody: LinearLayout? = null
    private var feedBox: LinearLayout? = null
    private var input: EditText? = null
    private var resultBox: LinearLayout? = null
    private var homeKey = ""
    private var feedKey = ""
    /** last manual check: tone ("urgent" | "caution" | "info"), headline, lines */
    private var result: Triple<String, String, List<String>>? = null

    private val hi get() = ShieldHub.lang(this) == "hi"
    private fun t(h: String, e: String) = if (hi) h else e
    private fun d(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", 0)
        intent.getStringExtra("lang")?.let { getSharedPreferences("kavach", Context.MODE_PRIVATE).edit().putString("lang", it).apply() }
        build()
        if (!intent.getBooleanExtra("quiet", false)) askRuntimePermissions()
        KavachRuntime.ensureLoaded(this) { ShieldHub.attach(it); onChanged() }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", tab) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("lang")?.let { getSharedPreferences("kavach", Context.MODE_PRIVATE).edit().putString("lang", it).apply() }
        if (intent.hasExtra("tab")) { tab = intent.getIntExtra("tab", Nav.HOME); build() }
    }

    private fun build() {
        Ui.theme(this, Ui.P.card)
        hero = null; whyTag = null; homeBody = null; feedBox = null; input = null; resultBox = null; homeKey = ""; feedKey = ""
        if (tab == Nav.CALLS) tab = Nav.HOME
        val (root, holder) = Ui.tabs(this, Nav.labels(hi), Nav.icons, tab) { if (it == Nav.CALLS) Nav.go(this, it) else { tab = it; build() } }
        val col = Ui.column(this)
        when (tab) { Nav.CHECK -> buildCheck(col); Nav.ACTIVITY -> buildActivity(col); Nav.SETTINGS -> buildSettings(col); else -> buildHome(col) }
        holder.addView(Ui.scroll(this, col))
        setContentView(root)
        onChanged()
    }

    private fun pageTitle(col: LinearLayout, title: String, subtitle: String) {
        col.addView(Ui.text(this, title, 26f, weight = 700).apply { setPadding(d(2), d(14), 0, 0) })
        col.addView(Ui.text(this, subtitle, 14.5f, color = Ui.MUTED).apply { setPadding(d(2), d(2), 0, d(16)) })
    }

    // ------------------------------------------------------------------ Home

    private fun buildHome(col: LinearLayout) {
        col.addView(Ui.header(this, "KAVACH Shield", t("आपके फ़ोन की सुरक्षा", "Scam protection for this phone")))
        hero = Ui.banner(this).also { h -> whyTag = Ui.pill(this); h.extras.addView(whyTag); col.addView(h) }
        homeBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(homeBody)
        col.addView(Ui.footnote(this, t("सारी जाँच इसी फ़ोन पर · इंटरनेट की अनुमति नहीं", "Everything is checked on this phone · no internet permission")))
    }

    private fun renderHome() {
        val h = hero ?: return
        val lang = ShieldHub.lang(this)
        val lv = ShieldHub.last?.level ?: "none"
        val n = notificationAccess(); val u = ShieldService.hasUsageAccess(this); val a = AppState.callAccessEnabled(this)
        val callOn = AppState.callMode in setOf("standby", "listening", "demo")
        val p = ShieldService.running && callOn
        val stopNow = ShieldHub.guardianLevel == "stop" || lv == "stop"
        when {
            stopNow -> Ui.setState(h, "stop", t("रुकिए — स्कैम जैसा पैटर्न", "Stop — scam pattern detected"), t("पैसे, ओटीपी या पिन मत दीजिए।", "Don't pay or share any OTP or PIN."))
            lv == "caution" -> Ui.setState(h, "caution", t("सावधान — संदिग्ध पैटर्न", "Caution — suspicious pattern"), t("पैसे भेजने से पहले रुकिए।", "Pause before you send money."))
            !(n && u && a) -> Ui.setState(h, "off", t("सेटअप बाकी है", "Setup needed"), t("सुरक्षा चालू करने के लिए नीचे के कदम पूरे करें।", "Finish the steps below to turn on protection."))
            !p -> Ui.setState(h, "off", t("सुरक्षा बंद है", "Protection is off"), t("नीचे 'चालू करें' दबाएँ।", "Tap Turn on below."))
            AppState.callMode == "listening" -> Ui.setState(h, "listen", t("कॉल सुन रहा है", "Listening to a call"), t("अभी तक कोई चेतावनी नहीं। यह गारंटी नहीं है।", "No warnings so far. This is not a guarantee."), Ui.Ic.ear)
            else -> Ui.setState(h, "listen", t("सुरक्षा चालू है", "Protection is on"), t("मैसेज, लिंक, ऐप और कॉल पर नज़र। अभी तक कोई चेतावनी नहीं — यह गारंटी नहीं है।", "Watching messages, links, apps and calls. No warnings so far — this is not a guarantee."), Ui.Ic.shieldCheck)
        }
        }
        val why = ShieldHub.last?.let { a -> if (a.level != "none") kavach.core.KillChain.ruleLabel(a.rule, lang) else "" } ?: ""
        whyTag?.let { w ->
            if (why.isNotEmpty() && (stopNow || lv == "caution")) Ui.setPill(w, why, if (stopNow) 0xFFFFFFFF.toInt() else Ui.P.onCaution, if (stopNow) 0x33FFFFFF else 0x1F000000, Ui.Ic.info)
            else w.visibility = View.GONE
        }

        val items = synchronized(ShieldHub.items) { ShieldHub.items.toList() }
        val key = "$lang$n$u$a$p${AppState.callMode}$stopNow$lv${ShieldHub.stat(this, "stat_msgs")}/${ShieldHub.stat(this, "stat_links")}/${ShieldHub.stat(this, "stat_alerts")}" +
            "${ShieldHub.linkState}${ShieldHub.callState}${items.firstOrNull()?.wallMs}${ShieldHub.netAlertApps.size}"
        if (key == homeKey) return
        homeKey = key
        val body = homeBody ?: return
        body.removeAllViews()

        if (stopNow || lv == "caution") body.addView(Ui.button(this, t("मैंने पढ़ लिया — चेतावनी हटाएँ", "I've read this — clear the warning"), Ui.Ic.check) { ShieldHub.clearWarning() })

        // setup: numbered steps with a progress bar; disappears when all four are done
        val done = listOf(n, u, a, p).count { it }
        if (done < 4) {
            val card = Ui.card(this, 0)
            val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(18), d(18), d(18), d(6)) }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(Ui.text(this, t("सेटअप पूरा करें", "Finish setup"), 17f, weight = 600), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(Ui.text(this, t("4 में से $done", "$done of 4"), 13.5f, color = Ui.MUTED, weight = 500))
            top.addView(line); top.addView(Ui.progress(this, done, 4))
            card.addView(top)
            card.addView(Ui.step(this, 1, n, t("मैसेज जाँच", "Message checking"), t("SMS और चैट की सूचनाओं में स्कैम पहचानता है", "Spots scams in SMS and chat notifications"), t("चालू करें", "Turn on")) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            Ui.addRow(card, Ui.step(this, 2, u, t("स्क्रीन-शेयर गार्ड", "Screen-share guard"), t("स्क्रीन शेयर के दौरान पेमेंट ऐप खुलने पर रोकता है", "Warns if a payment app opens while your screen is shared"), t("चालू करें", "Turn on")) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
            Ui.addRow(card, Ui.item(this, if (a) Ui.Ic.check else Ui.Ic.ear, t("कॉल सुनने की अनुमति", "Call listening"),
                t("स्पीकर पर रखी कॉल इसी फ़ोन पर सुनता है (Android की ऐक्सेसिबिलिटी अनुमति)", "Hears a call you put on speaker, on this phone (Android accessibility permission)"),
                trailing = if (a) null else Ui.small(this, t("चालू करें", "Turn on")) { showAccessHelp() },
                fg = if (a) Ui.GOOD else Ui.GOLD, bg = if (a) Ui.P.goodSoft else Ui.P.goldSoft))
            Ui.addRow(card, Ui.step(this, 4, p, t("सुरक्षा चालू करें", "Turn on protection"), t("मैसेज, लिंक, ऐप और कॉल — पीछे चुपचाप", "Messages, links, apps and calls — quietly in the background"), t("चालू करें", "Turn on")) { startProtection() })
            body.addView(card)
        }

        // real counters, kept on this phone
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(12) } }
        listOf(Triple(ShieldHub.stat(this, "stat_msgs"), t("मैसेज जाँचे", "Messages checked"), Ui.Ic.message),
            Triple(ShieldHub.stat(this, "stat_links"), t("लिंक जाँचे", "Links checked"), Ui.Ic.link),
            Triple(ShieldHub.stat(this, "stat_alerts"), t("चेतावनियाँ", "Warnings"), Ui.Ic.bell)).forEachIndexed { i, (v, c, ic) ->
            tiles.addView(Ui.stat(this, v.toString(), c, ic), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = d(10) })
        }
        body.addView(tiles)

        body.addView(Ui.label(this, t("अभी की स्थिति", "Right now")))
        val callWords = when (AppState.callMode) {
            "standby" -> t("तैयार — अनजान नंबर की कॉल पर सुनना शुरू", "Ready — starts listening when an unknown number calls")
            "listening" -> t("इस कॉल को सुन रहा है", "Listening to this call")
            "demo" -> t("डेमो कॉल चल रही है", "Playing a demo call")
            "models_missing" -> t("बोली मॉडल नहीं मिले", "Speech models missing")
            else -> t("बंद", "Off")
        }
        val nw = netWatchInstalled()
        body.addView(Ui.group(this,
            Ui.item(this, Ui.Ic.phoneCall, t("फ़ोन कॉल", "Phone call"), stateWords(ShieldHub.callState)),
            Ui.item(this, Ui.Ic.ear, t("कॉल सुरक्षा", "Call protection"), callWords, chevron = true) { Nav.go(this, Nav.CALLS) },
            Ui.item(this, Ui.Ic.wifi, t("नेटवर्क वॉच", "Network Watch"),
                if (nw) t("ऐप पर भेजे जा रहे डेटा और संदिग्ध वेबसाइटों पर नज़र", "Watches app uploads and suspicious websites") else t("वैकल्पिक ऐप इंस्टॉल नहीं है", "Optional app not installed"),
                chevron = nw) { if (nw) packageManager.getLaunchIntentForPackage(NETWATCH)?.let { startActivity(it) } }))

        for ((pkg, label) in ShieldHub.netAlertApps) body.addView(Ui.button(this, t("मैं \"$label\" को पहचानता हूँ — भरोसा करें", "I know \"$label\" — trust it"), Ui.Ic.check) {
            ShieldHub.trustApp(this, pkg); homeKey = ""; onChanged() })

        items.firstOrNull()?.let { latest ->
            body.addView(Ui.label(this, t("सबसे नई चेतावनी", "Latest")))
            body.addView(alertCard(latest))
            body.addView(Ui.button(this, t("सारी गतिविधि देखें", "See all activity"), Ui.Ic.history) { tab = Nav.ACTIVITY; build() })
        }
    }

    /** Hub status strings are English; show the common ones in Hindi too. */
    private fun stateWords(s: String): String {
        val known = mapOf("no call" to "कोई कॉल नहीं", "not started" to "शुरू नहीं हुआ", "waiting for guardian" to "Guardian का इंतज़ार", "guardian connected" to "Guardian जुड़ा है")
        return if (hi) known[s.lowercase()] ?: s else s.replaceFirstChar { it.uppercase() }
    }

    // ------------------------------------------------------------------ Check

    private fun buildCheck(col: LinearLayout) {
        pageTitle(col, t("जाँचें", "Check"), t("कोई लिंक या मैसेज चिपकाएँ। जाँच इसी फ़ोन पर होती है।", "Paste a link or a message. It is checked on this phone only."))
        val card = Ui.card(this)
        input = Ui.input(this, t("यहाँ लिंक या मैसेज चिपकाएँ", "Paste a link or message here"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 4; gravity = Gravity.TOP or Gravity.START
            intent.getStringExtra("text")?.let { setText(it) }
        }
        card.addView(input)
        card.addView(Ui.row(this, Ui.button(this, t("चिपकाएँ", "Paste"), Ui.Ic.paste) { paste() }, Ui.primary(this, t("जाँचें", "Check"), Ui.Ic.search) { checkInput() }))
        col.addView(card)
        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(resultBox)
        col.addView(Ui.label(this, t("ऐप", "Apps")))
        col.addView(Ui.group(this, Ui.item(this, Ui.Ic.scan, t("इंस्टॉल किए ऐप स्कैन करें", "Scan installed apps"),
            t("रिमोट-कंट्रोल और जोखिम भरे ऐप ढूँढता है", "Looks for remote-control and risky apps"), chevron = true) { scanApps() }))
        renderResult()
        if (intent.getBooleanExtra("run", false)) { intent.removeExtra("run"); col.postDelayed({ checkInput() }, 1500) }
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val s = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        if (s.isBlank()) toast(t("क्लिपबोर्ड खाली है।", "Nothing to paste.")) else input?.setText(s)
    }

    private fun renderResult() {
        val box = resultBox ?: return
        box.removeAllViews()
        val (tone, headline, lines) = result ?: return
        val card = Ui.card(this)
        card.addView(toneChip(tone).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 })
        card.addView(Ui.text(this, headline, 18f, weight = 600).apply { setPadding(0, d(12), 0, d(4)) })
        lines.forEach { card.addView(Ui.text(this, it, 15f, color = Ui.MUTED)) }
        box.addView(card)
    }

    private fun toneChip(tone: String): Ui.Chip = when (tone) {
        "urgent" -> Ui.chip(this, t("ज़रूरी", "Urgent"), Ui.BAD, Ui.P.badSoft, Ui.Ic.octagon)
        "caution" -> Ui.chip(this, t("सावधान", "Caution"), Ui.WARN, Ui.P.warnSoft, Ui.Ic.triangle)
        "ok" -> Ui.chip(this, t("कोई साफ़ चेतावनी संकेत नहीं", "No obvious warning signs"), Ui.GOOD, Ui.P.goodSoft, Ui.Ic.check)
        else -> Ui.chip(this, t("जानकारी", "Info"), Ui.P.accentText, Ui.P.accentSoft, Ui.Ic.info)
    }

    private fun checkInput() {
        val text = input?.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) { toast(t("पहले कोई लिंक या मैसेज चिपकाएँ।", "Paste a link or message first.")); return }
        val core = ShieldHub.core ?: run { toast(t("मॉडल लोड हो रहे हैं, दोबारा दबाएँ।", "Still loading — tap again in a moment.")); return }
        val urls = kavach.core.UrlChecker.extractUrls(text)
        if (urls.size == 1 && urls[0].length >= text.length - 2) ShieldHub.onLinkChecked(this, urls[0]) else ShieldHub.onMessage(this, "manual", text)
        val v = core.shield.check(text)
        val lang = ShieldHub.lang(this)
        val worst = when { v.level == "dangerous" || v.urls.any { it.level == "dangerous" } -> "urgent"
            v.level == "suspicious" || v.urls.any { it.level == "suspicious" } -> "caution"; else -> "ok" }
        val headline = when (worst) {
            "urgent" -> t("यह स्कैम जैसा लग रहा है", "This looks like a scam")
            "caution" -> t("यह संदिग्ध लग रहा है", "This looks suspicious")
            else -> t("कोई साफ़ चेतावनी संकेत नहीं मिला", "No obvious warning signs found")
        }
        val lines = ArrayList<String>()
        if (worst == "urgent") lines.add(t("लिंक न खोलें। पैसे, ओटीपी या पिन न दें।", "Don't open the link. Don't pay or share any OTP or PIN."))
        if (worst == "ok") lines.add(t("यह गारंटी नहीं है। कभी भी ओटीपी या पिन किसी को न दें।", "This is not a guarantee. Never share an OTP or PIN with anyone."))
        for (u in v.urls) {
            val lvl = when (u.level) {
                "dangerous" -> t("खतरनाक", "dangerous"); "suspicious" -> t("संदिग्ध", "suspicious")
                "safe" -> t("आधिकारिक डोमेन से मेल खाता है", "matches the organisation's official domain")
                else -> t("कोई साफ़ चेतावनी संकेत नहीं", "no obvious warning signs")
            }
            lines.add(u.host + " — " + lvl + u.reasonText(lang).joinToString("") { r -> "\n• $r" })
        }
        result = Triple(worst, headline, lines)
        renderResult()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input?.windowToken, 0)
    }

    private fun scanApps() {
        toast(t("ऐप स्कैन हो रहे हैं…", "Scanning apps…"))
        Thread {
            val res = AppInspector(this).scanAll()
            res.forEach { ShieldHub.onAppRisk(this, it) }
            runOnUiThread {
                result = if (res.isEmpty()) Triple("ok", t("कोई जोखिम भरा ऐप नहीं मिला", "No risky apps found"), listOf(t("यह गारंटी नहीं है।", "This is not a guarantee.")))
                else Triple("caution", t("${res.size} ऐप पर ध्यान दें", "${res.size} apps need attention"), res.take(8).map { it.label + " — " + it.reasons.firstOrNull().orEmpty() })
                renderResult()
            }
        }.start()
    }

    // ------------------------------------------------------------------ Activity

    private fun buildActivity(col: LinearLayout) {
        pageTitle(col, t("गतिविधि", "Activity"), t("कवच ने इस फ़ोन पर क्या देखा", "What KAVACH has noticed on this phone"))
        feedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(feedBox)
    }

    private fun renderFeed() {
        val box = feedBox ?: return
        val items = synchronized(ShieldHub.items) { ShieldHub.items.take(30) }
        val key = "${items.size}${items.firstOrNull()?.wallMs}"
        if (key == feedKey) return
        feedKey = key
        box.removeAllViews()
        if (items.isEmpty()) { box.addView(Ui.card(this).apply { addView(Ui.empty(this@MainActivity, Ui.Ic.shieldCheck, t("अभी तक कुछ नहीं", "Nothing yet"),
            t("चेतावनियाँ और जाँचें यहाँ दिखेंगी।", "Warnings and checks will show up here."))) }); return }
        items.forEach { box.addView(alertCard(it)) }
    }

    private fun alertCard(it: ShieldHub.Item): LinearLayout {
        val tone = ShieldHub.tone(it.level)
        val (fg, bg) = when (tone) { "urgent" -> Ui.BAD to Ui.P.badSoft; "caution" -> Ui.WARN to Ui.P.warnSoft; else -> Ui.P.accentText to Ui.P.accentSoft }
        val ic = when (it.channel) { "link" -> Ui.Ic.link; "app" -> Ui.Ic.scan; "screen" -> Ui.Ic.smartphone; "call" -> Ui.Ic.phoneCall
            "network" -> Ui.Ic.wifi; "llm" -> Ui.Ic.sparkles; "family" -> Ui.Ic.users; else -> Ui.Ic.message }
        val card = Ui.card(this, 16).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(Ui.iconTile(this, ic, fg, bg))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, when (tone) { "urgent" -> t("ज़रूरी", "Urgent"); "caution" -> t("सावधान", "Caution"); else -> t("जानकारी", "Info") }, 12.5f, color = fg, weight = 700),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.text(this, ago(it.wallMs), 12.5f, color = Ui.MUTED))
        words.addView(top)
        words.addView(Ui.text(this, it.title, 16f, weight = 600).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (it.detail.isNotBlank()) words.addView(Ui.text(this, it.detail, 14f, color = Ui.MUTED).apply { maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.END })
        card.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60000
        return when { m < 1 -> t("अभी", "Just now"); m < 60 -> t("$m मिनट पहले", "$m min ago"); m < 1440 -> t("${m / 60} घंटे पहले", "${m / 60} h ago")
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms)) }
    }

    // ------------------------------------------------------------------ Settings

    private fun buildSettings(col: LinearLayout) {
        pageTitle(col, t("सेटिंग", "Settings"), t("कवच को अपने हिसाब से बनाएँ", "Make KAVACH work your way"))
        col.addView(Ui.label(this, t("भाषा", "Language")).apply { setPadding(d(4), d(4), 0, d(8)) })
        col.addView(Ui.segmented(this, listOf("हिंदी", "English"), if (hi) 0 else 1) {
            getSharedPreferences("kavach", Context.MODE_PRIVATE).edit().putString("lang", if (it == 0) "hi" else "en").apply(); build() })

        col.addView(Ui.label(this, t("कॉल", "Calls")))
        col.addView(Ui.group(this, Ui.item(this, Ui.Ic.phone, t("कवच फ़ोन", "KAVACH Phone"),
            t("अनजान कॉल अपने-आप स्पीकर पर, ताकि Guardian सुन सके", "Puts unknown calls on speaker so Guardian can hear them"), chevron = true) {
            startActivity(Intent(this, DialerActivity::class.java)) }))

        col.addView(Ui.label(this, t("परिवार को सूचना", "Family alert")))
        val fam = Ui.card(this)
        fam.addView(Ui.text(this, t("Stop चेतावनी आने पर इस नंबर पर SMS जाएगा। वैकल्पिक।", "Gets a text message when KAVACH shows a Stop warning. Optional."), 14f, color = Ui.MUTED))
        val p = getSharedPreferences("kavach", Context.MODE_PRIVATE)
        val num = Ui.input(this, t("परिवार के सदस्य का नंबर", "Family member's number"), InputType.TYPE_CLASS_PHONE).apply { setText(p.getString("family_number", "")) }
        fam.addView(num)
        fam.addView(Ui.button(this, t("सेव करें", "Save"), Ui.Ic.check) {
            p.edit().putString("family_number", num.text.toString().trim()).apply()
            toast(t("सेव हो गया।", "Saved."))
            if (num.text.isNotBlank() && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 3)
        })
        col.addView(fam)

        col.addView(Ui.label(this, t("आपकी निजता", "Your privacy")))
        col.addView(Ui.group(this,
            Ui.item(this, Ui.Ic.lock, t("सारी जाँच इसी फ़ोन पर", "Everything is checked on this phone"), t("मैसेज और लिंक कहीं भेजे नहीं जाते", "Messages and links are not sent anywhere")),
            Ui.item(this, Ui.Ic.wifi, t("इंटरनेट की अनुमति नहीं", "No internet permission"), t("यह ऐप आपका डेटा बाहर भेज ही नहीं सकता", "This app cannot send your data out")),
            Ui.item(this, Ui.Ic.micOff, t("कॉल रिकॉर्ड नहीं होती", "Calls are never recorded"), t("आवाज़ कभी सेव नहीं की जाती", "Audio is never saved"))))
        col.addView(Ui.footnote(this, "KAVACH Shield " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" }), Ui.Ic.shield))
    }

    // ------------------------------------------------------------------ plumbing

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun askRuntimePermissions() {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        want.add(Manifest.permission.RECORD_AUDIO)
        // call awareness: know that a call with an unknown number is going on (never its audio)
        want.add(Manifest.permission.READ_PHONE_STATE)
        want.add(Manifest.permission.READ_CALL_LOG)
        want.add(Manifest.permission.READ_CONTACTS)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
    }

    private fun netWatchInstalled(): Boolean = try { packageManager.getPackageInfo(NETWATCH, 0); true } catch (_: Throwable) { false }

    private fun showAccessHelp() {
        android.app.AlertDialog.Builder(this, Ui.dialogTheme())
            .setTitle(t("कॉल सुनने की अनुमति", "Allow call listening"))
            .setMessage(t("कॉल के दौरान Android माइक सिर्फ़ ऐक्सेसिबिलिटी सेवाओं को देता है।\n\nसेटिंग में: Downloaded apps / Installed services → KAVACH call listening → चालू करें।\n\nकवच इससे सिर्फ़ स्पीकर पर रखी कॉल सुनता है। स्क्रीन नहीं पढ़ता, कुछ दबाता नहीं, आवाज़ सेव नहीं करता।",
                "During a call, Android shares the microphone only with accessibility services.\n\nIn Settings: Downloaded apps (or Installed services) → KAVACH call listening → turn it on.\n\nKAVACH uses it only to hear a call on speaker. It does not read your screen, tap anything, or save audio."))
            .setPositiveButton(t("सेटिंग खोलें", "Open Settings")) { _, _ -> AppState.openCallAccessSettings(this) }
            .setNegativeButton(t("बाद में", "Later"), null).show()
    }

    private fun startProtection() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4); return
        }
        AppState.startCallProtection(this, getSharedPreferences("guardian", Context.MODE_PRIVATE).getString("call_lang", null) ?: ShieldHub.lang(this))
        startForegroundService(Intent(this, ShieldService::class.java))
        getSharedPreferences("kavach", Context.MODE_PRIVATE).edit().putBoolean("protection_started", true).apply()
        hero?.postDelayed({ onChanged() }, 400)
    }

    private fun notificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(this, ShieldListenerService::class.java).flattenToString())
    }

    override fun onResume() { super.onResume(); ShieldHub.addListener(this); homeKey = ""; onChanged() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 4 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startProtection()
        homeKey = ""; onChanged()
    }
    override fun onPause() { ShieldHub.removeListener(this); super.onPause() }

    override fun onChanged() { renderHome(); renderFeed() }
}

/** Share a link or message to KAVACH from any app ("Share → KAVACH Link Check"). */
class LinkCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        Ui.theme(this)
        val lang0 = ShieldHub.lang(this)
        val col = Ui.column(this, 18)
        col.addView(Ui.header(this, "KAVACH Link Check", if (lang0 == "hi") "इसी फ़ोन पर जाँचा गया" else "Checked on this phone only"))
        val banner = Ui.banner(this, compact = true)
        Ui.setState(banner, "ready", if (lang0 == "hi") "जाँच हो रही है…" else "Checking…", "")
        col.addView(banner)
        val card = Ui.card(this)
        val body = Ui.text(this, "", 15f, color = Ui.MUTED)
        card.addView(body); card.visibility = View.GONE
        col.addView(card)
        col.addView(Ui.primary(this, if (lang0 == "hi") "ठीक है" else "Done") { finish() })
        setContentView(Ui.scroll(this, col))
        KavachRuntime.ensureLoaded(this) { core ->
            ShieldHub.attach(core)
            val lang = ShieldHub.lang(this)
            val urls = kavach.core.UrlChecker.extractUrls(shared)
            val verdicts = urls.map { core.urlChecker.check(it) }
            val msg = core.shield.check(shared)
            val worst = when { msg.level == "dangerous" || verdicts.any { it.level == "dangerous" } -> "stop"
                msg.level == "suspicious" || verdicts.any { it.level == "suspicious" } -> "caution"; else -> "none" }
            when (worst) {
                "stop" -> Ui.setState(banner, "stop", if (lang == "hi") "यह स्कैम जैसा लग रहा है" else "This looks like a scam", if (lang == "hi") "लिंक न खोलें। ओटीपी या पिन न दें।" else "Don't open it. Don't share any OTP or PIN.")
                "caution" -> Ui.setState(banner, "caution", if (lang == "hi") "यह संदिग्ध लग रहा है" else "This looks suspicious", if (lang == "hi") "आगे बढ़ने से पहले रुकिए।" else "Pause before you go further.")
                else -> Ui.setState(banner, "ready", if (lang == "hi") "कोई साफ़ चेतावनी संकेत नहीं" else "No obvious warning signs",
                    if (lang == "hi") "यह गारंटी नहीं है। कोई ओटीपी या पिन न दें।" else "This is not a guarantee. Never share an OTP or PIN.", Ui.Ic.search)
            }
            body.text = (verdicts.joinToString("\n\n") { v -> "${v.host}\n" + v.reasonText(lang).joinToString("\n") { "• $it" } } +
                "\n\n" + (if (lang == "hi") "संदेश जोखिम: " else "Message risk: ") + "${"%.0f".format(msg.pScam * 100)}%").trim()
            card.visibility = View.VISIBLE
            verdicts.forEach { ShieldHub.onLinkChecked(this, it.url) }
            if (verdicts.isEmpty() && shared.isNotBlank()) ShieldHub.onMessage(this, "manual", shared)
        }
    }
}

/** Full-screen red stop card. Big text, one-tap call to 1930, spoken aloud. */
class StopActivity : Activity() {
    private var speaker: Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lang = ShieldHub.lang(this)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val red = 0xFF991B1B.toInt()
        Ui.theme(this)
        window.decorView.setBackgroundColor(red)
        @Suppress("DEPRECATION") run { window.statusBarColor = red; window.navigationBarColor = red
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() }
        val col = Ui.column(this, 26).apply { setBackgroundColor(red); gravity = Gravity.CENTER_VERTICAL }
        col.addView(Ui.Orb(this).apply { set(Ui.Ic.octagon, Color.WHITE, Color.WHITE, red, false, true) }, LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)))
        col.addView(Ui.text(this, if (lang == "hi") "रुकिए!" else "Stop!", 46f, color = Color.WHITE, weight = 700).apply { setPadding(0, Ui.dp(this@StopActivity, 14), 0, 0) })
        col.addView(Ui.text(this, if (lang == "hi") "यह स्कैम जैसा पैटर्न है" else "This looks like a scam", 21f, color = Color.WHITE, weight = 600))
        if (text.isNotBlank()) col.addView(Ui.text(this, text, 19f, color = Color.WHITE).apply {
            background = Ui.shape(this@StopActivity, 0x33000000, 20f); val p = Ui.dp(this@StopActivity, 18); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@StopActivity, 18) } })
        intent.getStringExtra(EXTRA_RULE)?.takeIf { it.isNotBlank() }?.let { col.addView(Ui.chip(this, it, Color.WHITE, 0x33FFFFFF, Ui.Ic.info)) }
        col.addView(Ui.space(this, 26))
        col.addView(Ui.solid(this, if (lang == "hi") "1930 पर कॉल करें (साइबर हेल्पलाइन)" else "Call 1930 (cyber helpline)", Color.WHITE, red, Ui.Ic.phone) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")))
        })
        col.addView(Ui.solid(this, if (lang == "hi") "मैं समझ गया, भुगतान नहीं करूंगा" else "I understand, I won't pay", 0xFF7F1D1D.toInt(), Color.WHITE) { finish() })
        setContentView(Ui.scroll(this, col).apply { setBackgroundColor(red) })
        speaker = Speaker(this)
        // during a phone call the warning is not spoken (it would go into the call); the phone vibrates instead
        if (!AppState.inPhoneCall(this)) col.postDelayed({ speaker?.speak(text, lang) }, 600)
    }

    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_RULE = "rule"
    }
}
