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
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kavach.shared.KavachRuntime
import kavach.shared.Speaker
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

class MainActivity : Activity(), ShieldHub.Listener {
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var feed: TextView
    private lateinit var input: EditText
    private lateinit var setupCard: LinearLayout
    private var setupKey = ""

    /** Setup steps: a button until done, then a green tick. Rebuilt only when something changed. */
    private fun refreshSetup() {
        val hi = ShieldHub.lang(this) == "hi"
        val n = notificationAccess(); val u = ShieldService.hasUsageAccess(this)
        val p = getSharedPreferences("kavach", Context.MODE_PRIVATE).getBoolean("protection_started", false)
        val key = "$n$u$p"
        if (key == setupKey) return
        setupKey = key
        setupCard.removeAllViews()
        setupCard.addView(Ui.step(this, n, if (hi) "मैसेज जाँच चालू" else "Message checking on",
            if (hi) "1 · मैसेज जाँच चालू करें" else "1 · Turn on message checking") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        setupCard.addView(Ui.step(this, u, if (hi) "स्क्रीन-शेयर और ऐप निगरानी चालू" else "Screen-share & app watch on",
            if (hi) "2 · स्क्रीन-शेयर गार्ड चालू करें" else "2 · Turn on screen-share guard") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        setupCard.addView(Ui.step(this, p, if (hi) "सुरक्षा चालू है" else "Protection is on",
            if (hi) "3 · सुरक्षा शुरू करें" else "3 · Start protection") { startProtection() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.theme(this)
        val hi = ShieldHub.lang(this) == "hi"
        val col = Ui.column(this)
        col.addView(Ui.header(this, "KAVACH Shield", if (hi) "आपके फ़ोन की सुरक्षा · कुछ भी बाहर नहीं जाता" else "Protecting this phone · nothing leaves it"))
        banner = Ui.banner(this); col.addView(banner)
        status = Ui.text(this, "", 14f, color = Color.DKGRAY); col.addView(status)

        // ---- setup (each step turns into a green tick when done)
        col.addView(Ui.label(this, if (hi) "सेटअप" else "Setup"))
        setupCard = Ui.card(this); col.addView(setupCard)

        // ---- check a link or message
        col.addView(Ui.label(this, if (hi) "लिंक या मैसेज जाँचें" else "Check a link or message"))
        val check = Ui.card(this)
        input = Ui.input(this, if (hi) "यहाँ लिंक या मैसेज चिपकाएँ" else "Paste a link or message here", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        check.addView(input)
        check.addView(Ui.row(this, Ui.primary(this, if (hi) "जाँचें" else "Check") { checkInput() }, Ui.button(this, if (hi) "ऐप स्कैन करें" else "Scan apps") { scanApps() }))
        col.addView(check)

        trustRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; col.addView(trustRow)

        // ---- recent alerts
        col.addView(Ui.label(this, if (hi) "हाल की चेतावनियाँ" else "Recent alerts"))
        val fcard = Ui.card(this)
        feed = Ui.text(this, "", 15f); fcard.addView(feed)
        col.addView(fcard)

        // ---- more
        col.addView(Ui.label(this, if (hi) "और विकल्प" else "More"))
        val more = Ui.card(this)
        more.addView(Ui.button(this, if (hi) "📞  कवच फ़ोन: अनजान कॉल अपने-आप स्पीकर पर" else "📞  KAVACH Phone: unknown calls on speaker") {
            startActivity(Intent(this, DialerActivity::class.java)) })
        more.addView(familyRow())
        more.addView(Ui.button(this, if (hi) "Language: English" else "भाषा: हिंदी") { toggleLang() })
        col.addView(more)
        setContentView(Ui.scroll(this, col))
        askRuntimePermissions()
        KavachRuntime.ensureLoaded(this) { ShieldHub.attach(it); onChanged() }
    }

    private lateinit var trustRow: LinearLayout

    /** "I trust this app": for a known app (e.g. a backup app you installed yourself) flagged by Network Watch */
    private fun refreshTrust() {
        trustRow.removeAllViews()
        val hi = ShieldHub.lang(this) == "hi"
        for ((pkg, label) in ShieldHub.netAlertApps) trustRow.addView(Ui.button(this, if (hi) "मैं \"$label\" को पहचानता हूँ (भरोसा करें)" else "I know \"$label\" - trust it") {
            ShieldHub.trustApp(this, pkg); refreshTrust()
        })
    }

    private fun familyRow(): LinearLayout {
        val p = getSharedPreferences("kavach", Context.MODE_PRIVATE)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val num = Ui.input(this, if (ShieldHub.lang(this) == "hi") "परिवार का नंबर (वैकल्पिक)" else "Family member's number (optional)", InputType.TYPE_CLASS_PHONE)
            .apply { setText(p.getString("family_number", "")) }
        row.addView(num, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.button(this, if (ShieldHub.lang(this) == "hi") "सेव" else "Save") {
            p.edit().putString("family_number", num.text.toString().trim()).apply()
            if (num.text.isNotBlank() && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 3)
        }.apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 96), LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dp(this@MainActivity, 10) } })
        return row
    }

    private fun askRuntimePermissions() {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_CONNECT)
        // call awareness: know that a call with an unknown number is going on (never its audio)
        want.add(Manifest.permission.READ_PHONE_STATE)
        want.add(Manifest.permission.READ_CALL_LOG)
        want.add(Manifest.permission.READ_CONTACTS)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
    }

    private fun startProtection() {
        startForegroundService(Intent(this, ShieldService::class.java))
        getSharedPreferences("kavach", Context.MODE_PRIVATE).edit().putBoolean("protection_started", true).apply()
        refreshSetup()
    }

    private fun toggleLang() {
        val p = getSharedPreferences("kavach", Context.MODE_PRIVATE)
        p.edit().putString("lang", if (ShieldHub.lang(this) == "hi") "en" else "hi").apply()
        recreate()
    }

    private fun checkInput() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val core = ShieldHub.core ?: return
        val urls = kavach.core.UrlChecker.extractUrls(t)
        if (urls.size == 1 && urls[0].length >= t.length - 2) ShieldHub.onLinkChecked(this, urls[0]) else ShieldHub.onMessage(this, "manual", t)
        val v = core.shield.check(t)
        status.text = "message: ${v.level} (p=${"%.2f".format(v.pScam)})" + v.urls.joinToString("") { "\nlink ${it.host}: ${it.level} ${it.reasonText(ShieldHub.lang(this))}" }
    }

