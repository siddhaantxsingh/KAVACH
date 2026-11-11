package kavach.guardian

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import kavach.core.Alert
import kavach.core.GuardState
import kavach.core.Verdict
import kavach.shared.LlamaAdvisor
import kavach.shared.PerfStats
import kavach.shared.Ui
import java.io.File

/**
 * Guardian: a focused listening appliance. The screen only claims what is true:
 * "Not listening" when idle, "Demo — scripted call" for a replay, "Speech models missing"
 * when they are, and the readiness card shows which models are on this phone and whether
 * Llama has actually run.
 */
class GuardianActivity : Activity(), GuardianBus.Listener {
    private lateinit var banner: Ui.Hero
    private lateinit var modeTag: Ui.Chip
    private lateinit var whyTag: Ui.Chip
    private lateinit var placement: Ui.Chip
    private var status: TextView? = null
    private var perf: TextView? = null
    private var bubbles: LinearLayout? = null
    private var explain: TextView? = null
    private var explainLead: TextView? = null
    private var explainFoot: Ui.Chip? = null
    private var explainCard: LinearLayout? = null
    private var actionHolder: LinearLayout? = null
    private var ready: LinearLayout? = null
    private var lang = "hi"
    private var tab = 0
    private var linkAddr: String? = null
    private var lastLlm: LlamaAdvisor.Stats? = null
    private var level = "none"
    // last values, so the screen can be rebuilt (tab change) without losing what it showed
    private var finals: List<String> = emptyList()
    private var partial = ""
    private var explainText = ""
    private var explainBy = ""
    private var whyText = ""
    private var snr: Double? = null
    private var techOn = false
    private val handler = Handler(Looper.getMainLooper())
    private val perfTick = object : Runnable {
        override fun run() {
            perf?.let { if (techOn) it.text = PerfStats.snapshot(this@GuardianActivity).overlayText(lastLlm, null) }
            handler.postDelayed(this, 1000)
        }
    }

    private val hi get() = lang == "hi"
    private fun t(h: String, e: String) = if (hi) h else e

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.keepScreenOn(this)
        lang = getPreferences(Context.MODE_PRIVATE).getString("lang", "hi") ?: "hi"
        linkAddr = getPreferences(Context.MODE_PRIVATE).getString("link", null)
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", 0)
        intent.getStringExtra("lang")?.let { lang = it; getPreferences(Context.MODE_PRIVATE).edit().putString("lang", it).apply() }
        build()
        if (!intent.getBooleanExtra("quiet", false)) requestPermissionsIfNeeded()
        intent.getStringExtra("demo")?.let { d -> intent.removeExtra("demo"); startGuard(if (d.endsWith("_en")) "en" else "hi", d) }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", tab) }

    /** Builds the whole screen for the current tab, then re-applies the last known state. */
    private fun build() {
        Ui.theme(this, Ui.P.card)
        status = null; perf = null; bubbles = null; explain = null; explainLead = null; explainCard = null; actionHolder = null; ready = null; explainFoot = null
        val (root, holder) = Ui.tabs(this, listOf(t("सुनें", "Listen"), t("सेटअप", "Setup")), listOf(Ui.Ic.ear, Ui.Ic.settings), tab) { tab = it; build() }
        val col = Ui.column(this)
        col.addView(Ui.header(this, "KAVACH Guardian", t("कॉल सुनकर स्कैम पहचानता है", "Hears the call. Spots the scam."), true,
            Ui.iconButton(this, Ui.Ic.languages, t("भाषा बदलें", "Change language")) { setLang(if (hi) "en" else "hi") }))
        banner = Ui.banner(this); modeTag = Ui.pill(this); whyTag = Ui.pill(this); placement = Ui.pill(this)
        if (tab == 0) buildListen(col) else buildSetup(col)
        holder.addView(Ui.scroll(this, col))
        setContentView(root)
        renderAll()
    }

