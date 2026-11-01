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
import kavach.shared.KavachRuntime
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
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    @Volatile var running = false
    @Volatile var lastStatus = "idle"
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
    private var link: DeviceLink? = null
    private var demo: DemoPlayer? = null
    private var log: FileWriter? = null
    private val finals = ArrayList<String>()
    private var lang = "hi"
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> start(intent.getStringExtra(EXTRA_LANG) ?: "hi", intent.getStringExtra(EXTRA_DEMO), intent.getStringExtra(EXTRA_LINK_ADDR))
        }
        return START_NOT_STICKY
    }

    private fun start(language: String, demoScript: String?, linkAddr: String?) {
        if (GuardianBus.running) return
        GuardianBus.running = true
        lang = language
        startedAt = System.currentTimeMillis()
        Ui.ensureChannel(this, CHANNEL, "KAVACH Guardian", android.app.NotificationManager.IMPORTANCE_LOW)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, GuardianActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Ui.ongoing(this, CHANNEL, "KAVACH is guarding this call", "Audio is processed on this phone only and never stored.",
            android.R.drawable.ic_lock_idle_lock, pi)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, n)

        engine = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kavach-engine") }
        speaker = Speaker(this)
        val models = ModelFiles.dir(this)
        advisor = LlamaAdvisor(ModelFiles.llamaPte(this).path, ModelFiles.llamaTokenizer(this).path).also { adv ->
            GuardianBus.status(if (adv.state == LlamaAdvisor.State.MISSING) "Llama model not installed: rule + classifier mode" else "loading Llama…")
            adv.loadAsync { st ->
                GuardianBus.status("Llama: $st")
                if (st == LlamaAdvisor.State.READY) guardian?.let { adv.warm(it.buildLlmPrompt("", emptyMap(), emptyList()).first) }
            }
        }
        openLog()
        linkAddr?.let { connectLink(it) }

        KavachRuntime.ensureLoaded(this) { core ->
            val g = core.newGuardian(GuardianConfig(lang = lang))
            g.onAlert = { a -> onAlert(a) }
            guardian = g
            advisor?.let { adv -> if (adv.state == LlamaAdvisor.State.READY) adv.warm(g.buildLlmPrompt("", emptyMap(), emptyList()).first) }
            engine.scheduleWithFixedDelay({ tickNow() }, 3, 3, TimeUnit.SECONDS)
            engine.scheduleWithFixedDelay({ logPerf() }, 5, 5, TimeUnit.SECONDS)
            if (demoScript != null) {
                demo = DemoPlayer(this, demoScript, onPartial = { p -> publishTranscript(p) }, onFinal = { text, _ -> onFinalSegment(text) }).also { it.start() }
                GuardianBus.status("demo replay: $demoScript")
            } else {
                val a = AsrEngine(models, lang, onPartial = { p -> publishTranscript(p) }, onFinal = { text, _ -> onFinalSegment(text) })
                val missing = a.missingFiles()
                if (missing.isNotEmpty()) {
                    GuardianBus.status("ASR model missing: ${missing.first()} — run scripts/push_models.sh")
                } else {
                    asr = a; a.start(); GuardianBus.status("listening (${if (lang == "hi") "Hindi/Hinglish" else "English"})")
                }
            }
        }
    }

    private fun callTime(): Double = asr?.now() ?: demo?.now() ?: ((System.currentTimeMillis() - startedAt) / 1000.0)

    private fun onFinalSegment(text: String) {
        val t = callTime()
        engine.execute {
            val g = guardian ?: return@execute
            g.onSegment(text, t)
            synchronized(finals) { finals.add(text); while (finals.size > 40) finals.removeAt(0) }
            publishTranscript("")
            tickNow()
        }
    }

    private fun publishTranscript(partial: String) {
        val snap = synchronized(finals) { finals.toList() }
        GuardianBus.post { it.onTranscript(snap, partial) }
    }

    private fun tickNow() {
        val g = guardian ?: return
        try {
            val st = g.tick(callTime())
            GuardianBus.post { it.onState(st) }
        } catch (t: Throwable) { Log.e(TAG, "tick failed", t) }
    }

    /** Runs on the engine thread (called from Guardian.tick via onAlert). */
    private fun onAlert(a: Alert) {
        speaker?.speak(a.verdict.say, lang)
        GuardianBus.post { it.onAlert(a) }
        link?.send(linkedMapOf("type" to "level", "level" to a.level, "rule" to a.rule, "scam_type" to a.verdict.type, "say" to a.verdict.say))
        logLine("alert", a.level, a.rule, a.verdict.type)
        val adv = advisor ?: return
        if (adv.state != LlamaAdvisor.State.READY) return
        val g = guardian ?: return
        val active = g.kc.assess(callTime()).active
        llmQueue.execute {
            val v = g.askLlm(adv, a.window, active, a.retrieved) ?: return@execute
            engine.execute {
                val before = g.level
                g.applyLlmVerdict(callTime(), v, a.retrieved, a.window)
                if (before != "stop" && g.level == "stop") {
                    val say = v.say.ifBlank { a.verdict.say }
                    speaker?.speak(say, lang)
                    link?.send(linkedMapOf("type" to "level", "level" to "stop", "rule" to "llm-early-warning", "scam_type" to v.type, "say" to say))
                }
                GuardianBus.post { it.onLlm(v, adv.lastStats) }
                adv.lastStats?.let { s -> logLine("llm", s.promptTokens, s.generatedTokens, s.ttftMs, "%.1f".format(s.prefillTokPerS), "%.1f".format(s.decodeTokPerS), s.usedWarmPrefix) }
            }
        }
    }

    private fun connectLink(address: String) {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val dev = try { bm.adapter?.getRemoteDevice(address) } catch (t: Throwable) { null } ?: return
        link = DeviceLink(this, onMessage = { m ->
            // events from the user's phone (dangerous link, risky APK, screen share) join this call's kill-chain
            DeviceLink.eventFrom(m, callTime())?.let { ev -> engine.execute { guardian?.addExternal(ev); tickNow() } }
        }, onState = { s -> GuardianBus.status("phone link: $s") }).also { it.connect(dev) }
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

    override fun onDestroy() {
        GuardianBus.running = false
        asr?.stop(); demo?.stop(); link?.stop()
        if (::engine.isInitialized) engine.shutdownNow()
        llmQueue.shutdownNow()
        advisor?.close(); speaker?.shutdown()
        try { log?.close() } catch (_: Throwable) { }
        GuardianBus.status("stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GuardianService"
        const val CHANNEL = "guardian"
        const val NOTIF_ID = 7
        const val ACTION_START = "kavach.guardian.START"
        const val ACTION_STOP = "kavach.guardian.STOP"
        const val EXTRA_LANG = "lang"
        const val EXTRA_DEMO = "demo_script"
        const val EXTRA_LINK_ADDR = "link_addr"
    }
}
