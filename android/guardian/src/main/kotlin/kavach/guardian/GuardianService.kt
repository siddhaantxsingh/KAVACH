package kavach.guardian

import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kavach.core.Alert
import kavach.core.GuardState
import kavach.core.Guardian
import kavach.core.GuardianConfig
import kavach.core.MiniJson
import kavach.core.Verdict
import kavach.shared.DeviceLink
import kavach.shared.KavachLink
import kavach.shared.KavachRuntime
import kavach.shared.LocalLink
import kavach.shared.LlamaAdvisor
import kavach.shared.PerfStats
import kavach.shared.Speaker
import kavach.shared.Ui
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** UI <- service events, always delivered on the main thread. */
object GuardianBus {
    interface Listener {
        fun onTranscript(finals: List<String>, partial: String) {}
        fun onState(s: GuardState) {}
        fun onAlert(a: Alert) {}
        fun onLlm(v: Verdict, stats: LlamaAdvisor.Stats?) {}
        fun onStatus(msg: String) {}
        fun onPlacement(snrDb: Double) {}
        /** mode: idle | standby | listening | demo | models_missing */
        fun onMode(mode: String) {}
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    @Volatile var running = false
    @Volatile var lastStatus = "idle"
    @Volatile var mode = "idle"
    /** Llama: missing | loading | ready | failed (only "ready" + a finished generation means it actually ran) */
    @Volatile var llamaState = "missing"
    @Volatile var llamaLastRun: String = ""
    /** "" | "copied_example" | "wrong_language": why Llama's own wording was not shown */
    @Volatile var llamaSayNote: String = ""
    /** language the caller is speaking now (Auto), "hi" | "en" */
    @Volatile var callLang: String = "hi"
    fun mode(m: String) { mode = m; kavach.shared.AppState.callMode = m; post { it.onMode(m) } }
    fun add(l: Listener) = main.post { listeners.add(l) }
    fun remove(l: Listener) = main.post { listeners.remove(l) }
    fun post(block: (Listener) -> Unit) { main.post { listeners.toList().forEach(block) } }
    fun status(msg: String) { lastStatus = msg; post { it.onStatus(msg) } }
}

class GuardianService : Service() {
    private lateinit var engine: ScheduledExecutorService
    private val llmQueue = Executors.newSingleThreadExecutor { r -> Thread(r, "kavach-llmq") }
    private var guardian: Guardian? = null
    private var asr: AsrEngine? = null
    private var advisor: LlamaAdvisor? = null
    private var speaker: Speaker? = null
    private var link: KavachLink? = null
    private var ignoredOwnVoice = 0
    private var demo: DemoPlayer? = null
    private var log: FileWriter? = null
    private val finals = ArrayList<String>()
    /** Language actually used for warnings, Llama and the detector ("hi" | "en"). With the "auto"
     *  setting it follows the language of the caller's recent sentences. */
    private var lang = "hi"
    /** What the user chose: "auto" | "hi" | "en" */
    private var setting = "auto"
    private var langPick: kavach.core.LangPick? = null
    /** languages of the last few final sentences (Auto) */
    private val recentLangs = ArrayDeque<String>()
    private var startedAt = 0L