    private fun buildListen(col: LinearLayout) {
        banner.setOnLongClickListener { techOn = !techOn; tab = 1; build(); true }
        banner.extras.addView(modeTag); banner.extras.addView(whyTag); banner.extras.addView(placement)
        col.addView(banner)

        col.addView(Ui.label(this, t("कॉल की भाषा", "Call language")))
        col.addView(Ui.segmented(this, listOf("हिंदी / Hinglish", "English"), if (hi) 0 else 1) { setLang(if (it == 0) "hi" else "en") })
        actionHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actionHolder)
        col.addView(Ui.text(this, t("कॉल को स्पीकर पर रखें और यह फ़ोन उसके पास (10–30 सेमी) रखें।", "Put the call on speaker and keep this phone 10–30 cm from it."), 13.5f, color = Ui.MUTED)
            .apply { gravity = Gravity.CENTER; setPadding(Ui.dp(this@GuardianActivity, 12), Ui.dp(this@GuardianActivity, 6), Ui.dp(this@GuardianActivity, 12), 0) })

        // explanation (only when there is one)
        explainCard = Ui.card(this).apply { visibility = View.GONE; (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(this@GuardianActivity, 18) }
        val eh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        eh.addView(Ui.icon(this, Ui.Ic.sparkles, Ui.GOLD, 18))
        eh.addView(Ui.text(this, t("कवच ने क्या देखा", "What KAVACH noticed"), 14f, color = Ui.GOLD, weight = 600).apply { setPadding(Ui.dp(this@GuardianActivity, 8), 0, 0, 0) })
        explainCard!!.addView(eh)
        explainLead = Ui.text(this, "", 18f, weight = 600).apply { setPadding(0, Ui.dp(this@GuardianActivity, 10), 0, 0) }
        explainCard!!.addView(explainLead)
        explain = Ui.text(this, "", 15.5f, color = Ui.MUTED).apply { setPadding(0, Ui.dp(this@GuardianActivity, 6), 0, 0); setLineSpacing(Ui.dp(this@GuardianActivity, 4).toFloat(), 1f) }
        explainCard!!.addView(explain)
        explainFoot = Ui.pill(this); explainCard!!.addView(explainFoot)
        col.addView(explainCard)

        // transcript
        col.addView(Ui.label(this, t("लाइव बातचीत", "Live transcript")))
        val tcard = Ui.card(this, 14)
        bubbles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tcard.addView(bubbles)
        col.addView(tcard)
        col.addView(Ui.footnote(this, t("आवाज़ इसी फ़ोन पर रहती है · कभी सेव नहीं होती", "Audio stays on this phone · never saved")))
    }

    private fun buildSetup(col: LinearLayout) {
        col.addView(Ui.label(this, t("तैयारी", "Readiness")))
        ready = Ui.group(this); col.addView(ready)

        col.addView(Ui.label(this, t("अपने-आप", "Automation")))
        val prefs = getSharedPreferences("guardian", Context.MODE_PRIVATE)
        col.addView(Ui.group(this,
            Ui.item(this, Ui.Ic.radio, t("स्टैंडबाय", "Standby"), t("अनजान कॉल आने पर अपने-आप सुनना शुरू करे", "Start listening by itself when an unknown number calls"), chevron = true) { startStandby() },
            Ui.item(this, Ui.Ic.zap, t("अपने-आप शुरू", "Auto-start"), t("Shield के बताने पर सुनना शुरू करे", "Begin when Shield reports a call"),
                trailing = Ui.toggle(this, prefs.getBoolean(GuardianService.PREF_AUTOSTART, true), "Auto-start") { prefs.edit().putBoolean(GuardianService.PREF_AUTOSTART, it).apply() }),
            Ui.item(this, Ui.Ic.smartphone, t("Shield वाला फ़ोन", "Phone with Shield"), linkedText(), chevron = true) { pickPhone() }))

        col.addView(Ui.label(this, t("जाँच के औज़ार", "Tools")))
        col.addView(Ui.group(this,
            Ui.item(this, Ui.Ic.play, t("डेमो कॉल चलाएँ", "Play a demo call"), t("रिकॉर्ड की हुई स्क्रिप्ट, असली कॉल नहीं", "A scripted call, not a live one"), chevron = true) { pickDemo() },
            Ui.item(this, Ui.Ic.cpu, t("फ़ोन का AI जाँचें", "Test the on-device AI"), t("Llama 3.2 1B से नमूना कॉल समझवाएँ", "Ask Llama 3.2 1B to explain a sample call"), chevron = true) { testLlama() },
            Ui.item(this, Ui.Ic.activity, t("तकनीकी जानकारी", "Technical details"), t("गति, मेमोरी और स्कोर दिखाएँ", "Show speed, memory and scores"),
                trailing = Ui.toggle(this, techOn, "Technical details") { techOn = it; renderTech() })))

        status = Ui.text(this, GuardianBus.lastStatus, 13f, color = Ui.MUTED).apply { setPadding(Ui.dp(this@GuardianActivity, 4), Ui.dp(this@GuardianActivity, 8), 0, 0) }
        col.addView(status)
        perf = Ui.text(this, "", 12.5f, color = Ui.MUTED).apply { typeface = android.graphics.Typeface.MONOSPACE; setPadding(Ui.dp(this@GuardianActivity, 4), 0, 0, 0) }
        col.addView(perf)
        col.addView(Ui.footnote(this, t("इस ऐप के पास इंटरनेट की अनुमति नहीं है", "This app has no internet permission"), Ui.Ic.wifi))
    }

