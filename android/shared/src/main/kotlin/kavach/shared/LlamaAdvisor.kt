package kavach.shared

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kavach.core.LlmContract
import kavach.core.LlmFn
import kavach.core.MiniJson
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Llama 3.2 1B (4-bit, ExecuTorch + XNNPACK/KleidiAI, Arm CPU) as the scam advisor.
 *
 * Latency tricks
 *  - warm prefix: the constant system prompt is pre-filled into the KV cache when guarding
 *    starts and again in the background after every alert (resetContext + prefillPrompt), so
 *    an alert only pays for the user part of the prompt;
 *  - early stop: generation is stopped as soon as the JSON verdict closes;
 *  - hard timeout: the caller never waits longer than [timeoutMs]; the rule-based fallback
 *    verdict has already been spoken, so a slow or throttled phone only loses the refinement.
 * All calls run on one dedicated high-priority thread ("kavach-llm").
 */
class LlamaAdvisor(private val modelPath: String, private val tokenizerPath: String, private val timeoutMs: Long = 25_000) : LlmFn {
    enum class State { MISSING, LOADING, READY, FAILED }

    data class Stats(
        val promptTokens: Int, val generatedTokens: Int, val ttftMs: Long, val prefillTokPerS: Double, val decodeTokPerS: Double,
        val totalMs: Long, val usedWarmPrefix: Boolean,
    )

    @Volatile var state: State = if (File(modelPath).exists() && File(tokenizerPath).exists()) State.LOADING else State.MISSING
        private set
    @Volatile var lastStats: Stats? = null
        private set
    @Volatile var loadMs: Long = 0
        private set
    /** what happened on the last generate(): "ok", "timeout", "error: …" (shown on screen, never hidden) */
    @Volatile var lastOutcome: String = ""
        private set
    @Volatile var lastElapsedMs: Long = 0
        private set
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY); r.run() }, "kavach-llm")
    }
    private var module: LlmModule? = null
    private var warmPrefix: String? = null
    private var pendingWarm: Future<*>? = null

    fun loadAsync(onDone: (State) -> Unit = {}) {
        if (state == State.MISSING) { onDone(state); return }
        exec.execute {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val m = LlmModule(modelPath, tokenizerPath, 0.0f) // temperature 0 = greedy, deterministic verdicts
                m.load()
                module = m
                loadMs = SystemClock.elapsedRealtime() - t0
                state = State.READY
                Log.i(TAG, "Llama loaded in $loadMs ms")
            } catch (t: Throwable) {
                Log.e(TAG, "Llama load failed", t)
                state = State.FAILED
            }
            onDone(state)
        }
    }

    /** Pre-fill the constant system prefix in the background. */
    fun warm(prefix: String) {
        if (state != State.READY) return
        pendingWarm = exec.submit {
            val m = module ?: return@submit
            try {
                m.resetContext()
                m.prefillPrompt(prefix)
                warmPrefix = prefix
            } catch (t: Throwable) {
                Log.w(TAG, "prefix prefill not supported by this runtime build; using full prompts", t)
                warmPrefix = null
            }
        }
    }

    /** Blocking generate (called from a worker thread, never the main thread). */
    override fun generate(prefix: String, suffix: String): String {
        if (state != State.READY) return ""
        val t0 = SystemClock.elapsedRealtime()
        val task = exec.submit<String> { runGenerate(prefix, suffix) }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS).also { lastOutcome = "ok" }
        } catch (e: TimeoutException) {
            module?.stop()
            lastOutcome = "timeout"
            Log.w(TAG, "Llama timed out after $timeoutMs ms")
            ""
        } catch (t: Throwable) {
            lastOutcome = "error: ${t.javaClass.simpleName}"
            Log.e(TAG, "Llama generate failed", t)
            ""
        } finally {
            lastElapsedMs = SystemClock.elapsedRealtime() - t0
            warm(prefix) // re-arm the cache for the next alert, off the critical path
        }
    }

    private fun runGenerate(prefix: String, suffix: String): String {
        val m = module ?: return ""
        val warmHit = warmPrefix == prefix
        val prompt = if (warmHit) suffix else prefix + suffix
        if (!warmHit) m.resetContext()
        warmPrefix = null // the cache now contains this conversation
        val out = StringBuilder()
        val t0 = SystemClock.elapsedRealtime()
        var firstTokenAt = 0L
        var statsJson: String? = null
        m.generate(prompt, SEQ_LEN, object : LlmCallback {
            override fun onResult(result: String) {
                if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                out.append(result)
                if (result.contains('}') && LlmContract.extractJsonObject(out.toString()) != null) m.stop() // early stop
            }
            override fun onStats(stats: String) { statsJson = stats }
        }, false)
        val total = SystemClock.elapsedRealtime() - t0
        lastStats = parseStats(statsJson, if (firstTokenAt > 0) firstTokenAt - t0 else total, total, warmHit)
        return out.toString()
    }

    private fun parseStats(json: String?, ttft: Long, total: Long, warm: Boolean): Stats {
        var pt = 0; var gt = 0; var pre = 0.0; var dec = 0.0
        try {
            val d = MiniJson.parse(json ?: "{}") as Map<*, *>
            pt = (d["prompt_tokens"] as? Double)?.toInt() ?: 0
            gt = (d["generated_tokens"] as? Double)?.toInt() ?: 0
            val start = d["inference_start_ms"] as? Double
            val promptEnd = d["prompt_eval_end_ms"] as? Double
            val end = d["inference_end_ms"] as? Double
            if (start != null && promptEnd != null && promptEnd > start) pre = pt * 1000.0 / (promptEnd - start)
            if (promptEnd != null && end != null && end > promptEnd && gt > 1) dec = (gt - 1) * 1000.0 / (end - promptEnd)
        } catch (_: Throwable) { }
        return Stats(pt, gt, ttft, pre, dec, total, warm)
    }

    fun close() {
        exec.execute { try { module?.close() } catch (_: Throwable) { } ; module = null }
        exec.shutdown()
    }

    companion object {
        private const val TAG = "LlamaAdvisor"
        /** Max total positions (exported with max_seq_length 2048); generation stops early at JSON close. */
        const val SEQ_LEN = 2048
    }
}
