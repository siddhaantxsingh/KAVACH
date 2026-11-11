package kavach.netwatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kavach.shared.Ui
import java.util.Locale

/**
 * KAVACH Network Watch (optional app). Three tabs:
 *  - Overview: the DNS watch on/off, what it has checked, warnings in plain language with what to do
 *  - Apps: which apps looked up which websites, and which were flagged
 *  - Wi-Fi: a user-started check of the home network for devices with risky open services
 * Everything is judged on this phone. Findings are also shown in the KAVACH app's Activity list.
 */
class NetWatchActivity : Activity() {
    private var tab = 0
    private var body: LinearLayout? = null
    private var hero: Ui.Hero? = null
    private var actions: LinearLayout? = null
    private var tiles: LinearLayout? = null
    private var lastKey = ""
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1500) } }

    private val hi get() = getSharedPreferences("nw", Context.MODE_PRIVATE).getString("lang", null)?.let { it == "hi" } ?: (Locale.getDefault().language == "hi")
    private fun t(hiText: String, en: String) = if (hi) hiText else en
    private fun d(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", 0)
        intent.getStringExtra("lang")?.let { getSharedPreferences("nw", Context.MODE_PRIVATE).edit().putString("lang", it).apply() }
        build()
        if (tab == 2 && intent.getBooleanExtra("scan", false)) scanWifi()
        if (android.os.Build.VERSION.SDK_INT >= 33 && !intent.getBooleanExtra("quiet", false))
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", tab) }

    private fun build() {
        Ui.theme(this, Ui.P.card)
        hero = null; actions = null; tiles = null; body = null; lastKey = ""
        val (root, holder) = Ui.tabs(this, listOf(t("सारांश", "Overview"), t("ऐप", "Apps"), "Wi-Fi"),
            listOf(Ui.Ic.shield, Ui.Ic.smartphone, Ui.Ic.wifi), tab) { tab = it; build() }
        val col = Ui.column(this)
        col.addView(Ui.header(this, "Network Watch", t("कौन-सा ऐप किस वेबसाइट से जुड़ता है", "Which app talks to which website"), false,
            Ui.iconButton(this, Ui.Ic.languages, t("भाषा बदलें", "Change language")) {
                getSharedPreferences("nw", Context.MODE_PRIVATE).edit().putString("lang", if (hi) "en" else "hi").apply(); build() }))
        when (tab) { 1 -> buildApps(col); 2 -> buildWifi(col); else -> buildOverview(col) }
        holder.addView(Ui.scroll(this, col))
        setContentView(root)
        refresh()
    }

    // ------------------------------------------------------------------ Overview

    private fun buildOverview(col: LinearLayout) {
        hero = Ui.banner(this).also { col.addView(it) }
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actions)
        tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8); bottomMargin = d(4) } }
        col.addView(tiles)
        col.addView(Ui.label(this, t("चेतावनियाँ", "Warnings")))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        col.addView(Ui.label(this, t("यह कैसे काम करता है", "How it works")))
        col.addView(Ui.group(this,
            Ui.item(this, Ui.Ic.search, t("सिर्फ़ वेबसाइट के नाम", "Only website names"),
                t("हर ऐप किस नाम (जैसे sbi-kyc-update.xyz) को खोजता है, वही जाँचता है। आप क्या भेजते हैं, वह नहीं।", "Checks the name each app looks up (like sbi-kyc-update.xyz), never what you send.")),
            Ui.item(this, Ui.Ic.cpu, t("इसी फ़ोन पर फ़ैसला", "Judged on this phone"),
                t("नकली बैंक साइट और मैलवेयर जैसे बेतुके नाम पहचानने वाला मॉडल फ़ोन पर चलता है।", "A model that spots fake bank sites and malware-style random names runs on the phone.")),
            Ui.item(this, Ui.Ic.lock, t("चाबी का निशान क्यों?", "Why the key icon?"),
                t("Android का VPN फ़ीचर इसी फ़ोन पर इस्तेमाल होता है। आपका डेटा किसी KAVACH सर्वर पर नहीं जाता।", "It uses Android's VPN feature locally on this phone. Your traffic is not sent to any KAVACH server."))))
        col.addView(Ui.footnote(this, t("सिर्फ़ DNS देखता है · बाकी ट्रैफ़िक सामान्य रूप से जाता है", "Sees DNS lookups only · all other traffic flows normally")))
    }

    private fun startWatch() {
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 7) else onActivityResult(7, RESULT_OK, null)
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 7 && resultCode == RESULT_OK) { startService(Intent(this, DnsVpnService::class.java)); h.postDelayed({ lastKey = ""; refresh() }, 600) }
    }

    private fun stopWatch() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
        h.postDelayed({ lastKey = ""; refresh() }, 600)
    }

    // ------------------------------------------------------------------ Apps

    private fun buildApps(col: LinearLayout) {
        col.addView(Ui.text(this, t("Network Watch चालू होने के बाद हर ऐप ने कितनी वेबसाइटें खोजीं।", "Website lookups per app since Network Watch was turned on."), 14.5f, color = Ui.MUTED)
            .apply { setPadding(d(2), 0, 0, d(10)) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
    }

    // ------------------------------------------------------------------ Wi-Fi

    private var scanResult: LanScan.Result? = null
    private var scanning = false

    private fun buildWifi(col: LinearLayout) {
        col.addView(Ui.text(this, t("घर या दुकान के Wi-Fi पर ऐसे डिवाइस ढूँढता है जिनके खतरनाक दरवाज़े खुले हैं — जैसे TV बॉक्स का रिमोट-डिबगिंग, कैमरे का Telnet, खुले शेयर फ़ोल्डर।",
            "Looks for devices on your home or shop Wi-Fi with risky open doors — a TV box with remote debugging on, a camera with Telnet, open shared folders."), 14.5f, color = Ui.MUTED)
            .apply { setPadding(d(2), 0, 0, d(8)) })
        col.addView(Ui.primary(this, if (scanning) t("जाँच हो रही है…", "Checking…") else t("मेरा Wi-Fi जाँचें", "Check my Wi-Fi"), Ui.Ic.wifi) { scanWifi() })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        col.addView(Ui.footnote(this, t("सिर्फ़ आपके अपने नेटवर्क पर, आपके कहने पर · कोई डेटा नहीं पढ़ा जाता", "Only your own network, only when you ask · no data is read")))
        renderScan()
    }

    private fun scanWifi() {
        if (scanning) return
        scanning = true; scanResult = null
        build()
        Thread {
            val r = LanScan.scan(this) { done, total -> runOnUiThread { if (tab == 2) progressText("${done * 100 / total}%") } }
            for (dev in r.devices.filter { it.level != "info" }) {
                val worst = dev.open.first()
                DnsVpnService.report(this, mapOf("type" to "finding", "level" to dev.level,
                    "title" to "Wi-Fi device ${dev.ip}: ${worst.name} is open", "detail" to worst.whyEn + " " + worst.fixEn))
            }
            runOnUiThread { scanning = false; scanResult = r; if (tab == 2) build() }
        }.start()
    }

    private fun progressText(p: String) {
        val b = body ?: return
        b.removeAllViews()
        b.addView(Ui.card(this).apply { addView(Ui.text(this@NetWatchActivity, t("नेटवर्क के 254 पते जाँच रहा है… ", "Checking the 254 addresses of this network… ") + p, 15f, color = Ui.MUTED)) })
    }

    private fun renderScan() {
        val b = body ?: return
        val r = scanResult ?: return
        b.removeAllViews()
        if (r.error != null) {
            b.addView(Ui.card(this).apply { addView(Ui.empty(this@NetWatchActivity, Ui.Ic.wifi, t("Wi-Fi से जुड़ें", "Connect to Wi-Fi"),
                t("यह जाँच सिर्फ़ Wi-Fi या LAN पर चलती है, मोबाइल डेटा पर नहीं।", "This check runs on Wi-Fi or a wired network, not on mobile data."))) })
            return
        }
        val risky = r.devices.count { it.level != "info" }
        val summary = Ui.banner(this, compact = true)
        when {
            risky > 0 -> Ui.setState(summary, if (r.devices.any { it.level == "urgent" }) "stop" else "caution",
                t("$risky डिवाइस पर ध्यान दें", "$risky device(s) need attention"), "${r.network} · ${"%.0f".format(r.seconds)} s")
            else -> Ui.setState(summary, "ready", t("कोई खतरनाक खुला दरवाज़ा नहीं मिला", "No risky open doors found"),
                t("${r.devices.size} डिवाइस में सिर्फ़ सामान्य सेवाएँ · यह गारंटी नहीं है", "${r.devices.size} device(s) with ordinary services only · not a guarantee"), Ui.Ic.check)
        }
        b.addView(summary)
        for (dev in r.devices) {
            val card = Ui.card(this, 16)
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val (fg, bg) = tone(dev.level)
            head.addView(Ui.iconTile(this, if (dev.isRouter) Ui.Ic.radio else if (dev.isThisPhone) Ui.Ic.smartphone else Ui.Ic.cpu, fg, bg))
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
            words.addView(Ui.text(this, dev.ip + when { dev.isRouter -> t(" · राउटर", " · router"); dev.isThisPhone -> t(" · यह फ़ोन", " · this phone"); else -> "" }, 16f, weight = 600))
            words.addView(Ui.text(this, levelWord(dev.level), 13f, color = fg, weight = 700))
            head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(head)
            for (s in dev.open) {
                card.addView(Ui.divider(this))
                card.addView(Ui.text(this, "${s.name} · port ${s.port}", 15f, weight = 600))
                card.addView(Ui.text(this, if (hi) s.whyHi else s.whyEn, 14f, color = Ui.MUTED))
                card.addView(Ui.text(this, "→ " + (if (hi) s.fixHi else s.fixEn), 14f, color = Ui.TEXT))
            }
            b.addView(card)
        }
    }

    // ------------------------------------------------------------------ live refresh

    private fun refresh() {
        val on = DnsVpnService.state == "on"
        val findings = synchronized(DnsVpnService.recent) { DnsVpnService.recent.toList() }
        val apps = synchronized(DnsVpnService.apps) { DnsVpnService.apps.values.map { Triple(it.pkg, it.lookups, it.flagged) to it.lastHost } }
        val key = "$tab$on${DnsVpnService.lookups}${findings.size}${apps.size}${hi}"
        if (key == lastKey) return
        lastKey = key
        when (tab) {
            0 -> {
                hero?.let { hr ->
                    when {
                        !on -> Ui.setState(hr, "off", t("Network Watch बंद है", "Network Watch is off"), t("चालू करें ताकि नकली बैंक साइट और मैलवेयर वाले ऐप पकड़े जा सकें।", "Turn it on to catch fake bank sites and apps that behave like malware."))
                        findings.isNotEmpty() -> Ui.setState(hr, "caution", t("${findings.size} चेतावनी", "${findings.size} warning(s)"), t("नीचे देखें कि कौन-सा ऐप और क्या करें।", "See below which app, and what to do."))
                        else -> Ui.setState(hr, "listen", t("नज़र रख रहा है", "Watching"), t("अभी तक कोई खतरनाक वेबसाइट नहीं। यह गारंटी नहीं है।", "No dangerous websites so far. This is not a guarantee."), Ui.Ic.shieldCheck)
                    }
                }
                actions?.let { a ->
                    a.removeAllViews()
                    a.addView(if (on) Ui.danger(this, t("बंद करें", "Turn off"), Ui.Ic.x) { stopWatch() } else Ui.primary(this, t("चालू करें", "Turn on"), Ui.Ic.shield) { startWatch() })
                }
                tiles?.let { tl ->
                    tl.removeAllViews()
                    listOf(Triple(DnsVpnService.lookups.toString(), t("नाम जाँचे", "Lookups checked"), Ui.Ic.search),
                        Triple(apps.size.toString(), t("ऐप देखे", "Apps seen"), Ui.Ic.smartphone),
                        Triple(findings.size.toString(), t("चेतावनियाँ", "Warnings"), Ui.Ic.bell)).forEachIndexed { i, (v, c, ic) ->
                        tl.addView(Ui.stat(this, v, c, ic), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = d(10) })
                    }
                }
                body?.let { b ->
                    b.removeAllViews()
                    if (findings.isEmpty()) b.addView(Ui.card(this).apply { addView(Ui.empty(this@NetWatchActivity, Ui.Ic.shieldCheck, t("कोई चेतावनी नहीं", "No warnings"),
                        if (on) t("कोई ऐप नकली बैंक साइट या संदिग्ध नाम से नहीं जुड़ा।", "No app has contacted a fake bank site or a suspicious name.") else t("चालू करने के बाद चेतावनियाँ यहाँ दिखेंगी।", "Warnings appear here once it is on."))) })
                    for (f in findings) b.addView(findingCard(f))
                }
            }
            1 -> body?.let { b ->
                b.removeAllViews()
                if (apps.isEmpty()) { b.addView(Ui.card(this).apply { addView(Ui.empty(this@NetWatchActivity, Ui.Ic.smartphone, t("अभी कुछ नहीं", "Nothing yet"),
                    if (on) t("ऐप इस्तेमाल करें; खोजे गए नाम यहाँ दिखेंगे।", "Use your apps; their lookups will show here.") else t("पहले सारांश में Network Watch चालू करें।", "First turn on Network Watch in Overview."))) }); return }
                val g = Ui.group(this)
                for ((st, last) in apps.sortedWith(compareBy({ -it.first.third }, { -it.first.second })).take(40)) {
                    val (pkg, n, flagged) = st
                    val label = appLabel(pkg)
                    val (fg, bg) = if (flagged > 0) tone("caution") else Ui.P.goldText to Ui.P.goldSoft
                    Ui.addRow(g, Ui.item(this, if (flagged > 0) Ui.Ic.triangle else Ui.Ic.smartphone, label,
                        t("$n नाम खोजे", "$n lookups") + (if (flagged > 0) t(" · $flagged संदिग्ध", " · $flagged suspicious") else "") + " · " + last, fg = fg, bg = bg))
                }
                b.addView(g)
            }
            else -> Unit
        }
    }

    private fun findingCard(f: DnsVpnService.Companion.Finding): LinearLayout {
        val (fg, bg) = tone("urgent")
        val card = Ui.card(this, 16).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(Ui.iconTile(this, Ui.Ic.octagon, fg, bg))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(Ui.text(this, t("ज़रूरी", "Urgent"), 12.5f, color = fg, weight = 700), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.text(this, ago(f.at), 12.5f, color = Ui.MUTED))
        words.addView(top)
        words.addView(Ui.text(this, t("\"${f.app}\" एक खतरनाक वेबसाइट से जुड़ा", "\"${f.app}\" contacted a dangerous website"), 16f, weight = 600))
        words.addView(Ui.text(this, f.host, 14f, color = Ui.TEXT).apply { typeface = android.graphics.Typeface.MONOSPACE })
        words.addView(Ui.text(this, f.detail, 14f, color = Ui.MUTED))
        words.addView(Ui.text(this, t("क्या करें: इस ऐप में लॉग-इन या पेमेंट न करें। अगर आप इसे नहीं पहचानते, तो हटा दें (इसका इस फ़ोन पर रखा डेटा भी हट जाएगा)।",
            "What to do: don't log in or pay inside this app. If you don't recognise it, remove it (its data on this phone is deleted with it)."), 14f, color = Ui.TEXT))
        card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }

    private fun tone(level: String): Pair<Int, Int> = when (level) {
        "urgent" -> Ui.BAD to Ui.P.badSoft
        "caution" -> Ui.WARN to Ui.P.warnSoft
        else -> Ui.P.accentText to Ui.P.accentSoft
    }

    private fun levelWord(level: String) = when (level) { "urgent" -> t("ज़रूरी", "Urgent"); "caution" -> t("सावधान", "Caution"); else -> t("जानकारी", "Info") }

    private fun appLabel(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }

    private fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60000
        return when { m < 1 -> t("अभी", "Just now"); m < 60 -> t("$m मिनट पहले", "$m min ago"); else -> t("${m / 60} घंटे पहले", "${m / 60} h ago") }
    }

    override fun onResume() { super.onResume(); lastKey = ""; h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }
}