    private fun setLang(l: String) {
        if (l == lang) return
        lang = l; getPreferences(Context.MODE_PRIVATE).edit().putString("lang", lang).apply(); build()
    }

    private fun linkedText() = when (linkAddr) { null -> t("नहीं जुड़ा", "Not linked"); kavach.shared.LocalLink.SAME_PHONE -> t("इसी फ़ोन पर Shield", "Shield on this phone"); else -> t("ब्लूटूथ: ", "Bluetooth: ") + linkAddr }

    override fun onResume() { super.onResume(); GuardianBus.add(this); handler.post(perfTick); renderAll() }
    override fun onPause() { GuardianBus.remove(this); handler.removeCallbacks(perfTick); super.onPause() }

    // ------------------------------------------------------------------ truthful states

    private fun renderAll() { renderMode(GuardianBus.mode); renderTranscript(); renderExplain(); renderTech() }

    private fun renderTech() { status?.visibility = if (techOn) View.VISIBLE else View.GONE; perf?.visibility = if (techOn) View.VISIBLE else View.GONE }

    private fun renderMode(mode: String) {
        val live = mode == "listening" || mode == "demo"
        when (mode) {
            "listening", "demo" -> Ui.setBanner(banner, level, lang)
            "standby" -> Ui.setState(banner, "ready", t("स्टैंडबाय पर", "On standby"), t("अनजान नंबर से कॉल आने पर अपने-आप सुनना शुरू करेगा।", "Starts listening by itself when an unknown number calls."), Ui.Ic.radio)
            "models_missing" -> Ui.setState(banner, "caution", t("बोली पहचानने के मॉडल नहीं मिले", "Speech models missing"), t("सेटअप में 'तैयारी' देखें। डेमो कॉल फिर भी चलेगी।", "See Readiness in Setup. The demo call still works."))
            else -> { level = "none"; whyText = ""; snr = null
                Ui.setState(banner, "ready", t("अभी नहीं सुन रहा", "Not listening"), t("कॉल स्पीकर पर हो तो नीचे 'सुनना शुरू करें' दबाएँ।", "When a call is on speaker, tap Start listening."), Ui.Ic.micOff) }
        }
        // chips inside the hero: readable on every state colour
        val solid = live && (level == "stop" || level == "caution")
        val fg = when { live && level == "stop" -> 0xFFFFFFFF.toInt(); live && level == "caution" -> Ui.P.onCaution; else -> Ui.TEXT }
        val bg = when { live && level == "stop" -> 0x33FFFFFF; live && level == "caution" -> 0x1F000000; else -> if (Ui.P.dark) 0x33000000 else 0xFFFFFFFF.toInt() }
        if (mode == "demo") Ui.setPill(modeTag, t("डेमो — रिकॉर्ड की हुई स्क्रिप्ट, असली कॉल नहीं", "Demo — a scripted call, not a live call"), fg, bg, Ui.Ic.play) else modeTag.visibility = View.GONE
        if (live && whyText.isNotEmpty()) Ui.setPill(whyTag, whyText, fg, bg, Ui.Ic.info) else whyTag.visibility = View.GONE
        val s = snr
        if (mode == "listening" && s != null) {
            val (txt, col) = when (kavach.core.AudioCheck.verdict(s)) {
                "good" -> t("आवाज़ साफ़ सुनाई दे रही है", "Hearing the call clearly") to Ui.GOOD
                "move_closer" -> t("फ़ोन को स्पीकर के और पास रखें", "Move this phone closer to the speaker") to Ui.WARN
                else -> t("बहुत शोर — पास रखें, टीवी धीमा करें", "Too noisy — move closer, turn the TV down") to Ui.BAD
            }
            Ui.setPill(placement, txt, if (solid) fg else col, bg, Ui.Ic.signal)
        } else placement.visibility = View.GONE

        actionHolder?.let { h ->
            h.removeAllViews()
            if (GuardianBus.running || live || mode == "standby") h.addView(Ui.danger(this, t("सुनना बंद करें", "Stop listening"), Ui.Ic.micOff) { stopGuard() })
            else h.addView(Ui.primary(this, t("सुनना शुरू करें", "Start listening"), Ui.Ic.mic) { startGuard(lang, null) })
        }
        refreshReadiness()
    }

