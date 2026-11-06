package kavach.guardian

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kavach.core.Alert
import kavach.core.GuardState
import kavach.core.Verdict
import kavach.shared.LlamaAdvisor
import kavach.shared.PerfStats
import kavach.shared.Ui

class GuardianActivity : Activity(), GuardianBus.Listener {
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var explain: TextView
    private lateinit var why: TextView
    private lateinit var placement: TextView
    private lateinit var perf: TextView
    private var lang = "hi"
    private var linkAddr: String? = null
    private var lastLlm: LlamaAdvisor.Stats? = null
    private val handler = Handler(Looper.getMainLooper())
    private val perfTick = object : Runnable {
        override fun run() {
            if (perf.visibility == View.VISIBLE) perf.text = PerfStats.snapshot(this@GuardianActivity).overlayText(lastLlm, null)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.keepScreenOn(this)
        lang = getPreferences(Context.MODE_PRIVATE).getString("lang", "hi") ?: "hi"
        linkAddr = getPreferences(Context.MODE_PRIVATE).getString("link", null)
        val col = Ui.column(this)
        banner = Ui.banner(this).also { Ui.setBanner(it, "none", lang); it.setOnClickListener { togglePerf() } }
        col.addView(banner)
        status = Ui.text(this, GuardianBus.lastStatus, 14f, color = Color.DKGRAY)
        col.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(Ui.button(this, "▶ हिंदी / Hinglish") { startGuard("hi", null) })
        row1.addView(Ui.button(this, "▶ English") { startGuard("en", null) })
        row1.addView(Ui.button(this, "■ Stop") { stopGuard() })
        col.addView(row1)
        val row0 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row0.addView(Ui.button(this, "⏸ Standby: start by itself on calls") { startStandby() })
        val prefs = getSharedPreferences("guardian", Context.MODE_PRIVATE)
        row0.addView(Ui.button(this, if (prefs.getBoolean(GuardianService.PREF_AUTOSTART, true)) "Auto-start: ON" else "Auto-start: OFF") {
            val on = !prefs.getBoolean(GuardianService.PREF_AUTOSTART, true)
            prefs.edit().putBoolean(GuardianService.PREF_AUTOSTART, on).apply()
            recreate()
        })
        col.addView(row0)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(Ui.button(this, "Demo replay") { pickDemo() })
        row2.addView(Ui.button(this, "Link phone") { pickPhone() })
        row2.addView(Ui.button(this, "Perf") { togglePerf() })
        col.addView(row2)

        why = Ui.text(this, "", 16f, color = Color.DKGRAY)
        col.addView(why)
        placement = Ui.text(this, "", 16f, bold = true)
        col.addView(placement)
        explain = Ui.text(this, "", 20f, bold = true)
        col.addView(explain)
        col.addView(Ui.text(this, if (lang == "hi") "बातचीत (सिर्फ इसी फोन पर):" else "Live transcript (stays on this phone):", 14f, color = Color.GRAY))
        transcript = Ui.text(this, "", 17f)
        col.addView(transcript)
        perf = Ui.text(this, "", 12f, color = 0xFF37474F.toInt()).apply { visibility = View.GONE; typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(perf)
        setContentView(Ui.scroll(this, col))
        requestPermissionsIfNeeded()
    }

    override fun onResume() { super.onResume(); GuardianBus.add(this); handler.post(perfTick) }
    override fun onPause() { GuardianBus.remove(this); handler.removeCallbacks(perfTick); super.onPause() }

    private fun requestPermissionsIfNeeded() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun startGuard(language: String, demo: String?) {
        if (demo == null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded(); return
        }
        lang = language
        getPreferences(Context.MODE_PRIVATE).edit().putString("lang", lang).apply()
        Ui.setBanner(banner, "none", lang)
        explain.text = ""
        val i = Intent(this, GuardianService::class.java).setAction(GuardianService.ACTION_START)
            .putExtra(GuardianService.EXTRA_LANG, lang).putExtra(GuardianService.EXTRA_DEMO, demo).putExtra(GuardianService.EXTRA_LINK_ADDR, linkAddr)
        startForegroundService(i)
    }

    /** Standby: the service starts now (while this screen is visible, as Android requires for the
     *  microphone) and begins listening by itself when the protected phone reports an unknown call. */
    private fun startStandby() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissionsIfNeeded(); return }
        if (linkAddr == null) { status.text = "Link the protected phone first (Link phone)."; return }
        startForegroundService(Intent(this, GuardianService::class.java).setAction(GuardianService.ACTION_STANDBY)
            .putExtra(GuardianService.EXTRA_LANG, lang).putExtra(GuardianService.EXTRA_LINK_ADDR, linkAddr))
        status.text = "Standby: waiting for a call on the protected phone."
    }