    private var standby = false
    private var sessionActive = false
    private var sessionStart = 0L
    private var autoStopAt = 0L
    private var models: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            // "Stop listening" while call protection is on: end this call's listening, keep protection
            ACTION_END_SESSION -> { if (standby) { if (sessionActive) engine.execute { endSession("stopped by the user") } } else stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                setup(intent.getStringExtra(EXTRA_LANG) ?: "auto", intent.getStringExtra(EXTRA_LINK_ADDR), standbyMode = false)
                beginSession(intent.getStringExtra(EXTRA_DEMO), reason = "manual")
            }
            ACTION_STANDBY -> setup(intent.getStringExtra(EXTRA_LANG) ?: "auto", intent.getStringExtra(EXTRA_LINK_ADDR), standbyMode = true)
        }
        return START_NOT_STICKY
    }

    /**
     * Service-level setup, done once: foreground notification (microphone type, started while the
     * activity is visible, so recording can begin later from the background when a call starts),
     * engine thread, TTS, Llama, link, detection core.
     */
    private fun setup(language: String, linkAddr: String?, standbyMode: Boolean) {
        setting = language
        lang = if (language == "auto") "hi" else language
        standby = standby || standbyMode
        protecting = standby
        if (GuardianBus.running) {
            if (standbyMode && !sessionActive) GuardianBus.mode("standby")
            return
        }
        GuardianBus.running = true
        startedAt = System.currentTimeMillis()
        Ui.ensureChannel(this, CHANNEL, "KAVACH Guardian", android.app.NotificationManager.IMPORTANCE_LOW)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, GuardianActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Ui.ongoing(this, CHANNEL, if (standbyMode) "KAVACH call protection is on" else "KAVACH is guarding this call",
            if (standbyMode) "When a call from an unknown number starts, put it on speaker: KAVACH listens on this phone."
            else "Audio is processed on this phone only and never stored.", android.R.drawable.ic_lock_idle_lock, pi)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, n)

        engine = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kavach-engine") }
        speaker = Speaker(this)
        models = ModelFiles.dir(this)
        advisor = LlamaAdvisor(ModelFiles.llamaPte(this).path, ModelFiles.llamaTokenizer(this).path).also { adv ->
            GuardianBus.llamaState = adv.state.name.lowercase()
            GuardianBus.status(if (adv.state == LlamaAdvisor.State.MISSING) "Llama model not installed: rule + classifier mode" else "loading Llama…")
            adv.loadAsync { st ->
                GuardianBus.llamaState = st.name.lowercase()
                GuardianBus.status("Llama: $st")
                kavach.shared.AppState.llama = if (st == LlamaAdvisor.State.READY) adv else null
                // a warning fired while Llama was still loading: explain it now
                if (st == LlamaAdvisor.State.READY) pendingAlert?.let { pa -> pendingAlert = null; if (sessionActive) engine.execute { askLlamaFor(pa) } }
                if (st == LlamaAdvisor.State.READY) guardian?.let { adv.warm(it.buildLlmPrompt("", emptyMap(), emptyList()).first) }
            }
        }
        openLog()
        connectLink(linkAddr ?: LocalLink.SAME_PHONE)
        KavachRuntime.ensureLoaded(this) { core ->
            val g = core.newGuardian(GuardianConfig(lang = lang))
            g.onAlert = { a -> onAlert(a) }
            guardian = g
            advisor?.let { adv -> if (adv.state == LlamaAdvisor.State.READY) adv.warm(g.buildLlmPrompt("", emptyMap(), emptyList()).first) }
            engine.scheduleWithFixedDelay({ tickNow(); checkAutoStop() }, 3, 3, TimeUnit.SECONDS)
            engine.scheduleWithFixedDelay({ logPerf() }, 5, 5, TimeUnit.SECONDS)
            if (standbyMode && !sessionActive) { GuardianBus.status("standby: waiting for a call on the protected phone"); GuardianBus.mode("standby") }
        }
    }

    /** One guarded call. Resets the engine so every call is judged on its own. */
    private fun beginSession(demoScript: String?, reason: String) {
        KavachRuntime.ensureLoaded(this) { _ ->
            engine.execute {
                if (sessionActive) return@execute
                val g = guardian ?: return@execute
                g.reset()
                if (setting == "auto") { lang = "hi"; recentLangs.clear(); langPick?.reset() }
                g.cfg.lang = lang
                kavach.shared.AppState.callLevel = "none"
                synchronized(finals) { finals.clear() }
                sessionStart = System.currentTimeMillis()
                sessionActive = true
                autoStopAt = 0L
                // the protected phone's call is part of this session's kill-chain
                if (reason == "call_started") g.addExternal(kavach.core.KcEvent(0.0, "phone", kavach.core.KillChain.CALL_START, 0.0, "auto-start"))
                logLine("session", reason)
                if (demoScript != null) {
                    demo = DemoPlayer(this, demoScript, onPartial = { p -> publishTranscript(p) }, onFinal = { text, _ -> onFinalSegment(text) }).also { it.start() }
                    GuardianBus.status("demo replay: $demoScript"); GuardianBus.mode("demo")
                } else {
                    val dir = models ?: ModelFiles.dir(this)
                    // Auto needs both whole-sentence models; without them it listens as Hindi/Hinglish
                    // (that model also writes English loanwords, which the detector knows).
                    val engineLang = if (setting == "auto") (if (AsrEngine.autoReady(dir)) "auto" else "hi") else setting
                    if (engineLang == "auto" && langPick == null) langPick = try {
                        kavach.core.LangPick.fromText(assets.open("langid/en_words.txt").bufferedReader().readText(),
                            assets.open("langid/hi_words.txt").bufferedReader().readText())
                    } catch (t: Throwable) { Log.e(TAG, "language lists", t); null }
                    val a = AsrEngine(dir, if (engineLang == "auto" && langPick == null) "hi" else engineLang,
                        langPick = langPick, onLang = { l -> onSentenceLang(l) }, onPartial = { p -> publishTranscript(p) }, onFinal = { text, _ -> onFinalSegment(text) },
                        onPlacement = { snr -> GuardianBus.post { it.onPlacement(snr) } }, callAudio = oemMode(this),
                        isMuted = { speaker?.isSpeakingOrJustSpoke(700) == true })
                    val missing = a.missingFiles()
                    if (missing.isNotEmpty()) {
                        GuardianBus.status("ASR model missing: ${missing.first()} — run scripts/push_models.sh")
                        sessionActive = false; GuardianBus.mode("models_missing")
                    } else {
                        asr = a; a.start(); GuardianBus.mode("listening")
                        GuardianBus.status((if (reason == "call_started") "call detected — " else "") + "listening (${when { setting == "auto" && AsrEngine.autoReady(dir) -> "Auto: Hindi + English"; setting == "auto" -> "Auto: Hindi/Hinglish model only"; lang == "hi" -> "Hindi/Hinglish"; else -> "English" }})" +
                            if (oemMode(this)) " · system mode: reading the call audio directly" else "")
                    }
                }
            }
        }
    }

    private fun endSession(reason: String) {
        if (!sessionActive) return
        asr?.stop(); asr = null
        demo?.stop(); demo = null
        sessionActive = false
        logLine("session_end", reason)
        GuardianBus.status(if (standby) "call ended — back on standby" else "stopped listening")
        GuardianBus.mode(if (standby) "standby" else "idle")
        if (!standby) stopSelf()
    }

    private fun checkAutoStop() {
        if (autoStopAt > 0 && System.currentTimeMillis() >= autoStopAt) { autoStopAt = 0L; endSession("call_ended") }
    }

    private fun callTime(): Double = asr?.now() ?: demo?.now() ?: ((System.currentTimeMillis() - (if (sessionStart > 0) sessionStart else startedAt)) / 1000.0)

    private fun onFinalSegment(text: String) {
        val t = callTime()
        // The microphone also hears KAVACH's own spoken warning; never treat it as the caller.
        if (speaker?.isSpeakingOrJustSpoke() == true || isEchoOfOurWarning(text)) {
            ignoredOwnVoice++
            GuardianBus.status("ignored our own spoken warning ($ignoredOwnVoice)")
            return
        }
        engine.execute {
            val g = guardian ?: return@execute
            g.onSegment(text, t)
            synchronized(finals) { finals.add(text); while (finals.size > 40) finals.removeAt(0) }
            publishTranscript("")
            tickNow()
        }
    }

    /** Auto: the caller's language follows the majority of the last 3 sentences (latest wins a tie),
     *  so one English word-heavy sentence in a Hindi call does not flip warnings and Llama to English. */
    private fun onSentenceLang(l: String) {
        engine.execute {
            recentLangs.addLast(l); while (recentLangs.size > 3) recentLangs.removeFirst()
            val en = recentLangs.count { it == "en" }; val hi = recentLangs.size - en
            val now = if (en > hi) "en" else if (hi > en) "hi" else l
            if (now != lang) {
                lang = now; guardian?.cfg?.lang = now
                GuardianBus.status("caller is speaking " + if (now == "hi") "Hindi/Hinglish" else "English")
            }
            GuardianBus.callLang = lang
        }
    }

    /** Backup for the microphone mute: a sentence that mostly repeats what KAVACH said in the last
     *  20 s is our own warning coming back through the microphone, not the caller. */
    private fun isEchoOfOurWarning(text: String): Boolean {
        val sp = speaker ?: return false
        if (android.os.SystemClock.elapsedRealtime() - sp.lastSpokenAt > 20_000L) return false
        val said = kavach.core.Normalize.tokens(sp.lastSpokenText).toSet()
        val heard = kavach.core.Normalize.tokens(text)
        if (said.isEmpty() || heard.size < 3) return false
        val overlap = heard.count { it in said }.toDouble() / heard.size
        return overlap >= 0.6
    }

    private fun publishTranscript(partial: String) {
        val snap = synchronized(finals) { finals.toList() }
        GuardianBus.post { it.onTranscript(snap, partial) }
    }

    private fun tickNow() {
        if (!sessionActive) return
        val g = guardian ?: return
        try {
            val st = g.tick(callTime())
            GuardianBus.post { it.onState(st) }
        } catch (t: Throwable) { Log.e(TAG, "tick failed", t) }
    }

    /** Runs on the engine thread (called from Guardian.tick via onAlert). */
    private fun onAlert(a: Alert) {
        val call = inPhoneCall()
        // During a real phone call the warning is NOT spoken (it would go into the call and mute our
        // own listening); the phone vibrates and the screen shows it instead. A yellow warning is
        // never spoken either: the microphone is muted while KAVACH speaks, and the sentences right
        // after the first suspicion are the ones that decide whether the call is a scam. Only the
        // red warning is spoken aloud (outside a call), after the decision has been made.
        kavach.shared.AppState.callLevel = a.level
        if (call || a.level != "stop") vibrate(a.level) else speaker?.speak(spoken(a.level, a.verdict.say), lang)
        GuardianBus.post { it.onAlert(a) }
        link?.send(linkedMapOf("type" to "level", "level" to a.level, "rule" to a.rule, "scam_type" to a.verdict.type, "say" to a.verdict.say,
            "show_stop" to (a.level == "stop" && (call || !GuardianActivity.visible))))
        logLine("alert", a.level, a.rule, a.verdict.type)
        val adv = advisor ?: return
        if (adv.state == LlamaAdvisor.State.LOADING) { pendingAlert = a; return }
        askLlamaFor(a)
    }

    private var pendingAlert: Alert? = null

    /** A cellular or internet (WhatsApp etc.) call is in progress on this phone. */
    private fun inPhoneCall(): Boolean {
        val m = (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).mode
        return m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION
    }

    private fun vibrate(level: String) {
        val pattern = if (level == "stop") longArrayOf(0, 600, 250, 600, 250, 900) else longArrayOf(0, 350, 200, 350)
        try {
            val v: android.os.Vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java)!!.defaultVibrator
            else getSystemService(android.os.Vibrator::class.java)!!
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } catch (t: Throwable) { Log.w(TAG, "vibrate", t) }
    }

    /** The 1B model sometimes copies the example answer from its instructions word for word, or
     *  answers in English during a Hindi call. Its verdict (scam type, evidence) is kept, but such a
     *  'say' is replaced by KAVACH's playbook explanation for this call, and the screen says so. */
    private fun repairSay(v: Verdict, a: Alert): Verdict {
        val ex = kavach.core.Normalize.tokens(EXAMPLE_SAY).toSet()
        val got = kavach.core.Normalize.tokens(v.say)
        val copied = got.isNotEmpty() && got.count { it in ex }.toDouble() / got.size >= 0.8
        val wrongLang = lang == "hi" && v.say.none { it in '\u0900'..'\u097F' }
        if (!copied && !wrongLang) { GuardianBus.llamaSayNote = ""; return v }
        GuardianBus.llamaSayNote = if (copied) "copied_example" else "wrong_language"
        GuardianBus.status("Llama: its wording was replaced by the playbook (" + GuardianBus.llamaSayNote + ")")
        // A copied answer carries the example's own "scam, 0.9, payment" too, so it must not be able
        // to raise a yellow warning to red: its confidence is set to 0.
        return v.copy(say = a.verdict.say, confidence = if (copied) 0.0 else v.confidence)
    }

    /** What is said ALOUD. The microphone is muted while KAVACH speaks (so it never transcribes
     *  itself), so a long spoken warning would make it miss the caller's next sentences, which are
     *  often the decisive ones ("tell me the OTP"). Caution: one short line (~5 s). Stop: the headline
     *  and the action (~10 s). The full explanation is always on screen. */
    private fun spoken(level: String, full: String): String {
        val hi = lang == "hi"
        if (level != "stop") return if (hi) "सावधान। यह कॉल संदिग्ध लग रही है। कोई ओटीपी मत बताइए, पैसे मत भेजिए।"
            else "Careful. This call looks suspicious. Do not share any OTP or send money."
        val head = Regex("""^.{8,160}?[.!?।]""").find(full.trim())?.value ?: (if (hi) "रुकिए! यह कॉल स्कैम लग रही है।" else "Stop! This call looks like a scam.")
        return head + if (hi) " पैसे मत भेजिए, ओटीपी मत बताइए। कॉल काटिए और 1930 पर कॉल कीजिए।" else " Do not send money or share any OTP. Hang up and call 1930."
    }

    /** Ask Llama to explain an alert (asynchronous: the warning itself never waits for this). Every
     *  outcome is reported on screen: ran (with measured speed), too slow, rejected, or error. */
    private fun askLlamaFor(a: Alert) {
        val adv = advisor ?: return
        if (adv.state != LlamaAdvisor.State.READY) return
        val g = guardian ?: return
        val active = g.kc.assess(callTime()).active
        GuardianBus.status("Llama is explaining…")
        llmQueue.execute {
            val raw = g.askLlm(adv, a.window, active, a.retrieved)
            GuardianBus.llamaLastRun = describeLlamaRun(adv, raw != null)
            val v = raw?.let { repairSay(it, a) }
            GuardianBus.status("Llama: " + GuardianBus.llamaLastRun)
            if (v == null) return@execute
            engine.execute {
                val before = g.level
                g.applyLlmVerdict(callTime(), v, a.retrieved, a.window)
                if (before != "stop" && g.level == "stop") {
                    val say = v.say.ifBlank { a.verdict.say }
                    kavach.shared.AppState.callLevel = "stop"
                    if (inPhoneCall()) vibrate("stop") else speaker?.speak(spoken("stop", say), lang)
                    link?.send(linkedMapOf("type" to "level", "level" to "stop", "rule" to "llm-early-warning", "scam_type" to v.type, "say" to say,
                        "show_stop" to (inPhoneCall() || !GuardianActivity.visible)))
                }
                GuardianBus.post { it.onLlm(v, adv.lastStats) }
                adv.lastStats?.let { s -> logLine("llm", s.promptTokens, s.generatedTokens, s.ttftMs, "%.1f".format(s.prefillTokPerS), "%.1f".format(s.decodeTokPerS), s.usedWarmPrefix) }
            }
        }
    }

    private fun connectLink(address: String) {
        val onMsg: (Map<String, Any?>) -> Unit = { m ->
            when (m["type"]) {
                // the protected phone got / ended a call from an unknown number -> auto-start / auto-stop
                "call_started" -> if (standby && autoStartEnabled() && m["unknown"] != false) {
                    autoStopAt = 0L
                    beginSession(null, reason = "call_started")
                }
                "call_ended" -> if (sessionActive) autoStopAt = System.currentTimeMillis() + AUTO_STOP_DELAY_MS
                // events from the user's phone (dangerous link, risky APK, screen share, payment app) join this call's kill-chain
                else -> DeviceLink.eventFrom(m, callTime())?.let { ev -> engine.execute { if (sessionActive) { guardian?.addExternal(ev); tickNow() } } }
            }
        }
        if (address == LocalLink.SAME_PHONE) {
            // one app: the protection part (messages, links, apps, calls) is in this same app
            link = kavach.shared.InProcessLink(kavach.shared.InProcessBus.GUARDIAN, onMsg) { s -> GuardianBus.status("Shield link: $s") }
            return
        }
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val dev = try { bm.adapter?.getRemoteDevice(address) } catch (t: Throwable) { null } ?: return
        link = DeviceLink(this, onMessage = onMsg, onState = { s -> GuardianBus.status("phone link: $s") }).also { it.connect(dev) }
    }

    // ---- per-session benchmark log (bench/parse_session.py turns these into plots)
    private fun openLog() {
        try {
            val dir = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
            log = FileWriter(File(dir, "guardian_${startedAt}.csv")).apply { write("wall_ms,call_s,kind,a,b,c,d,e,f\n") }
        } catch (t: Throwable) { Log.w(TAG, "no bench log", t) }
    }

    private fun logLine(kind: String, vararg v: Any?) {
        val w = log ?: return
        synchronized(w) { w.write("${System.currentTimeMillis()},${"%.2f".format(callTime())},$kind,${v.joinToString(",") { it.toString().replace(',', ';') }}\n"); w.flush() }
    }

    private fun logPerf() {
        try {
            val s = PerfStats.snapshot(this)
            logLine("perf", s.batteryPct, s.batteryTempC, s.currentNowMA, s.pssMB, s.coreFreqMHz.joinToString("|"),
                MiniJson.write(s.threadCore.mapValues { it.value.toDouble() }).replace(',', ';') + "|thermal=${s.thermalStatus}|rtf=${asr?.rtf}")
        } catch (t: Throwable) { Log.w(TAG, "perf log failed", t) }
    }

    private fun autoStartEnabled(): Boolean = getSharedPreferences("guardian", Context.MODE_PRIVATE).getBoolean(PREF_AUTOSTART, true)

    override fun onDestroy() {
        GuardianBus.running = false
        sessionActive = false; standby = false; protecting = false
        kavach.shared.AppState.callLevel = "none"
        asr?.stop(); demo?.stop(); link?.stop()
        if (::engine.isInitialized) engine.shutdownNow()
        llmQueue.shutdownNow()
        kavach.shared.AppState.llama = null
        advisor?.close(); speaker?.shutdown()
        try { log?.close() } catch (_: Throwable) { }
        GuardianBus.status("stopped"); GuardianBus.mode("idle")
        super.onDestroy()
    }

    companion object {
        /** The worked example in Llama's instructions (LlmContract.systemPrompt). */
        const val EXAMPLE_SAY = "This is a scam. Real police never ask for money on a call. Do not send anything. Hang up and call 1930."
        /** Plain-language record of what the last Llama call actually did (shown in Readiness). */
        fun describeLlamaRun(adv: LlamaAdvisor, valid: Boolean): String {
            val st = adv.lastStats
            val speed = st?.let { "${it.generatedTokens} tokens, first token ${it.ttftMs} ms, ${"%.1f".format(it.decodeTokPerS)} tokens/s" } ?: ""
            return when {
                adv.lastOutcome == "timeout" -> "too slow: no answer within ${adv.lastElapsedMs / 1000} s"
                adv.lastOutcome.startsWith("error") -> "error while generating (${adv.lastOutcome.removePrefix("error: ")})"
                valid -> "ran on this phone: $speed"
                else -> "ran on this phone ($speed) but its answer failed the safety check"
            }
        }

        /** OEM/system mode: true only when installed as a privileged system app that was granted
         *  CAPTURE_AUDIO_OUTPUT (docs/oem_mode.md). Normal installs never get this permission. */
        fun oemMode(ctx: android.content.Context): Boolean =
            ctx.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == android.content.pm.PackageManager.PERMISSION_GRANTED

        private const val TAG = "GuardianService"
        const val CHANNEL = "guardian"
        const val NOTIF_ID = 7
        const val ACTION_START = "kavach.guardian.START"
        const val ACTION_STOP = "kavach.guardian.STOP"
        const val ACTION_STANDBY = "kavach.guardian.STANDBY"
        const val ACTION_END_SESSION = "kavach.guardian.END_SESSION"
        /** true while call protection (standby) is on */
        @Volatile var protecting = false
        const val AUTO_STOP_DELAY_MS = 30_000L
        const val PREF_AUTOSTART = "autostart"
        const val EXTRA_LANG = "lang"
        const val EXTRA_DEMO = "demo_script"
        const val EXTRA_LINK_ADDR = "link_addr"
    }
}
