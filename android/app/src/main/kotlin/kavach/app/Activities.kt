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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this)
        banner = Ui.banner(this); col.addView(banner)
        status = Ui.text(this, "", 14f, color = Color.DKGRAY); col.addView(status)

        col.addView(Ui.button(this, "1. Allow message checking (notification access)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(Ui.button(this, "2. Allow screen-share guard (usage access)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        col.addView(Ui.button(this, "3. Start protection") { startProtection() })

        input = EditText(this).apply { hint = "Paste a link or message to check"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; textSize = 18f }
        col.addView(input)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, "Check") { checkInput() })
        row.addView(Ui.button(this, "Scan apps") { scanApps() })
        row.addView(Ui.button(this, if (ShieldHub.lang(this) == "hi") "English" else "हिंदी") { toggleLang() })
        col.addView(row)
        col.addView(familyRow())
        feed = Ui.text(this, "", 16f); col.addView(feed)
        setContentView(Ui.scroll(this, col))
        askRuntimePermissions()
        KavachRuntime.ensureLoaded(this) { ShieldHub.attach(it); onChanged() }
    }

    private fun familyRow(): LinearLayout {
        val p = getSharedPreferences("kavach", Context.MODE_PRIVATE)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val num = EditText(this).apply { hint = "Family member's number (optional)"; inputType = InputType.TYPE_CLASS_PHONE; setText(p.getString("family_number", "")) }
        row.addView(num, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.button(this, "Save") {
            p.edit().putString("family_number", num.text.toString().trim()).apply()
            if (num.text.isNotBlank() && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 3)
        })
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

    private fun startProtection() { startForegroundService(Intent(this, ShieldService::class.java)) }

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
        val lv = ShieldHub.last?.level ?: "none"
        Ui.setBanner(banner, if (ShieldHub.guardianLevel == "stop") "stop" else lv, lang)
        status.text = "messages: ${if (notificationAccess()) "on" else "OFF"} · screen guard: ${if (ShieldService.hasUsageAccess(this)) "on" else "OFF"}" +
            " · Guardian link: ${ShieldHub.linkState} · ${ShieldHub.callState}" +
            (ShieldHub.last?.let { a -> if (a.level != "none") "\n" + (if (lang == "hi") "कारण: " else "Why: ") + kavach.core.KillChain.ruleLabel(a.rule, lang) + "  (channels: ${a.channels.joinToString()})" else "" } ?: "")
        val items = synchronized(ShieldHub.items) { ShieldHub.items.take(15) }
        feed.text = items.joinToString("\n\n") { "[${it.channel}] ${it.level.uppercase()} — ${it.title}\n${it.detail}" }
    }
}

/** Share a link or message to KAVACH from any app ("Share → KAVACH Link Check"). */
class LinkCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        val col = Ui.column(this)
        val banner = Ui.banner(this)
        col.addView(banner)
        val body = Ui.text(this, "…", 20f)
        col.addView(body)
        col.addView(Ui.button(this, "OK") { finish() })
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
        val col = Ui.column(this, 24).apply { setBackgroundColor(Ui.LEVEL_COLORS["stop"]!!) }
        col.addView(Ui.text(this, if (lang == "hi") "रुकिए!" else "STOP!", 48f, bold = true, color = Color.WHITE))
        col.addView(Ui.text(this, text, 24f, color = Color.WHITE))
        col.addView(Ui.button(this, if (lang == "hi") "📞 1930 पर कॉल करें (साइबर हेल्पलाइन)" else "📞 Call 1930 (cyber helpline)") {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")))
        })
        col.addView(Ui.button(this, if (lang == "hi") "मैं समझ गया, भुगतान नहीं करूंगा" else "I understand, I will not pay") { finish() })
        intent.getStringExtra(EXTRA_RULE)?.takeIf { it.isNotBlank() }?.let { col.addView(Ui.text(this, "($it)", 12f, color = Color.WHITE)) }
        setContentView(col)
        speaker = Speaker(this)
        col.postDelayed({ speaker?.speak(text, lang) }, 600)
    }

    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_RULE = "rule"
    }
}