    private fun renderTranscript() {
        val b = bubbles ?: return
        b.removeAllViews()
        if (finals.isEmpty() && partial.isEmpty()) {
            b.addView(Ui.text(this, t("सुनना शुरू करने पर बातचीत यहाँ दिखेगी।", "The conversation appears here once listening starts."), 15f, color = Ui.MUTED)
                .apply { setPadding(Ui.dp(this@GuardianActivity, 6), Ui.dp(this@GuardianActivity, 10), Ui.dp(this@GuardianActivity, 6), Ui.dp(this@GuardianActivity, 10)) })
            return
        }
        finals.takeLast(10).forEach { b.addView(Ui.bubble(this, it, false)) }
        if (partial.isNotEmpty()) b.addView(Ui.bubble(this, "$partial …", true))
    }

    private fun renderExplain() {
        val c = explainCard ?: return
        c.visibility = if (explainText.isBlank()) View.GONE else View.VISIBLE
        val (lead, rest) = Ui.splitLead(explainText)
        explainLead?.text = lead
        explain?.text = rest; explain?.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        explainFoot?.let { if (explainBy.isNotEmpty()) Ui.setPill(it, explainBy, Ui.P.accentText, Ui.P.accentSoft, Ui.Ic.cpu) else it.visibility = View.GONE }
    }

    private fun refreshReadiness() {
        val ready = ready ?: return
        ready.removeAllViews()
        val dir = ModelFiles.dir(this)
        fun has(vararg rel: String) = rel.all { File(dir, it).isFile && File(dir, it).length() > 0 }
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hiAsr = has("asr-hi/model.int8.onnx", "asr-hi/tokens.txt")
        val enAsr = has("asr-en/encoder.onnx", "asr-en/decoder.onnx", "asr-en/joiner.onnx", "asr-en/tokens.txt")
        val vad = has("silero_vad.onnx")
        val llamaFile = ModelFiles.llamaPte(this).isFile && ModelFiles.llamaTokenizer(this).isFile
        Ui.addRow(ready, Ui.status(this, t("माइक्रोफ़ोन", "Microphone"), mic, if (mic) t("अनुमति है", "Allowed") else t("अनुमति चाहिए", "Permission needed")))
        Ui.addRow(ready, Ui.status(this, t("हिंदी / Hinglish बोली मॉडल", "Hindi / Hinglish speech model"), hiAsr && vad, if (hiAsr && vad) t("इस फ़ोन पर है", "On this phone") else t("नहीं है", "Missing")))
        Ui.addRow(ready, Ui.status(this, t("English बोली मॉडल", "English speech model"), enAsr && vad, if (enAsr && vad) t("इस फ़ोन पर है", "On this phone") else t("नहीं है", "Missing")))
        val ls = GuardianBus.llamaState
        val (lok, ltext) = when {
            !llamaFile -> false to t("नहीं है — सिर्फ नियम + क्लासिफ़ायर", "Missing — rules + classifier only")
            GuardianBus.llamaLastRun.isNotEmpty() -> true to GuardianBus.llamaLastRun.replaceFirstChar { it.uppercase() }
            ls == "ready" -> true to t("लोड हो गया, अभी चला नहीं", "Loaded; has not run yet")
            ls == "failed" -> false to t("लोड नहीं हो सका", "Failed to load")
            ls == "loading" -> false to t("लोड हो रहा है…", "Loading…")
            else -> false to t("फ़ाइल है; सुनना शुरू करने पर लोड होगा", "File present; loads when listening starts")
        }
        Ui.addRow(ready, Ui.status(this, "Llama 3.2 1B · ExecuTorch", lok, ltext))
        Ui.addRow(ready, Ui.status(this, t("Shield से जुड़ाव", "Link to Shield"), linkAddr != null, linkedText()))
    }

