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
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var explain: TextView
    private lateinit var explainCard: LinearLayout
    private lateinit var why: TextView
    private lateinit var placement: TextView
    private lateinit var modeTag: TextView
    private lateinit var ready: LinearLayout
    private lateinit var perf: TextView
    private var lang = "hi"
    private var linkAddr: String? = null
    private var lastLlm: LlamaAdvisor.Stats? = null
    private var level = "none"
    private val handler = Handler(Looper.getMainLooper())
    private val perfTick = object : Runnable {
        override fun run() {
            if (perf.visibility == View.VISIBLE) perf.text = PerfStats.snapshot(this@GuardianActivity).overlayText(lastLlm, null)
            handler.postDelayed(this, 1000)
        }
    }

    private val hi get() = lang == "hi"
    private fun t(h: String, e: String) = if (hi) h else e

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.theme(this)
        Ui.keepScreenOn(this)
        lang = getPreferences(Context.MODE_PRIVATE).getString("lang", "hi") ?: "hi"
        linkAddr = getPreferences(Context.MODE_PRIVATE).getString("link", null)
        val col = Ui.column(this)
        col.addView(Ui.header(this, "KAVACH Guardian", t("कॉल सुनकर स्कैम पहचानता है · आवाज़ कभी सेव नहीं होती", "Listens to the call on speaker · audio is never saved"), listening = true))
        banner = Ui.banner(this).also { it.setOnLongClickListener { togglePerf(); true } }
        col.addView(banner)
        modeTag = Ui.pill(this); col.addView(modeTag)
        why = Ui.text(this, "", 16f, bold = true).apply { visibility = View.GONE }
        col.addView(why)
        placement = Ui.pill(this); col.addView(placement)

        // ---- main action
        col.addView(Ui.label(this, t("कॉल सुनना शुरू करें", "Start listening")))
        val listen = Ui.card(this)
        listen.addView(Ui.text(this, t("कॉल को स्पीकर पर रखें, यह फ़ोन उसके पास (10–30 सेमी) रखें, फिर कॉल की भाषा चुनें:",
            "Put the call on speaker, keep this phone 10–30 cm from it, then choose the call's language:"), 16f, color = Ui.MUTED))
        listen.addView(Ui.row(this, Ui.primary(this, "हिंदी / Hinglish") { startGuard("hi", null) }, Ui.primary(this, "English") { startGuard("en", null) }))
        listen.addView(Ui.danger(this, t("सुनना बंद करें", "Stop listening")) { stopGuard() })
        col.addView(listen)

        // ---- explanation (only when there is one)
        explainCard = Ui.card(this).apply { visibility = View.GONE }
        explainCard.addView(Ui.text(this, t("कवच ने क्या देखा", "What KAVACH noticed"), 14f, bold = true, color = Ui.GOLD))
        explain = Ui.text(this, "", 18f)
        explainCard.addView(explain)
        col.addView(explainCard)

        // ---- transcript
        col.addView(Ui.label(this, t("बातचीत (सिर्फ इसी फ़ोन पर, सेव नहीं होती)", "Live transcript (on this phone only, not saved)")))
        val tcard = Ui.card(this)
        transcript = Ui.text(this, t("सुनना शुरू करने पर बातचीत यहाँ दिखेगी।", "The conversation appears here once listening starts."), 17f, color = Ui.MUTED)
        tcard.addView(transcript)
        col.addView(tcard)

        // ---- readiness (truthful model and permission states)
        col.addView(Ui.label(this, t("तैयारी", "Readiness")))
        ready = Ui.card(this); col.addView(ready)

        // ---- more
        col.addView(Ui.label(this, t("और विकल्प", "More")))
        val more = Ui.card(this)
        more.addView(Ui.button(this, t("स्टैंडबाय: अनजान कॉल पर अपने-आप सुनें", "Standby: listen by itself on unknown calls")) { startStandby() })
        val prefs = getSharedPreferences("guardian", Context.MODE_PRIVATE)
        val auto = prefs.getBoolean(GuardianService.PREF_AUTOSTART, true)
        more.addView(Ui.button(this, t("अपने-आप शुरू: ", "Auto-start: ") + if (auto) t("चालू", "on") else t("बंद", "off")) {
            prefs.edit().putBoolean(GuardianService.PREF_AUTOSTART, !auto).apply(); recreate()
        })
        more.addView(Ui.row(this, Ui.button(this, t("डेमो कॉल", "Demo call")) { pickDemo() }, Ui.button(this, t("फ़ोन जोड़ें", "Link phone")) { pickPhone() }))
        more.addView(Ui.button(this, t("भाषा: English", "Language: हिंदी")) {
            lang = if (hi) "en" else "hi"; getPreferences(Context.MODE_PRIVATE).edit().putString("lang", lang).apply(); recreate()
        })
        more.addView(Ui.button(this, t("Llama अभी जाँचें (नमूना कॉल)", "Test Llama now (sample call)")) { testLlama() })
        more.addView(Ui.button(this, t("तकनीकी जानकारी", "Technical details")) { togglePerf() })
        col.addView(more)
        status = Ui.text(this, GuardianBus.lastStatus, 14f, color = Ui.MUTED); col.addView(status)
        perf = Ui.text(this, "", 13f, color = Ui.MUTED).apply { visibility = View.GONE; typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(perf)
        setContentView(Ui.scroll(this, col))
        renderMode(GuardianBus.mode)
        requestPermissionsIfNeeded()
    }

    override fun onResume() { super.onResume(); GuardianBus.add(this); handler.post(perfTick); refreshReadiness(); renderMode(GuardianBus.mode) }
    override fun onPause() { GuardianBus.remove(this); handler.removeCallbacks(perfTick); super.onPause() }

    // ------------------------------------------------------------------ truthful states

    private fun renderMode(mode: String) {
        when (mode) {
            "listening" -> { Ui.setBanner(banner, level, lang); modeTag.visibility = View.GONE }
            "demo" -> { Ui.setBanner(banner, level, lang); Ui.setPill(modeTag, t("डेमो — रिकॉर्ड की हुई स्क्रिप्ट, असली कॉल नहीं", "Demo — a scripted call, not a live call"), Ui.GOLD) }
            "standby" -> { Ui.setState(banner, "ready", t("स्टैंडबाय", "On standby"), t("अनजान नंबर से कॉल आने पर अपने-आप सुनना शुरू करेगा।", "Starts listening by itself when an unknown number calls.")); modeTag.visibility = View.GONE }
            "models_missing" -> { Ui.setState(banner, "caution", t("बोली पहचानने के मॉडल नहीं मिले", "Speech models missing"), t("नीचे 'तैयारी' देखें। डेमो कॉल फिर भी चलेगी।", "See Readiness below. The demo call still works.")); modeTag.visibility = View.GONE }
            else -> { level = "none"; Ui.setState(banner, "ready", t("अभी नहीं सुन रहा", "Not listening"), t("कॉल स्पीकर पर हो तो नीचे भाषा चुनें।", "When the call is on speaker, choose its language below.")); modeTag.visibility = View.GONE; placement.visibility = View.GONE }
        }
        refreshReadiness()
    }

    private fun refreshReadiness() {
        ready.removeAllViews()
        val dir = ModelFiles.dir(this)
        fun has(vararg rel: String) = rel.all { File(dir, it).isFile && File(dir, it).length() > 0 }
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hiAsr = has("asr-hi/model.int8.onnx", "asr-hi/tokens.txt")
        val enAsr = has("asr-en/encoder.onnx", "asr-en/decoder.onnx", "asr-en/joiner.onnx", "asr-en/tokens.txt")
        val vad = has("silero_vad.onnx")
        val llamaFile = ModelFiles.llamaPte(this).isFile && ModelFiles.llamaTokenizer(this).isFile
        ready.addView(Ui.status(this, t("माइक्रोफ़ोन", "Microphone"), mic, if (mic) t("अनुमति है", "allowed") else t("अनुमति चाहिए", "permission needed")))
        ready.addView(Ui.status(this, t("हिंदी/Hinglish बोली मॉडल", "Hindi/Hinglish speech model"), hiAsr && vad, if (hiAsr && vad) t("फ़ोन पर है", "on this phone") else t("नहीं है", "missing")))
        ready.addView(Ui.status(this, t("English बोली मॉडल", "English speech model"), enAsr && vad, if (enAsr && vad) t("फ़ोन पर है", "on this phone") else t("नहीं है", "missing")))
        val ls = GuardianBus.llamaState
        val (lok, ltext) = when {
            !llamaFile -> false to t("नहीं है — सिर्फ नियम + क्लासिफ़ायर", "missing — rules + classifier only")
            GuardianBus.llamaLastRun.isNotEmpty() -> true to GuardianBus.llamaLastRun
            ls == "ready" -> true to t("लोड हो गया, अभी चला नहीं", "loaded; has not run yet")
            ls == "failed" -> false to t("लोड नहीं हो सका", "failed to load")
            ls == "loading" -> false to t("लोड हो रहा है…", "loading…")
            else -> false to t("फ़ाइल है; सुनना शुरू करने पर लोड होगा", "file present; loads when listening starts")
        }
        ready.addView(Ui.status(this, "Llama 3.2 1B (ExecuTorch)", lok, ltext))
        val linked = when (linkAddr) { null -> t("नहीं जुड़ा", "not linked"); kavach.shared.LocalLink.SAME_PHONE -> t("इसी फ़ोन पर Shield", "Shield on this phone"); else -> t("ब्लूटूथ: ", "Bluetooth: ") + linkAddr }
        ready.addView(Ui.status(this, t("Shield से जुड़ाव", "Link to Shield"), linkAddr != null, linked))
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
        lang = language
        getPreferences(Context.MODE_PRIVATE).edit().putString("lang", lang).apply()
        level = "none"
        explain.text = ""; explainCard.visibility = View.GONE
        transcript.text = ""; transcript.setTextColor(Ui.TEXT)
        val i = Intent(this, GuardianService::class.java).setAction(GuardianService.ACTION_START)
            .putExtra(GuardianService.EXTRA_LANG, lang).putExtra(GuardianService.EXTRA_DEMO, demo).putExtra(GuardianService.EXTRA_LINK_ADDR, linkAddr)
        startForegroundService(i)
    }

    /** Standby: the service starts now (while this screen is visible, as Android requires for the
     *  microphone) and begins listening by itself when the protected phone reports an unknown call. */
    private fun startStandby() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissionsIfNeeded(); return }
        if (linkAddr == null) { status.text = t("पहले 'फ़ोन जोड़ें' से Shield वाला फ़ोन चुनें।", "First choose the Shield phone with Link phone."); return }
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
            refreshReadiness()
        }.show()
    }

    /** Proof that Llama runs on this phone: load it, explain a short scripted scam transcript, show the
     *  answer and the measured speed. Clearly labelled as a sample, separate from real calls. */
    private fun testLlama() {
        if (GuardianBus.running) { status.visibility = View.VISIBLE
            status.text = t("पहले 'सुनना बंद करें' दबाएँ (Llama अभी उपयोग में है)।", "Tap Stop listening first (Llama is in use)."); return }
        if (!ModelFiles.llamaPte(this).isFile) { status.visibility = View.VISIBLE; status.text = t("Llama फ़ाइल फ़ोन पर नहीं है।", "The Llama file is not on this phone."); return }
        explainCard.visibility = View.VISIBLE
        explain.text = t("Llama लोड हो रहा है और नमूना कॉल समझा रहा है (30–60 सेकंड)…", "Loading Llama and explaining a sample call (30–60 s)…")
        Thread {
            val adv = LlamaAdvisor(ModelFiles.llamaPte(this).path, ModelFiles.llamaTokenizer(this).path, timeoutMs = 60_000)
            val latch = java.util.concurrent.CountDownLatch(1)
            adv.loadAsync { latch.countDown() }
            latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
            GuardianBus.llamaState = adv.state.name.lowercase()
            if (adv.state != LlamaAdvisor.State.READY) {
                runOnUiThread { explain.text = t("Llama लोड नहीं हो सका।", "Llama failed to load on this phone."); refreshReadiness() }
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
                        explain.text = t("नमूना कॉल (स्क्रिप्ट) पर Llama का जवाब:\n", "Llama on a sample (scripted) call:\n") +
                            (v?.let { "${it.say}\n\n" + t("प्रकार: ", "Type: ") + "${it.type} · ${"%.0f".format(it.confidence * 100)}%" } ?: t("कोई मान्य जवाब नहीं।", "No valid answer.")) +
                            "\n\n" + run
                        refreshReadiness()
                    }
                }.start()
            }
        }.start()
    }

    private fun togglePerf() {
        val on = perf.visibility != View.VISIBLE
        perf.visibility = if (on) View.VISIBLE else View.GONE
        status.visibility = if (on) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ bus events
    override fun onMode(mode: String) = renderMode(mode)

    override fun onTranscript(finals: List<String>, partial: String) {
        transcript.setTextColor(Ui.TEXT)
        transcript.text = (finals.takeLast(12) + if (partial.isNotEmpty()) listOf("… $partial") else emptyList()).joinToString("\n")
    }

    override fun onState(s: GuardState) {
        level = s.level
        if (GuardianBus.mode == "listening" || GuardianBus.mode == "demo") Ui.setBanner(banner, s.level, lang)
        why.text = if (s.rule.isNotBlank() && s.level != "none") t("कारण: ", "Why: ") + kavach.core.KillChain.ruleLabel(s.rule, lang) else ""
        why.visibility = if (why.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (perf.visibility == View.VISIBLE) status.text = "p(scam)=${"%.2f".format(s.pScam)} score=${s.score} stages=${s.stages} rule=${s.rule}"
    }

    override fun onAlert(a: Alert) {
        level = a.level
        Ui.setBanner(banner, a.level, lang)
        explain.text = a.verdict.say
        explainCard.visibility = if (a.verdict.say.isBlank()) View.GONE else View.VISIBLE
    }

    override fun onLlm(v: Verdict, stats: LlamaAdvisor.Stats?) {
        lastLlm = stats
        val ev = if (v.evidence.isNotEmpty()) "\n" + t("सुनी गई बातें: ", "Heard: ") + "“" + v.evidence.joinToString("”, “") + "”" else ""
        explain.text = (if (v.say.isNotBlank()) v.say else explain.text.toString()) + ev +
            "\n" + t("Llama 3.2 1B ने इसी फ़ोन पर समझाया", "Explained by Llama 3.2 1B on this phone")
        explainCard.visibility = View.VISIBLE
        refreshReadiness()
    }

    override fun onStatus(msg: String) { status.text = msg; if (msg.startsWith("Llama")) refreshReadiness() }

    /** live placement check: is this phone close enough to the speaker-phone? (words carry the meaning) */
    override fun onPlacement(snrDb: Double) {
        val (txt, col) = when (kavach.core.AudioCheck.verdict(snrDb)) {
            "good" -> t("आवाज़ साफ़ सुनाई दे रही है", "Hearing the call clearly") to Ui.GOOD
            "move_closer" -> t("फ़ोन को स्पीकर के और पास रखें", "Move this phone closer to the speaker") to Ui.WARN
            else -> t("बहुत शोर — पास रखें, टीवी धीमा करें", "Too noisy — move closer, turn the TV down") to Ui.BAD
        }
        Ui.setPill(placement, "$txt · ${"%.0f".format(snrDb)} dB", col)
    }
}