    private fun scanApps() {
        Thread {
            val res = AppInspector(this).scanAll()
            res.forEach { ShieldHub.onAppRisk(this, it) }
            runOnUiThread { status.text = if (res.isEmpty()) "No risky apps found." else "${res.size} apps need attention (see below)." }
        }.start()
    }

    private fun notificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(this, ShieldListenerService::class.java).flattenToString())
    }

    override fun onResume() { super.onResume(); ShieldHub.addListener(this); onChanged() }
    override fun onPause() { ShieldHub.removeListener(this); super.onPause() }

    override fun onChanged() {
        val lang = ShieldHub.lang(this)
        if (trustRow.childCount != ShieldHub.netAlertApps.size) refreshTrust()
        val lv = ShieldHub.last?.level ?: "none"
        Ui.setBanner(banner, if (ShieldHub.guardianLevel == "stop") "stop" else lv, lang)
        refreshSetup()
        val why = ShieldHub.last?.let { a -> if (a.level != "none") (if (lang == "hi") "कारण: " else "Why: ") + kavach.core.KillChain.ruleLabel(a.rule, lang) else "" } ?: ""
        status.text = listOf(why, (if (lang == "hi") "गार्डियन: " else "Guardian: ") + ShieldHub.linkState + " · " + ShieldHub.callState)
            .filter { it.isNotBlank() }.joinToString("\n")
        val items = synchronized(ShieldHub.items) { ShieldHub.items.take(15) }
        val dot = mapOf("stop" to "🔴", "caution" to "🟠", "info" to "🔵")
        feed.text = if (items.isEmpty()) (if (lang == "hi") "अभी तक कोई चेतावनी नहीं। 👍" else "No alerts yet. 👍")
            else items.joinToString("\n\n") { "${dot[it.level] ?: "🟢"}  ${it.title}\n${it.detail}" }
    }
}