    private fun requestPermissionsIfNeeded() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { refreshReadiness() }

    private fun startGuard(language: String, demo: String?) {
        if (demo == null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded(); return
        }
        val langChanged = language != lang
        lang = language
        getPreferences(Context.MODE_PRIVATE).edit().putString("lang", lang).apply()
        level = "none"
        explainText = ""; explainBy = ""; whyText = ""; snr = null; finals = emptyList(); partial = ""
        if (tab != 0 || langChanged) { tab = 0; build() } else { renderTranscript(); renderExplain() }
        val i = Intent(this, GuardianService::class.java).setAction(GuardianService.ACTION_START)
            .putExtra(GuardianService.EXTRA_LANG, lang).putExtra(GuardianService.EXTRA_DEMO, demo).putExtra(GuardianService.EXTRA_LINK_ADDR, linkAddr)
        startForegroundService(i)
    }

    /** Standby: the service starts now (while this screen is visible, as Android requires for the
     *  microphone) and begins listening by itself when the protected phone reports an unknown call. */
    private fun startStandby() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissionsIfNeeded(); return }
        if (linkAddr == null) { toast(t("पहले 'Shield वाला फ़ोन' चुनें।", "First choose the phone with Shield.")); return }
        startForegroundService(Intent(this, GuardianService::class.java).setAction(GuardianService.ACTION_STANDBY)
            .putExtra(GuardianService.EXTRA_LANG, lang).putExtra(GuardianService.EXTRA_LINK_ADDR, linkAddr))
    }

    private fun stopGuard() {
        if (!GuardianBus.running) {
            android.widget.Toast.makeText(this, t("अभी सुन नहीं रहा है।", "Not listening right now."), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, GuardianService::class.java).setAction(GuardianService.ACTION_STOP))
        android.widget.Toast.makeText(this, t("सुनना बंद किया।", "Stopped listening."), android.widget.Toast.LENGTH_SHORT).show()
        renderMode("idle")
    }

    private fun pickDemo() {
        val names = assets.list("demo")?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") } ?: emptyList()
        val pretty = names.map { n ->
            val l = when { n.endsWith("_hinglish") -> "Hinglish"; n.endsWith("_hi") -> "हिंदी"; n.endsWith("_en") -> "English"; else -> "" }
            n.substringBeforeLast('_').replace('_', ' ').replaceFirstChar { it.uppercase() } + if (l.isNotEmpty()) " · $l" else ""
        }
        AlertDialog.Builder(this, Ui.dialogTheme()).setTitle(t("डेमो कॉल चुनें (रिकॉर्ड की हुई स्क्रिप्ट)", "Play a demo call (scripted)")).setItems(pretty.toTypedArray()) { _, w ->
            startGuard(if (names[w].endsWith("_en")) "en" else "hi", names[w])
        }.show()
    }

    @Suppress("MissingPermission")
    private fun pickPhone() {
        val names = arrayListOf(t("यही फ़ोन (KAVACH Shield यहीं है)", "This phone (KAVACH Shield is installed here)"))
        val addrs = arrayListOf(kavach.shared.LocalLink.SAME_PHONE)
        val btAllowed = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (btAllowed) {
            val bonded = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices?.toList() ?: emptyList()
            for (d in bonded) { names.add("Bluetooth: " + (d.name ?: d.address)); addrs.add(d.address) }
        }
        AlertDialog.Builder(this, Ui.dialogTheme()).setTitle(t("KAVACH Shield किस फ़ोन पर है?", "Where is KAVACH Shield running?")).setItems(names.toTypedArray()) { _, w ->
            linkAddr = addrs[w]
            getPreferences(Context.MODE_PRIVATE).edit().putString("link", linkAddr).apply()
            build()
        }.show()
    }

    /** Proof that Llama runs on this phone: load it, explain a short scripted scam transcript, show the
     *  answer and the measured speed. Clearly labelled as a sample, separate from real calls. */
    private fun testLlama() {
        if (GuardianBus.running) { toast(t("पहले 'सुनना बंद करें' दबाएँ (Llama अभी उपयोग में है)।", "Tap Stop listening first (Llama is in use).")); return }
        if (!ModelFiles.llamaPte(this).isFile) { toast(t("Llama फ़ाइल इस फ़ोन पर नहीं है।", "The Llama file is not on this phone.")); return }
        explainBy = ""
        explainText = t("Llama लोड हो रहा है और नमूना कॉल समझा रहा है (30–60 सेकंड)…", "Loading Llama and explaining a sample call (30–60 s)…")
        tab = 0; build()
        Thread {
            val adv = LlamaAdvisor(ModelFiles.llamaPte(this).path, ModelFiles.llamaTokenizer(this).path, timeoutMs = 60_000)
            val latch = java.util.concurrent.CountDownLatch(1)
            adv.loadAsync { latch.countDown() }
            latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
            GuardianBus.llamaState = adv.state.name.lowercase()
            if (adv.state != LlamaAdvisor.State.READY) {
                runOnUiThread { explainText = t("Llama लोड नहीं हो सका।", "Llama failed to load on this phone."); renderExplain(); refreshReadiness() }
                adv.close(); return@Thread
            }
            kavach.shared.KavachRuntime.ensureLoaded(this) { core ->
                Thread {
                    val g = core.newGuardian(kavach.core.GuardianConfig(lang = "en"))
                    val window = "I am Inspector Vikram Singh from Mumbai cyber crime branch. A parcel booked on your Aadhaar card " +
                        "contains drugs and fake passports. An arrest warrant has been issued. Do not tell anyone about this call. " +
                        "To prove your innocence transfer your savings to the RBI safe account now."
                    val active = mapOf("authority_police" to 1.0, "identity_misuse" to 1.0, "threat" to 1.0, "isolation" to 1.0, "payment_request" to 1.0)
                    val retrieved = core.playbook.entries.filter { it.id == "digital_arrest" || it.id == "courier_parcel" }
                    val v = g.askLlm(adv, window, active, retrieved)
                    val run = GuardianService.describeLlamaRun(adv, v != null)
                    GuardianBus.llamaLastRun = run
                    adv.close()
                    runOnUiThread {
                        explainBy = t("नमूना कॉल · Llama 3.2 1B इसी फ़ोन पर", "Sample call · Llama 3.2 1B on this phone")
                        explainText =
                            (v?.let { "${it.say}\n\n" + t("प्रकार: ", "Type: ") + it.type + (if (it.confidence > 0.0) " · ${"%.0f".format(it.confidence * 100)}%" else "") } ?: t("कोई मान्य जवाब नहीं।", "No valid answer.")) +
                            "\n\n" + run
                        renderExplain(); refreshReadiness()
                    }
                }.start()
            }
        }.start()
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ bus events
    override fun onMode(mode: String) = renderMode(mode)

    override fun onTranscript(finals: List<String>, partial: String) { this.finals = finals; this.partial = partial; renderTranscript() }

    override fun onState(s: GuardState) {
        level = s.level
        whyText = if (s.rule.isNotBlank() && s.level != "none") kavach.core.KillChain.ruleLabel(s.rule, lang) else ""
        renderMode(GuardianBus.mode)
        if (techOn) status?.text = "p(scam)=${"%.2f".format(s.pScam)} score=${s.score} stages=${s.stages} rule=${s.rule}"
    }

    override fun onAlert(a: Alert) {
        level = a.level
        explainText = a.verdict.say; explainBy = ""
        renderMode(GuardianBus.mode); renderExplain()
    }

    override fun onLlm(v: Verdict, stats: LlamaAdvisor.Stats?) {
        lastLlm = stats
        val ev = if (v.evidence.isNotEmpty()) "\n\n" + t("सुनी गई बातें: ", "Heard: ") + "“" + v.evidence.joinToString("”, “") + "”" else ""
        explainText = (if (v.say.isNotBlank()) v.say else explainText) + ev
        explainBy = when (GuardianBus.llamaSayNote) {
            "" -> t("Llama 3.2 1B ने इसी फ़ोन पर समझाया", "Explained by Llama 3.2 1B on this phone")
            else -> t("Llama 3.2 1B ने इसी फ़ोन पर जाँचा · सलाह कवच की प्लेबुक से", "Checked by Llama 3.2 1B on this phone · advice from KAVACH's playbook")
        }
        renderExplain(); refreshReadiness()
    }

    override fun onStatus(msg: String) { status?.text = msg; if (msg.startsWith("Llama")) refreshReadiness() }

    /** live placement check: is this phone close enough to the speaker-phone? (words carry the meaning) */
    override fun onPlacement(snrDb: Double) { snr = snrDb; renderMode(GuardianBus.mode) }
}
