package kavach.guardian

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    companion object {
        /** true while the Calls screen is on screen (then it shows STOP itself) */
        @Volatile var visible = false
    }

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
    /** call language setting: "auto" (default) | "hi" | "en" */
    private var lang = "auto"
    private var tab = 0
    private var protectHolder: LinearLayout? = null
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

    /** Screen language (app setting); `lang` is the language of the call being heard. */
    private val uiLang get() = kavach.app.ShieldHub.lang(this)
    private val hi get() = uiLang == "hi"
    private fun t(h: String, e: String) = if (hi) h else e
    private fun d(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.keepScreenOn(this)
        lang = getSharedPreferences("guardian", Context.MODE_PRIVATE).getString("call_language", "auto") ?: "auto"
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", 0)
        intent.getStringExtra("lang")?.let { lang = it }
        build()
        if (!intent.getBooleanExtra("quiet", false)) requestPermissionsIfNeeded()
        intent.getStringExtra("demo")?.let { d -> intent.removeExtra("demo"); startGuard(if (d.endsWith("_en")) "en" else "hi", d) }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", tab) }

    /** Builds the whole screen for the current section, then re-applies the last known state. */
    private fun build() {
        Ui.theme(this, Ui.P.card)
        status = null; perf = null; bubbles = null; explain = null; explainLead = null; explainCard = null; actionHolder = null; ready = null; explainFoot = null; protectHolder = null
        val (root, holder) = Ui.tabs(this, kavach.app.Nav.labels(hi), kavach.app.Nav.icons, kavach.app.Nav.CALLS) { if (it != kavach.app.Nav.CALLS) kavach.app.Nav.go(this, it) }
        val col = Ui.column(this)
        col.addView(Ui.header(this, t("कॉल सुरक्षा", "Call protection"), t("कॉल सुनकर स्कैम पहचानता है", "Hears the call. Spots the scam."), true))
        col.addView(Ui.segmented(this, listOf(t("सुनें", "Listen"), t("तैयारी और औज़ार", "Setup & tools")), tab) { tab = it; build() }
            .apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = d(14) })
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

        protectHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(protectHolder)

        col.addView(Ui.label(this, t("कॉल की भाषा", "Language of the call")))
        col.addView(Ui.segmented(this, listOf(t("अपने आप", "Auto"), "हिंदी / Hinglish", "English"), when (lang) { "hi" -> 1; "en" -> 2; else -> 0 }) {
            setLang(when (it) { 1 -> "hi"; 2 -> "en"; else -> "auto" }) })
        if (lang == "auto") col.addView(Ui.footnote(this, if (AsrEngine.autoReady(ModelFiles.dir(this)))
            t("KAVACH हर वाक्य की भाषा खुद पहचानता है — हिंदी, Hinglish या English, बीच में बदले तो भी।", "KAVACH recognises the language of every sentence by itself: Hindi, Hinglish or English, even if the caller switches.")
            else t("Auto के लिए दोनों बड़े मॉडल चाहिए (push_models.sh --asr-hi-big और --asr-en-big)। अभी हिंदी/Hinglish मॉडल सुनेगा।", "Auto needs both big speech models (push_models.sh --asr-hi-big and --asr-en-big). Until then it listens with the Hindi/Hinglish model."), Ui.Ic.info))
        actionHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actionHolder)
        col.addView(Ui.text(this, t("कॉल को स्पीकर पर रखें। फ़ोन आपके हाथ या मेज़ पर रहे — कवच इसी फ़ोन पर सुनता है।",
            "Put the call on speaker. Keep the phone in your hand or on the table — KAVACH listens on this phone."), 13.5f, color = Ui.MUTED)
            .apply { gravity = Gravity.CENTER; setPadding(d(12), d(6), d(12), 0) })

        // explanation (only when there is one)
        explainCard = Ui.card(this).apply { visibility = View.GONE; (layoutParams as LinearLayout.LayoutParams).topMargin = d(18) }
        val eh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        eh.addView(Ui.icon(this, Ui.Ic.sparkles, Ui.GOLD, 18))
        eh.addView(Ui.text(this, t("कवच ने क्या देखा", "What KAVACH noticed"), 14f, color = Ui.GOLD, weight = 600).apply { setPadding(d(8), 0, 0, 0) })
        explainCard!!.addView(eh)
        explainLead = Ui.text(this, "", 18f, weight = 600).apply { setPadding(0, d(10), 0, 0) }
        explainCard!!.addView(explainLead)
        explain = Ui.text(this, "", 15.5f, color = Ui.MUTED).apply { setPadding(0, d(6), 0, 0); setLineSpacing(d(4).toFloat(), 1f) }
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

    /** "Protect my calls": the switch, and what is still missing for it to work on a real call. */
    private fun renderProtect() {
        val h = protectHolder ?: return
        h.removeAllViews()
        val on = GuardianService.protecting
        val stuck = kavach.shared.AppState.callAccessStuck(this)
        val access = kavach.shared.AppState.callAccessEnabled(this) && !stuck
        val g = Ui.group(this, Ui.item(this, Ui.Ic.shieldCheck, t("मेरी कॉल सुरक्षित रखें", "Protect my calls"),
            if (on) t("चालू — अनजान नंबर की कॉल आते ही सुनना शुरू", "On — listening starts when an unknown number calls")
            else t("बंद — चालू करें ताकि हर अनजान कॉल पर कवच साथ रहे", "Off — turn on so KAVACH joins every call from an unknown number"),
            trailing = Ui.toggle(this, on, t("मेरी कॉल सुरक्षित रखें", "Protect my calls")) { v -> if (v) startStandby() else stopStandby() }))
        if (stuck) Ui.addRow(g, Ui.item(this, Ui.Ic.ear, t("कॉल सुनना Android ने रोक दिया", "Android stopped call listening"),
            t("स्विच 'चालू' दिखता है पर Android उसे नहीं चला रहा, इसलिए कॉल के दौरान माइक चुप रहेगा। सेटिंग में 'KAVACH call listening' बंद करें, फिर चालू करें।",
                "The switch still shows on, but Android is not running it, so calls would be heard as silence. In Settings turn 'KAVACH call listening' OFF, then ON again."),
            trailing = Ui.small(this, t("ठीक करें", "Fix")) { kavach.shared.AppState.openCallAccessSettings(this) }, fg = Ui.WARN, bg = Ui.P.warnSoft))
        else if (!access) Ui.addRow(g, Ui.item(this, Ui.Ic.ear, t("कॉल सुनने की अनुमति दें", "Allow call listening"),
            t("ज़रूरी: कॉल के दौरान Android माइक सिर्फ़ इस अनुमति से देता है। सेटिंग में 'KAVACH call listening' चालू करें।",
                "Needed for real calls: during a call Android only shares the mic with this permission. Turn on 'KAVACH call listening' in Settings."),
            trailing = Ui.small(this, t("चालू करें", "Allow")) { showAccessHelp() }, fg = Ui.WARN, bg = Ui.P.warnSoft))
        h.addView(g)
    }

    /** Explains the accessibility step before opening Settings (Xiaomi and others hide it a little). */
    private fun showAccessHelp() {
        AlertDialog.Builder(this, Ui.dialogTheme())
            .setTitle(t("कॉल सुनने की अनुमति", "Allow call listening"))
            .setMessage(t("सेटिंग में: Downloaded apps / Installed services → KAVACH call listening → चालू करें।\n\nकवच इससे सिर्फ़ स्पीकर पर रखी कॉल सुनता है। स्क्रीन नहीं पढ़ता, कुछ दबाता नहीं, आवाज़ सेव नहीं करता।",
                "In Settings: Downloaded apps (or Installed services) → KAVACH call listening → turn it on.\n\nKAVACH uses it only to hear a call on speaker. It does not read your screen, tap anything, or save audio."))
            .setPositiveButton(t("सेटिंग खोलें", "Open Settings")) { _, _ -> kavach.shared.AppState.openCallAccessSettings(this) }
            .setNegativeButton(t("बाद में", "Later"), null).show()
    }

    private fun buildSetup(col: LinearLayout) {
        col.addView(Ui.label(this, t("तैयारी", "Readiness")).apply { setPadding(d(4), d(4), 0, d(10)) })
        ready = Ui.group(this); col.addView(ready)

        col.addView(Ui.label(this, t("जाँच के औज़ार", "Tools")))
        col.addView(Ui.group(this,
            Ui.item(this, Ui.Ic.play, t("डेमो कॉल चलाएँ", "Play a demo call"), t("रिकॉर्ड की हुई स्क्रिप्ट, असली कॉल नहीं", "A scripted call, not a live one"), chevron = true) { pickDemo() },
            Ui.item(this, Ui.Ic.cpu, t("फ़ोन का AI जाँचें", "Test the on-device AI"), t("Llama 3.2 1B से नमूना कॉल समझवाएँ", "Ask Llama 3.2 1B to explain a sample call"), chevron = true) { testLlama() },
            Ui.item(this, Ui.Ic.zap, t("प्रदर्शन मापें", "Measure performance"), t("Arm CPU पर गति, मेमोरी, बैटरी और तापमान", "Speed, memory, battery and temperature on this Arm CPU"), chevron = true) {
                startActivity(Intent(this, BenchmarkActivity::class.java)) },
            Ui.item(this, Ui.Ic.activity, t("तकनीकी जानकारी", "Technical details"), t("गति, मेमोरी और स्कोर दिखाएँ", "Show speed, memory and scores"),
                trailing = Ui.toggle(this, techOn, "Technical details") { techOn = it; renderTech() })))

        status = Ui.text(this, GuardianBus.lastStatus, 13f, color = Ui.MUTED).apply { setPadding(d(4), d(8), 0, 0) }
        col.addView(status)
        perf = Ui.text(this, "", 12.5f, color = Ui.MUTED).apply { typeface = android.graphics.Typeface.MONOSPACE; setPadding(d(4), 0, 0, 0) }
        col.addView(perf)
        col.addView(Ui.footnote(this, t("इस ऐप के पास इंटरनेट की अनुमति नहीं है", "This app has no internet permission"), Ui.Ic.wifi))
    }

    private fun setLang(l: String) {
        if (l == lang) return
        lang = l
        getSharedPreferences("guardian", Context.MODE_PRIVATE).edit().putString("call_language", lang).apply()
        // call protection is on: it should listen in the newly chosen language from the next call
        if (GuardianService.protecting && GuardianBus.running && GuardianBus.mode == "standby") kavach.shared.AppState.startCallProtection(this, lang)
        build()
    }

    override fun onResume() { super.onResume(); visible = true; GuardianBus.add(this); handler.post(perfTick); build() }
    override fun onPause() { visible = false; GuardianBus.remove(this); handler.removeCallbacks(perfTick); super.onPause() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        intent.getStringExtra("demo")?.let { d -> intent.removeExtra("demo"); startGuard(if (d.endsWith("_en")) "en" else "hi", d) }
    }

    // ------------------------------------------------------------------ truthful states

    private fun renderAll() { renderMode(GuardianBus.mode); renderTranscript(); renderExplain(); renderTech(); renderProtect() }

    private fun renderTech() { status?.visibility = if (techOn) View.VISIBLE else View.GONE; perf?.visibility = if (techOn) View.VISIBLE else View.GONE }

    private fun renderMode(mode: String) {
        val live = mode == "listening" || mode == "demo"
        when (mode) {
            "listening", "demo" -> if (mode == "listening" && GuardianBus.micBlocked && level != "stop" && level != "caution")
                Ui.setState(banner, "caution", t("Android इस कॉल में माइक नहीं दे रहा", "Android is not sharing the microphone in this call"),
                    t("सेटिंग → ऐक्सेसिबिलिटी → 'KAVACH call listening' बंद करें, फिर चालू करें।", "Settings → Accessibility → turn 'KAVACH call listening' OFF, then ON again."), Ui.Ic.micOff)
                else Ui.setBanner(banner, level, uiLang)
            "standby" -> Ui.setState(banner, "ready", t("कॉल के लिए तैयार", "Ready for calls"), t("अनजान नंबर की कॉल आते ही सुनना शुरू करेगा। कॉल स्पीकर पर रखें।", "Starts listening when an unknown number calls. Put the call on speaker."), Ui.Ic.shieldCheck)
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
            if (live) h.addView(Ui.danger(this, t("सुनना बंद करें", "Stop listening"), Ui.Ic.micOff) { stopGuard() })
            else h.addView(Ui.primary(this, t("अभी सुनना शुरू करें", "Start listening now"), Ui.Ic.mic) { startGuard(lang, null) })
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
        val hiBig = AsrEngine.hasIndicConformer(dir)
        val hiAsr = hiBig || has("asr-hi/model.int8.onnx", "asr-hi/tokens.txt")
        val enBig = AsrEngine.hasEnglishBig(dir)
        val enAsr = enBig || has("asr-en/encoder.onnx", "asr-en/decoder.onnx", "asr-en/joiner.onnx", "asr-en/tokens.txt")
        val vad = has("silero_vad.onnx")
        val llamaFile = ModelFiles.llamaPte(this).isFile && ModelFiles.llamaTokenizer(this).isFile
        Ui.addRow(ready, Ui.status(this, t("माइक्रोफ़ोन", "Microphone"), mic, if (mic) t("अनुमति है", "Allowed") else t("अनुमति चाहिए", "Permission needed")))
        val stuckR = kavach.shared.AppState.callAccessStuck(this)
        val access = kavach.shared.AppState.callAccessEnabled(this) && !stuckR
        Ui.addRow(ready, Ui.status(this, t("कॉल सुनने की अनुमति", "Call listening permission"), access,
            if (access) t("चालू और चल रहा — स्पीकर पर रखी कॉल सुन सकता है", "On and running — can hear a call on speaker")
            else if (stuckR) t("Android ने रोका — सेटिंग में बंद करके फिर चालू करें", "Stopped by Android — turn it off and on again in Settings")
            else t("बंद — सिर्फ़ डेमो और पास के स्पीकर", "Off — demo and nearby speakers only")))
        Ui.addRow(ready, Ui.status(this, t("हिंदी / Hinglish बोली मॉडल", "Hindi / Hinglish speech model"), hiAsr && vad, if (!(hiAsr && vad)) t("नहीं है", "Missing")
            else if (hiBig) t("AI4Bharat IndicConformer — इस फ़ोन पर", "AI4Bharat IndicConformer — on this phone") else t("छोटा मॉडल — बेहतर मॉडल के लिए push_models.sh --asr-hi-big", "Small model — run push_models.sh --asr-hi-big for the better one")))
        Ui.addRow(ready, Ui.status(this, t("English बोली मॉडल", "English speech model"), enAsr && vad, if (!(enAsr && vad)) t("नहीं है", "Missing")
            else if (enBig) t("NVIDIA Parakeet 110M — इस फ़ोन पर", "NVIDIA Parakeet 110M — on this phone") else t("छोटा मॉडल — बेहतर मॉडल के लिए push_models.sh --asr-en-big", "Small model — run push_models.sh --asr-en-big for the better one")))
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
    }

    private fun requestPermissionsIfNeeded() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
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
        getSharedPreferences("guardian", Context.MODE_PRIVATE).edit().putString("call_language", lang).apply()
        level = "none"
        explainText = ""; explainBy = ""; whyText = ""; snr = null; finals = emptyList(); partial = ""
        if (tab != 0 || langChanged) { tab = 0; build() } else { renderTranscript(); renderExplain() }
        val i = Intent(this, GuardianService::class.java).setAction(GuardianService.ACTION_START)
            .putExtra(GuardianService.EXTRA_LANG, lang).putExtra(GuardianService.EXTRA_DEMO, demo)
        startForegroundService(i)
    }

    /** Standby: the service starts now (while this screen is visible, as Android requires for the
     *  microphone) and begins listening by itself when the protected phone reports an unknown call. */
    private fun startStandby() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissionsIfNeeded(); renderProtect(); return }
        kavach.shared.AppState.startCallProtection(this, lang)
        handler.postDelayed({ renderProtect(); refreshReadiness() }, 400)
        if (!kavach.shared.AppState.callAccessEnabled(this)) showAccessHelp()
    }

    private fun stopStandby() {
        kavach.shared.AppState.stopCallProtection(this)
        handler.postDelayed({ renderAll() }, 400)
    }

    private fun stopGuard() {
        if (!GuardianBus.running) { toast(t("अभी सुन नहीं रहा है।", "Not listening right now.")); return }
        val keep = GuardianService.protecting
        startService(Intent(this, GuardianService::class.java).setAction(if (keep) GuardianService.ACTION_END_SESSION else GuardianService.ACTION_STOP))
        toast(if (keep) t("इस कॉल पर सुनना बंद किया। सुरक्षा चालू है।", "Stopped listening to this call. Protection stays on.") else t("सुनना बंद किया।", "Stopped listening."))
        renderMode(if (keep) "standby" else "idle")
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

    /** Proof that Llama runs on this phone: load it, explain a short scripted scam transcript, show the
     *  answer and the measured speed. Clearly labelled as a sample, separate from real calls. */
    private fun testLlama() {
        if (GuardianBus.running) { toast(t("पहले 'मेरी कॉल सुरक्षित रखें' बंद करें (Llama अभी उपयोग में है)।", "Turn off 'Protect my calls' first (Llama is in use by it).")); return }
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
    override fun onMode(mode: String) { renderMode(mode); renderProtect() }

    override fun onTranscript(finals: List<String>, partial: String) { this.finals = finals; this.partial = partial; renderTranscript() }

    override fun onState(s: GuardState) {
        level = s.level
        whyText = if (s.rule.isNotBlank() && s.level != "none") kavach.core.KillChain.ruleLabel(s.rule, uiLang) else ""
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
