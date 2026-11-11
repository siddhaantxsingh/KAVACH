package kavach.guardian

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kavach.core.GuardianConfig
import kavach.core.MiniJson
import kavach.shared.AppState
import kavach.shared.KavachRuntime
import kavach.shared.LlamaAdvisor
import kavach.shared.PerfStats
import kavach.shared.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Performance on this phone's Arm CPU, measured, not estimated. Every number is written to
 * /sdcard/Android/data/io.kavach.guardian/files/bench/kavach_bench_<time>.json (docs/benchmarks.md).
 *
 *  1. phone: SoC, cores and their max clocks, RAM, Android version
 *  2. model files and their sizes
 *  3. detection core (cues + classifier + kill-chain): ms per transcript segment
 *  4. speech recognition: real-time factor for Hindi and English at 1, 2 and 4 threads
 *  5. Llama 3.2 1B (ExecuTorch, XNNPACK/KleidiAI on the Arm CPU): load time, memory, first token, tokens/s,
 *     cold and with the warm system prefix
 *  6. one minute of live-style listening: CPU use, battery current, temperature
 */
class BenchmarkActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var runBtn: Ui.Btn
    private lateinit var foot: TextView
    private val results = LinkedHashMap<String, Any?>()
    @Volatile private var running = false
    private var savedPath: String? = null

    private val hi get() = kavach.app.ShieldHub.lang(this) == "hi"
    private fun t(h: String, e: String) = if (hi) h else e
    private fun d(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.keepScreenOn(this)
        Ui.theme(this)
        val col = Ui.column(this)
        col.addView(Ui.backHeader(this, t("प्रदर्शन मापें", "Measure performance")))
        col.addView(Ui.text(this, t("इस फ़ोन के Arm CPU पर कवच के हर मॉडल की गति, मेमोरी, बैटरी और तापमान मापता है। लगभग 3–4 मिनट लगते हैं। सही बैटरी आँकड़ों के लिए चार्जर/USB हटा दें।",
            "Measures the speed, memory, battery use and temperature of every KAVACH model on this phone's Arm CPU. Takes about 3–4 minutes. Unplug the charger/USB for true battery numbers."), 14.5f, color = Ui.MUTED))
        runBtn = Ui.primary(this, t("मापना शुरू करें", "Start measuring"), Ui.Ic.zap) { start() }
        col.addView(runBtn)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        foot = Ui.text(this, "", 12.5f, color = Ui.MUTED)
        col.addView(foot)
        setContentView(Ui.scroll(this, col))
    }

    // ------------------------------------------------------------------ UI helpers

    private fun section(title: String): TextView {
        val card = Ui.card(this)
        card.addView(Ui.text(this, title, 16f, weight = 600))
        val body = Ui.text(this, t("चल रहा है…", "Running…"), 14f, color = Ui.MUTED).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        card.addView(body)
        runOnUiThread { list.addView(card) }
        return body
    }

    private fun show(tv: TextView, text: String) = runOnUiThread { tv.text = text }

    private fun start() {
        if (running) return
        running = true
        runBtn.visibility = View.GONE
        list.removeAllViews(); results.clear()
        val wasProtecting = GuardianService.protecting || GuardianBus.running
        Thread {
            // Llama and the speech models must not be loaded twice: pause call protection for the test
            if (wasProtecting) {
                AppState.stopCallProtection(this)
                var waited = 0
                while (GuardianBus.running && waited < 5000) { SystemClock.sleep(100); waited += 100 }
            }
            try { runAll() } catch (t: Throwable) { results["error"] = t.toString() }
            save()
            runOnUiThread {
                if (wasProtecting) AppState.startCallProtection(this, getSharedPreferences("guardian", Context.MODE_PRIVATE).getString("call_lang", "hi") ?: "hi")
                running = false
                runBtn.text = t("फिर से मापें", "Measure again"); runBtn.visibility = View.VISIBLE
                list.addView(Ui.button(this, t("नतीजे शेयर करें", "Share results"), Ui.Ic.history) { share() })
                foot.text = t("फ़ाइल: ", "Saved to: ") + (savedPath ?: "-") +
                    "\nadb pull " + (savedPath ?: "") + "\n" + if (wasProtecting) t("कॉल सुरक्षा फिर चालू कर दी गई।", "Call protection was switched back on.") else ""
            }
        }.apply { name = "kavach-bench" }.start()
    }

    // ------------------------------------------------------------------ the measurements

    private fun runAll() {
        val models = ModelFiles.dir(this)
        val s0 = PerfStats.snapshot(this)

        // 1. phone
        val phone = section(t("1 · फ़ोन", "1 · Phone"))
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val maxes = s0.coreMaxMHz
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        results["phone"] = linkedMapOf("model" to "${Build.MANUFACTURER} ${Build.MODEL}", "soc" to soc, "abi" to Build.SUPPORTED_ABIS.joinToString(","),
            "cores" to PerfStats.cores(), "core_max_mhz" to maxes, "ram_mb" to mem.totalMem / 1048576, "android" to Build.VERSION.RELEASE,
            "charging" to s0.charging)
        show(phone, "${Build.MANUFACTURER} ${Build.MODEL}\nSoC: $soc\nCPU: ${PerfStats.cores()} cores, max MHz ${maxes.joinToString(" ")}\n" +
            "RAM: ${mem.totalMem / 1048576} MB · Android ${Build.VERSION.RELEASE}\n" +
            (if (s0.charging) t("⚠ चार्ज हो रहा है: बैटरी आँकड़े सही नहीं होंगे", "⚠ Charging: battery numbers will not be valid") else t("बैटरी पर चल रहा है", "Running on battery")))

        // 2. model files
        val files = section(t("2 · मॉडल फ़ाइलें", "2 · Model files"))
        val fm = linkedMapOf<String, Any?>()
        val sb = StringBuilder()
        for ((name, rel) in listOf("Hindi speech (int8)" to "asr-hi/model.int8.onnx", "Hindi+ IndicConformer" to AsrEngine.IC_MODEL, "English+ Parakeet 110M" to AsrEngine.EN_BIG_MODEL, "English speech encoder" to "asr-en/encoder.onnx",
                "English speech decoder" to "asr-en/decoder.onnx", "English speech joiner" to "asr-en/joiner.onnx",
                "Voice detector (Silero)" to "silero_vad.onnx", "Llama 3.2 1B (.pte)" to "llama/kavach_llama3_2_1b.pte")) {
            val f = File(models, rel)
            val mb = if (f.isFile) f.length() / 1048576.0 else null
            fm[rel] = mb
            sb.append(String.format(Locale.US, "%-24s %s\n", name, mb?.let { String.format(Locale.US, "%.1f MB", it) } ?: t("नहीं है", "missing")))
        }
        val assetsKb = listOf("cues.json", "playbook.json", "models/cue_model.bin", "models/sms_model.bin", "models/url_model.bin")
            .sumOf { a -> try { assets.openFd(a).length } catch (_: Throwable) { try { assets.open(a).use { it.available().toLong() } } catch (_: Throwable) { 0L } } } / 1024
        fm["detector_assets_kb"] = assetsKb
        sb.append(String.format(Locale.US, "%-24s %d KB", "Detector (rules+classifier)", assetsKb))
        results["model_files_mb"] = fm
        show(files, sb.toString())

        // 3. detection core
        val det = section(t("3 · स्कैम पहचान (नियम + क्लासिफ़ायर)", "3 · Scam detection core (rules + classifier)"))
        val latch = CountDownLatch(1)
        var core: kavach.core.KavachCore? = null
        KavachRuntime.ensureLoaded(this) { c -> core = c; latch.countDown() }
        latch.await(60, TimeUnit.SECONDS)
        core?.let { c ->
            val segs = listOf("hello am i speaking with mister verma", "i am inspector vikram singh from mumbai cyber crime branch",
                "a parcel booked on your aadhaar card contains drugs", "an arrest warrant has been issued in your name",
                "do not tell anyone about this call not even your family", "stay on video call and keep your camera on",
                "to prove your innocence transfer your savings to the rbi safe account", "tell me the otp you just received")
            val g = c.newGuardian(GuardianConfig(lang = "en"))
            repeat(5) { g.reset(); segs.forEachIndexed { i, s -> g.onSegment(s, i * 6.0); g.tick(i * 6.0 + 1) } }
            val n = 40
            val t0 = System.nanoTime()
            repeat(n) { g.reset(); segs.forEachIndexed { i, s -> g.onSegment(s, i * 6.0); g.tick(i * 6.0 + 1) } }
            val perSeg = (System.nanoTime() - t0) / 1e6 / (n * segs.size)
            results["detector_ms_per_segment"] = perSeg
            show(det, String.format(Locale.US, "%.2f ms %s\n%s", perSeg, t("प्रति वाक्य (पहचान + स्कोर)", "per sentence (cues + classifier + kill-chain)"),
                t("आख़िरी स्तर: ", "Final level on the sample scam: ") + g.level))
        } ?: show(det, t("लोड नहीं हुआ", "did not load"))

        // 4. speech recognition speed
        val asr = section(t("4 · आवाज़ से टेक्स्ट (real-time factor, कम = तेज़)", "4 · Speech recognition (real-time factor, lower = faster)"))
        val pcm = assets.open("bench/sample_15s_16k.pcm").use { it.readBytes() }
        val audio = FloatArray(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        val seconds = audio.size / 16000.0
        val asrRes = linkedMapOf<String, Any?>()
        val asrText = StringBuilder()
        for (lang in listOf("hi", "en")) {
            val need = if (lang == "hi") File(models, "asr-hi/model.int8.onnx") else File(models, "asr-en/encoder.onnx")
            if (!need.isFile) { asrText.append("${if (lang == "hi") "Hindi" else "English"}: ${t("मॉडल नहीं है", "model missing")}\n"); continue }
            for (threads in listOf(1, 2, 4)) {
                val pss0 = pssMb()
                val tl = SystemClock.elapsedRealtime()
                val rec = AsrEngine.buildRecognizer(models, lang, threads)
                val loadMs = SystemClock.elapsedRealtime() - tl
                val st = rec.createStream()
                val t0 = SystemClock.elapsedRealtime()
                var i = 0
                while (i < audio.size) {
                    val e = minOf(audio.size, i + 1600)
                    st.acceptWaveform(audio.copyOfRange(i, e), 16000)
                    while (rec.isReady(st)) rec.decode(st)
                    i = e
                }
                st.inputFinished()
                while (rec.isReady(st)) rec.decode(st)
                val ms = SystemClock.elapsedRealtime() - t0
                val rtf = ms / 1000.0 / seconds
                val pss1 = pssMb()
                val heard = rec.getResult(st).text.trim()
                st.release(); rec.release()
                asrRes["${lang}_t$threads"] = linkedMapOf("rtf" to rtf, "load_ms" to loadMs, "pss_delta_mb" to (pss1 - pss0))
                asrText.append(String.format(Locale.US, "%-7s %d thr  RTF %.3f  (%.1fx real time)  load %d ms\n",
                    if (lang == "hi") "Hindi" else "English", threads, rtf, 1 / rtf, loadMs))
                if (threads == 2 && lang == "en") asrRes["en_sample_text"] = heard.take(120)
                show(asr, asrText.toString())
            }
        }
        // Hindi, AI4Bharat IndicConformer (whole sentences; the 15 s clip is decoded as one piece)
        if (AsrEngine.hasIndicConformer(models)) for (threads in listOf(2, 4)) {
            val pss0 = pssMb()
            val tl = SystemClock.elapsedRealtime()
            val rec = AsrEngine.buildOfflineHindi(models, threads)
            val loadMs = SystemClock.elapsedRealtime() - tl
            val t0 = SystemClock.elapsedRealtime()
            val st = rec.createStream(); st.acceptWaveform(audio, 16000); rec.decode(st)
            val ms = SystemClock.elapsedRealtime() - t0
            st.release(); rec.release()
            val rtf = ms / 1000.0 / seconds
            asrRes["hi_indicconformer_t$threads"] = linkedMapOf("rtf" to rtf, "load_ms" to loadMs, "pss_delta_mb" to (pssMb() - pss0))
            asrText.append(String.format(Locale.US, "%-7s %d thr  RTF %.3f  (%.1fx real time)  load %d ms\n", "Hindi+", threads, rtf, 1 / rtf, loadMs))
            show(asr, asrText.toString())
        }
        // English, NVIDIA Parakeet TDT-CTC 110M (whole sentences)
        if (AsrEngine.hasEnglishBig(models)) for (threads in listOf(2, 4)) {
            val pss0 = pssMb()
            val tl = SystemClock.elapsedRealtime()
            val rec = AsrEngine.buildOffline(models, "en", threads)
            val loadMs = SystemClock.elapsedRealtime() - tl
            val t0 = SystemClock.elapsedRealtime()
            val st = rec.createStream(); st.acceptWaveform(audio, 16000); rec.decode(st)
            val ms = SystemClock.elapsedRealtime() - t0
            if (threads == 2) asrRes["en_parakeet_sample_text"] = rec.getResult(st).text.take(120)
            st.release(); rec.release()
            val rtf = ms / 1000.0 / seconds
            asrRes["en_parakeet110m_t$threads"] = linkedMapOf("rtf" to rtf, "load_ms" to loadMs, "pss_delta_mb" to (pssMb() - pss0))
            asrText.append(String.format(Locale.US, "%-7s %d thr  RTF %.3f  (%.1fx real time)  load %d ms\n", "Engl.+", threads, rtf, 1 / rtf, loadMs))
            show(asr, asrText.toString())
        }
        results["asr"] = asrRes
        show(asr, asrText.toString().ifEmpty { t("मॉडल नहीं हैं", "models missing") })

        // 5. Llama
        val llm = section(t("5 · Llama 3.2 1B (ExecuTorch, Arm CPU)", "5 · Llama 3.2 1B (ExecuTorch, Arm CPU)"))
        val pte = ModelFiles.llamaPte(this)
        if (!pte.isFile || core == null) { show(llm, t("Llama फ़ाइल नहीं है", "Llama file missing")); results["llama"] = null }
        else {
            val pss0 = pssMb()
            val adv = LlamaAdvisor(pte.path, ModelFiles.llamaTokenizer(this).path, timeoutMs = 90_000)
            val l2 = CountDownLatch(1)
            adv.loadAsync { l2.countDown() }
            l2.await(180, TimeUnit.SECONDS)
            val pssLoaded = pssMb()
            if (adv.state != LlamaAdvisor.State.READY) { show(llm, t("लोड नहीं हुआ", "failed to load")); results["llama"] = mapOf("state" to adv.state.name) }
            else {
                val g = core!!.newGuardian(GuardianConfig(lang = "en"))
                val window = "I am Inspector Vikram Singh from Mumbai cyber crime branch. A parcel booked on your Aadhaar card contains drugs. " +
                    "An arrest warrant has been issued. Do not tell anyone. Transfer your savings to the RBI safe account now."
                val active = mapOf("authority_police" to 1.0, "identity_misuse" to 1.0, "threat" to 1.0, "isolation" to 1.0, "payment_request" to 1.0)
                val retrieved = core!!.playbook.entries.filter { it.id == "digital_arrest" || it.id == "courier_parcel" }
                show(llm, t("लोड हुआ, पहला जवाब बन रहा है…", "Loaded; first answer (cold)…"))
                val cur0 = currentMa()
                val thermal0 = thermal()
                g.askLlm(adv, window, active, retrieved)
                val cold = adv.lastStats
                show(llm, t("दूसरा जवाब (गरम prefix)…", "Second answer (warm system prefix)…"))
                adv.warm(g.buildLlmPrompt("", emptyMap(), emptyList()).first)
                SystemClock.sleep(500)
                val samples = ArrayList<Int>()
                val sampler = Thread { while (!Thread.currentThread().isInterrupted) { currentMa()?.let { samples.add(it) }; try { Thread.sleep(500) } catch (_: InterruptedException) { break } } }
                sampler.start()
                g.askLlm(adv, window, active, retrieved)
                sampler.interrupt()
                val warm = adv.lastStats
                val pssRun = pssMb()
                adv.close()
                fun st(s: LlamaAdvisor.Stats?) = s?.let { linkedMapOf("prompt_tokens" to it.promptTokens, "generated_tokens" to it.generatedTokens,
                    "ttft_ms" to it.ttftMs, "prefill_tok_s" to it.prefillTokPerS, "decode_tok_s" to it.decodeTokPerS, "total_ms" to it.totalMs, "warm_prefix" to it.usedWarmPrefix) }
                val avgCur = if (samples.isNotEmpty()) samples.average() else null
                results["llama"] = linkedMapOf("load_ms" to adv.loadMs, "pss_before_mb" to pss0, "pss_loaded_mb" to pssLoaded, "pss_running_mb" to pssRun,
                    "cold" to st(cold), "warm" to st(warm), "battery_current_ma_idle" to cur0, "battery_current_ma_generating" to avgCur,
                    "thermal_before" to thermal0, "thermal_after" to thermal())
                fun line(name: String, s: LlamaAdvisor.Stats?) = s?.let { String.format(Locale.US, "%-5s first token %5d ms · prefill %4.0f tok/s · decode %4.1f tok/s · %d+%d tok\n",
                    name, it.ttftMs, it.prefillTokPerS, it.decodeTokPerS, it.promptTokens, it.generatedTokens) } ?: "$name: -\n"
                show(llm, String.format(Locale.US, "load %d ms · memory +%d MB\n", adv.loadMs, pssLoaded - pss0) + line("cold", cold) + line("warm", warm) +
                    (avgCur?.let { String.format(Locale.US, "battery current while generating ≈ %.0f mA", it) } ?: ""))
            }
        }

        // 6. one minute of listening
        val live = section(t("6 · 1 मिनट सुनना (बैटरी और तापमान)", "6 · One minute of listening (battery and temperature)"))
        if (!File(models, "asr-en/encoder.onnx").isFile) { show(live, t("English मॉडल नहीं है", "English model missing")) }
        else {
            val rec = AsrEngine.buildRecognizer(models, "en", 2)
            val st = rec.createStream()
            val snapA = PerfStats.snapshot(this)
            val cpu0 = Process.getElapsedCpuTime()
            val wall0 = SystemClock.elapsedRealtime()
            val cur = ArrayList<Int>()
            var pos = 0
            while (SystemClock.elapsedRealtime() - wall0 < 60_000) {
                val tick = SystemClock.elapsedRealtime()
                val e = minOf(audio.size, pos + 1600)
                st.acceptWaveform(audio.copyOfRange(pos, e), 16000)
                while (rec.isReady(st)) rec.decode(st)
                if (rec.isEndpoint(st)) rec.reset(st)
                pos = if (e >= audio.size) 0 else e
                if ((tick - wall0) % 1000 < 100) currentMa()?.let { cur.add(it) }
                val left = 100 - (SystemClock.elapsedRealtime() - tick)
                if (left > 0) SystemClock.sleep(left)
                if ((SystemClock.elapsedRealtime() - wall0) % 5000 < 100)
                    show(live, t("सुन रहा है… ", "Listening… ") + "${(SystemClock.elapsedRealtime() - wall0) / 1000} s")
            }
            val cpuPct = (Process.getElapsedCpuTime() - cpu0) * 100.0 / (SystemClock.elapsedRealtime() - wall0)
            st.release(); rec.release()
            val snapB = PerfStats.snapshot(this)
            val avg = if (cur.isNotEmpty()) cur.average() else null
            val cap = batteryCapacityMah()
            val pctPerHour = if (avg != null && cap != null && !snapB.charging) avg / cap * 100 else null
            results["listening_60s"] = linkedMapOf("cpu_percent_of_one_core" to cpuPct, "battery_current_ma" to avg, "battery_capacity_mah" to cap,
                "battery_pct_per_hour" to pctPerHour, "battery_temp_start_c" to snapA.batteryTempC, "battery_temp_end_c" to snapB.batteryTempC,
                "thermal_status_end" to snapB.thermalStatus, "pss_mb" to snapB.pssMB, "charging" to snapB.charging)
            show(live, String.format(Locale.US, "CPU: %.0f%% of one core\n", cpuPct) +
                (avg?.let { String.format(Locale.US, "battery current: %.0f mA\n", it) } ?: "") +
                (pctPerHour?.let { String.format(Locale.US, "≈ %.1f%% battery per hour of listening\n", it) } ?: if (snapB.charging) t("चार्जिंग: बैटरी अनुमान नहीं\n", "charging: no battery estimate\n") else "") +
                String.format(Locale.US, "battery temperature %.1f → %.1f °C · thermal status %s", snapA.batteryTempC, snapB.batteryTempC, snapB.thermalStatus ?: "-"))
        }
        results["measured_at"] = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
    }

    // ------------------------------------------------------------------ small readers

    private fun pssMb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024

    /** Battery current in mA (positive = drawing). Phones report µA or mA; both are handled. */
    private fun currentMa(): Int? {
        val v = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (v == Int.MIN_VALUE || v == 0) return null
        val a = Math.abs(v)
        return if (a > 20_000) a / 1000 else a
    }

    private fun thermal(): Int? = if (Build.VERSION.SDK_INT >= 29) (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else null

    private fun batteryCapacityMah(): Double? = try {
        val c = Class.forName("com.android.internal.os.PowerProfile")
        val p = c.getConstructor(Context::class.java).newInstance(this)
        (c.getMethod("getBatteryCapacity").invoke(p) as Double).takeIf { it > 500 }
    } catch (_: Throwable) { null }

    private fun save() {
        try {
            val dir = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
            val f = File(dir, "kavach_bench_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json")
            f.writeText(MiniJson.write(results))
            savedPath = f.path
        } catch (t: Throwable) { savedPath = null }
    }

    private fun share() {
        val text = MiniJson.write(results)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "KAVACH benchmark"))
    }
}