/** Share a link or message to KAVACH from any app ("Share → KAVACH Link Check"). */
class LinkCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        Ui.theme(this)
        val col = Ui.column(this)
        col.addView(Ui.header(this, "KAVACH Link Check", "Checked on this phone only"))
        val banner = Ui.banner(this)
        col.addView(banner)
        val body = Ui.text(this, "…", 20f)
        col.addView(body)
        col.addView(Ui.primary(this, "OK") { finish() })
        setContentView(Ui.scroll(this, col))
        KavachRuntime.ensureLoaded(this) { core ->
            ShieldHub.attach(core)
            val lang = ShieldHub.lang(this)
            val urls = kavach.core.UrlChecker.extractUrls(shared)
            val verdicts = urls.map { core.urlChecker.check(it) }
            val msg = core.shield.check(shared)
            val worst = when { msg.level == "dangerous" || verdicts.any { it.level == "dangerous" } -> "stop"
                msg.level == "suspicious" || verdicts.any { it.level == "suspicious" } -> "caution"; else -> "none" }
            Ui.setBanner(banner, worst, lang)
            body.text = (verdicts.joinToString("\n\n") { v -> "${v.host}\n" + v.reasonText(lang).joinToString("\n") { "• $it" } } +
                "\n\n" + (if (lang == "hi") "संदेश जोखिम: " else "Message risk: ") + "${"%.0f".format(msg.pScam * 100)}%").trim()
            verdicts.forEach { ShieldHub.onLinkChecked(this, it.url) }
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
        window.decorView.setBackgroundColor(0xFF7F1D1D.toInt())
        @Suppress("DEPRECATION") run { window.statusBarColor = 0xFF7F1D1D.toInt(); window.navigationBarColor = 0xFF991B1B.toInt() }
        val col = Ui.column(this, 28).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF7F1D1D.toInt(), 0xFFDC2626.toInt()))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        col.addView(Ui.text(this, "⛔", 64f, color = Color.WHITE))
        col.addView(Ui.text(this, if (lang == "hi") "रुकिए!" else "STOP!", 52f, bold = true, color = Color.WHITE))
        col.addView(Ui.text(this, text, 23f, color = Color.WHITE))
        intent.getStringExtra(EXTRA_RULE)?.takeIf { it.isNotBlank() }?.let {
            col.addView(Ui.text(this, (if (lang == "hi") "कारण: " else "Why: ") + it, 16f, bold = true, color = 0xFFFECACA.toInt()))
        }
        col.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(1, Ui.dp(this, 24)))
        col.addView(Ui.solid(this, if (lang == "hi") "📞  1930 पर कॉल करें (साइबर हेल्पलाइन)" else "📞  Call 1930 (cyber helpline)", Color.WHITE, 0xFF991B1B.toInt()) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")))
        })
        col.addView(Ui.solid(this, if (lang == "hi") "मैं समझ गया, भुगतान नहीं करूंगा" else "I understand, I will not pay", 0x33FFFFFF, Color.WHITE) { finish() })
        setContentView(Ui.scroll(this, col).apply { setBackgroundColor(0xFF991B1B.toInt()) })
        speaker = Speaker(this)
        col.postDelayed({ speaker?.speak(text, lang) }, 600)
    }

    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_RULE = "rule"
    }
}