    private fun stopGuard() { startService(Intent(this, GuardianService::class.java).setAction(GuardianService.ACTION_STOP)) }

    private fun pickDemo() {
        val names = assets.list("demo")?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") } ?: emptyList()
        AlertDialog.Builder(this).setTitle("Replay a scripted call").setItems(names.toTypedArray()) { _, w ->
            startGuard(if (names[w].endsWith("_en")) "en" else "hi", names[w])
        }.show()
    }

    @Suppress("MissingPermission")
    private fun pickPhone() {
        val names = arrayListOf("This phone (KAVACH Shield is installed here)")
        val addrs = arrayListOf(kavach.shared.LocalLink.SAME_PHONE)
        val btAllowed = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (btAllowed) {
            val bonded = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices?.toList() ?: emptyList()
            for (d in bonded) { names.add("Bluetooth: " + (d.name ?: d.address)); addrs.add(d.address) }
        }
        AlertDialog.Builder(this).setTitle("Where is KAVACH Shield running?").setItems(names.toTypedArray()) { _, w ->
            linkAddr = addrs[w]
            getPreferences(Context.MODE_PRIVATE).edit().putString("link", linkAddr).apply()
            status.text = "Will link to: ${names[w]} when guarding starts."
        }.show()
    }

    private fun togglePerf() { perf.visibility = if (perf.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

    // ---- bus events
    override fun onTranscript(finals: List<String>, partial: String) {
        transcript.text = (finals.takeLast(12) + if (partial.isNotEmpty()) listOf("… $partial") else emptyList()).joinToString("\n")
    }

    override fun onState(s: GuardState) {
        Ui.setBanner(banner, s.level, lang)
        why.text = if (s.rule.isNotBlank() && s.level != "none") (if (lang == "hi") "कारण: " else "Why: ") + kavach.core.KillChain.ruleLabel(s.rule, lang) else ""
        if (perf.visibility == View.VISIBLE) status.text = "p(scam)=${"%.2f".format(s.pScam)} score=${s.score} stages=${s.stages} rule=${s.rule}"
    }

    override fun onAlert(a: Alert) {
        Ui.setBanner(banner, a.level, lang)
        explain.text = a.verdict.say
    }

    override fun onLlm(v: Verdict, stats: LlamaAdvisor.Stats?) {
        lastLlm = stats
        val ev = if (v.evidence.isNotEmpty()) "\n“" + v.evidence.joinToString("”, “") + "”" else ""
        explain.text = (if (v.say.isNotBlank()) v.say else explain.text.toString()) + ev +
            "\n[Llama 3.2 1B: ${v.type}, ${v.stage}, ${"%.0f".format(v.confidence * 100)}%]"
    }

    override fun onStatus(msg: String) { status.text = msg }

    /** live placement check: is this phone close enough to the speaker-phone? */
    override fun onPlacement(snrDb: Double) {
        val hi = lang == "hi"
        val (txt, col) = when (kavach.core.AudioCheck.verdict(snrDb)) {
            "good" -> (if (hi) "आवाज़ साफ़ है ✓" else "Placement: good ✓") to Color.rgb(46, 125, 50)
            "move_closer" -> (if (hi) "फ़ोन को स्पीकर के और पास रखें" else "Placement: move this phone closer to the speaker") to Color.rgb(239, 108, 0)
            else -> (if (hi) "बहुत शोर है: फ़ोन स्पीकर के पास रखें, टीवी धीमा करें" else "Too noisy: put this phone next to the speaker, turn the TV down") to Color.rgb(198, 40, 40)
        }
        placement.text = "$txt  (${"%.0f".format(snrDb)} dB)"
        placement.setTextColor(col)
    }
}
