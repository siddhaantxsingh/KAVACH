package kavach.core

import kotlin.math.exp

/**
 * Port of kavach_core/guardian.py — the Call Guardian streaming engine.
 *
 * Thread model on Android: onSegment() is called from the ASR thread, tick()
 * from the same single-threaded executor (GuardianService serialises both),
 * askLlm() runs on the LLM thread and reports back through [LlmBridge].
 */
class GuardianConfig(
    val pCaution: Double = 0.80,
    val pStop: Double = 0.80,
    val pVeto: Double = 0.50,
    val vetoRules: Set<String> = setOf("hook+action", "multi-action", "score"),
    val llmEscalateConf: Double = 0.80,
    var lang: String = "hi",
    val killchain: KillChainConfig = KillChainConfig(),
)

data class GuardState(
    val t: Double, val level: String, val pScam: Double, val score: Double, val active: Map<String, Double>,
    val stages: List<String>, val rule: String,
)

data class Alert(val t: Double, val level: String, val rule: String, val verdict: Verdict, val retrieved: List<PlaybookEntry>, val window: String)

/** (prefix, suffix) -> raw model text. Implemented by the ExecuTorch Llama runner on device. */
fun interface LlmFn { fun generate(prefix: String, suffix: String): String }

class Guardian(
    val lexicon: Lexicon,
    private val model: LinearModel?,
    val playbook: Playbook,
    val cfg: GuardianConfig = GuardianConfig(),
    var llmFn: LlmFn? = null,
) {
    val typeIds = playbook.typeIds
    val system = LlmContract.systemPrompt(typeIds)
    val labels: Map<String, Pair<String, String>> = lexicon.categories.mapValues { it.value.labelEn to it.value.labelHi }
    val kc = KillChain(lexicon.stageOf, cfg.killchain)

    private val toks = ArrayList<String>()
    private val tokTime = ArrayList<Double>()
    private var scanned = 0
    private val seenHits = HashSet<Pair<String, Int>>()
    private val seenSegments = HashSet<Pair<String, Double>>()
    private val pHist = ArrayList<Double>()
    var level = "none"; private set
    var rule = ""; private set
    val alerts = ArrayList<Alert>()
    val history = ArrayList<GuardState>()
    /** Called on every escalation with the instant (fallback) alert; Android speaks it. */
    var onAlert: ((Alert) -> Unit)? = null

    fun reset() {
        toks.clear(); tokTime.clear(); scanned = 0; seenHits.clear(); seenSegments.clear(); pHist.clear()
        level = "none"; rule = ""; alerts.clear(); history.clear(); kc.reset()
    }

    fun onSegment(text: String, t: Double) {
        val tk = Normalize.tokens(text)
        if (tk.isEmpty()) return
        toks.addAll(tk)
        repeat(tk.size) { tokTime.add(t) }
    }

    fun addExternal(ev: KcEvent) = kc.add(ev)

    fun transcriptWindow(): String = toks.takeLast(WINDOW_TOKENS).joinToString(" ")

    fun tick(now: Double): GuardState {
        val start = maxOf(0, scanned - RESCAN_OVERLAP)
        val sub = toks.subList(start, toks.size)
        for (h in lexicon.matchTokens(sub)) {
            val key = h.category to (start + h.start)
            if (key in seenHits) continue
            seenHits.add(key)
            val tHit = tokTime[start + h.end - 1]
            val segKey = h.category to tHit
            if (segKey in seenSegments) continue
            seenSegments.add(segKey)
            kc.add(KcEvent(tHit, "call", h.category, h.weight, h.phrase))
        }
        scanned = toks.size

        var p = 0.0
        if (model != null && toks.isNotEmpty()) p = sigmoid(model.logitTokens(toks.takeLast(WINDOW_TOKENS)))
        pHist.add(p)
        val a = kc.assess(now)
        var lv = a.level
        var rl = a.rule
        if (model != null && lv == "stop" && rl in cfg.vetoRules && level != "stop" && pHist.takeLast(3).max() < cfg.pVeto) {
            lv = "caution"; rl = "$rl(vetoed)"
        }
        val last2 = pHist.takeLast(2)
        val actionCue = a.active.keys.any { it in KillChain.ACTION_PAY }
        if (last2.size == 2) {
            if (last2.min() >= cfg.pStop && (rank(a.level) >= rank("caution") || actionCue)) {
                if (rank("stop") > rank(lv)) { lv = "stop"; rl = "classifier" }
            } else if (last2.min() >= cfg.pCaution && rank(lv) < rank("caution")) {
                lv = "caution"; rl = "classifier"
            }
        }
        if (rank(lv) > rank(level)) escalate(now, lv, rl, a)
        val st = GuardState(now, level, Math.round(p * 10000) / 10000.0, a.score, a.active, a.stages, rule)
        history.add(st)
        return st
    }

    private fun escalate(now: Double, lv: String, rl: String, a: Assessment) {
        level = lv; rule = rl
        if (lv != "caution" && lv != "stop") return
        val window = transcriptWindow()
        val retrieved = playbook.rank(a.active, window).map { it.first }
        val verdict = LlmContract.fallbackVerdict(lv, a.active, retrieved, labels, a.stages, cfg.lang)
        val alert = Alert(now, lv, rl, verdict, retrieved, window)
        alerts.add(alert)
        onAlert?.invoke(alert)
        val fn = llmFn ?: return
        val v = askLlm(fn, window, a.active, retrieved) ?: return
        applyLlmVerdict(now, v, retrieved, window)
    }

    /** Llama may raise CAUTION to STOP early; it can never lower a STOP (fail-safe). */
    fun applyLlmVerdict(now: Double, v: Verdict, retrieved: List<PlaybookEntry>, window: String) {
        alerts.add(Alert(now, level, "llm", v, retrieved, window))
        if (level == "caution" && v.scam && v.confidence >= cfg.llmEscalateConf && v.stage in setOf("isolation", "action", "payment")) {
            level = "stop"; rule = "llm-early-warning"
            kc.peak = "stop"; kc.peakRule = "llm-early-warning"
        }
    }

    fun buildLlmPrompt(window: String, active: Map<String, Double>, retrieved: List<PlaybookEntry>): Pair<String, String> =
        LlmContract.formatLlama3(system, LlmContract.userPrompt(window, active, retrieved, cfg.lang))

    fun askLlm(fn: LlmFn, window: String, active: Map<String, Double>, retrieved: List<PlaybookEntry>): Verdict? {
        val (prefix, suffix) = buildLlmPrompt(window, active, retrieved)
        var raw = fn.generate(prefix, suffix)
        var (v, errs) = LlmContract.validate(raw, window, typeIds)
        if (v == null) {
            raw = fn.generate(prefix, LlmContract.repairSuffix(suffix, raw, errs))
            v = LlmContract.validate(raw, window, typeIds).first
        }
        return v
    }

    /** Replay (t_end, text) segments in call time, exactly like the Python evaluation. */
    fun runTranscript(segments: List<Pair<Double, String>>, tickS: Double = TICK_S, tailS: Double = 6.0, eventDriven: Boolean = true): List<GuardState> {
        reset()
        if (segments.isEmpty()) return emptyList()
        val end = segments.last().first + tailS
        val times = java.util.TreeSet<Double>()
        for (k in 1..(end / tickS).toInt()) times.add(round3(k * tickS))
        if (eventDriven) for ((t, _) in segments) times.add(round3(t))
        var i = 0
        for (t in times) {
            while (i < segments.size && segments[i].first <= t + 1e-9) { onSegment(segments[i].second, segments[i].first); i++ }
            tick(t)
        }
        return history
    }

    companion object {
        const val TICK_S = 3.0
        const val WINDOW_TOKENS = 120
        const val RESCAN_OVERLAP = 12
        fun rank(l: String) = KillChain.LEVELS.indexOf(l)
        private fun round3(x: Double) = Math.round(x * 1000) / 1000.0
        private fun sigmoid(z: Double) = if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z) / (1.0 + exp(z))
    }
}
