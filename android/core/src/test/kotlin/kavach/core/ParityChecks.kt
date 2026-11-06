package kavach.core

import java.io.File

/**
 * Compares the Kotlin core with the Python reference using assets/parity/vectors.json
 * (written by tests/make_parity_vectors.py). Pure Kotlin so it also runs without
 * Gradle:  kotlinc ... && kotlin -cp ... kavach.core.ParityChecksKt <repo>/assets
 */
object ParityChecks {
    class FileAssets(private val dir: File) : AssetSource {
        override fun text(name: String) = File(dir, name).readText(Charsets.UTF_8)
        override fun bytes(name: String) = File(dir, name).readBytes()
    }

    private fun close(a: Double, b: Double, tol: Double = 1e-6) = Math.abs(a - b) <= tol

    fun run(assets: File): List<String> {
        val fails = ArrayList<String>()
        var checks = 0
        fun check(ok: Boolean, msg: () -> String) { checks++; if (!ok) fails.add(msg()) }

        val v = MiniJson.parse(File(assets, "parity/vectors.json").readText()).obj()
        val core = KavachCore(FileAssets(assets))

        for (row in v["normalize"].arr()) { val r = row.arr(); val got = Normalize.normalize(r[0] as String)
            check(got == r[1]) { "normalize(${r[0]}) = '$got' expected '${r[1]}'" } }
        for (row in v["fnv"].arr()) { val r = row.arr(); val got = Features.fnv1a32(r[0] as String)
            check(got == (r[1] as Double).toLong()) { "fnv(${r[0]}) = $got expected ${r[1]}" } }
        for (row in v["lexicon"].arr()) {
            val r = row.arr()
            val got = core.lexicon.match(r[0] as String).map { listOf(it.category, it.phrase, it.start.toDouble(), it.end.toDouble()) }
            check(got == r[1].arr()) { "lexicon(${r[0]}) = $got expected ${r[1]}" }
        }
        val vs = v["vector_small"].obj()
        val toks = vs["tokens"].strList()
        val vec = Features.vectorize(toks, 1 shl 18, core.lexicon.matchTokens(toks)).values
        val exp = vs["entries"].arr().map { it.arr() }
        check(vec.size == exp.size) { "vector_small size ${vec.size} vs ${exp.size}" }
        for (e in exp) { val k = (e[0] as Double).toInt(); check(close(vec[k] ?: -1.0, e[1] as Double, 1e-12)) { "vector_small[$k]" } }

        val logits = v["logits"].obj()
        val models = mapOf("cue_model" to core.cueModel, "sms_model" to core.smsModel, "url_model" to core.urlModel)
        for ((name, m) in models) for (row in logits[name].arr()) {
            val r = row.arr(); val got = m.logitTokens(Normalize.tokens(r[0] as String))
            check(close(got, r[1] as Double, 1e-6)) { "$name logit('${r[0]}') = $got expected ${r[1]}" }
        }
        for (row in v["urls_extract"].arr()) { val r = row.arr(); val got = UrlChecker.extractUrls(r[0] as String)
            check(got == r[1]) { "extractUrls(${r[0]}) = $got expected ${r[1]}" } }
        for (row in v["url_verdicts"].arr()) {
            val r = row.arr(); val got = core.urlChecker.check(r[0] as String)
            check(got.level == r[1] && close(got.risk, r[2] as Double, 2e-4) && got.reasons == r[3]) {
                "url(${r[0]}) = ${got.level} ${got.risk} ${got.reasons} expected ${r[1]} ${r[2]} ${r[3]}" }
        }
        for (row in v["messages"].arr()) {
            val r = row.arr(); val got = core.shield.check(r[0] as String)
            check(got.level == r[1] && close(got.pScam, r[2] as Double, 2e-4)) { "message(${r[0]}) = ${got.level} ${got.pScam} expected ${r[1]} ${r[2]}" }
        }
        for (sc in v["killchain"].arr()) {
            val s = sc.obj()
            val kc = KillChain(core.lexicon.stageOf)
            val steps = s["steps"].arr()
            s["events"].arr().forEachIndexed { i, e ->
                val ev = e.arr()
                kc.add(KcEvent(ev[0] as Double, ev[1] as String, ev[2] as String, ev[3] as Double))
                val st = steps[i].arr()
                val a = kc.assess(st[0] as Double)
                check(a.level == st[1] && a.rule == st[2] && close(a.score, st[3] as Double, 2e-3)) { "killchain step $st got ${a.level} ${a.rule} ${a.score}" }
            }
        }
        for (row in v["retrieval"].arr()) {
            val r = row.arr()
            val act = r[0].obj().mapValues { it.value as Double }
            val got = core.playbook.rank(act, r[1] as String).map { it.first.id }
            check(got == r[2]) { "retrieval ${r[1]} = $got expected ${r[2]}" }
        }
        val pr = v["prompt"].obj()
        check(LlmContract.systemPrompt(core.playbook.typeIds) == pr["system"]) { "system prompt differs" }
        check(LlmContract.userPrompt("kisi ko mat batana", mapOf("isolation" to 2.5), core.playbook.entries.take(1), "hi") == pr["user"]) { "user prompt differs" }
        val fmt = LlmContract.formatLlama3(LlmContract.systemPrompt(core.playbook.typeIds), "u")
        check(listOf(fmt.first, fmt.second) == pr["formatted"]) { "llama3 format differs" }
        for (row in v["validate"].arr()) {
            val r = row.arr()
            val got = LlmContract.validate(r[0] as String, "main cbi se hoon kisi ko mat batana", core.playbook.typeIds).first
            val gotMap = got?.let { MiniJson.parse(it.toJson()) }
            check(gotMap == r[1]) { "validate(${r[0]}) = $gotMap expected ${r[1]}" }
        }
        for (gc in v["guardian"].arr()) {
            val g = gc.obj()
            val segs = g["segments"].arr().map { val s = it.arr(); (s[0] as Double) to (s[1] as String) }
            val guardian = core.newGuardian()
            val hist = guardian.runTranscript(segs)
            val exp2 = g["levels"].arr().map { it.arr() }
            check(hist.size == exp2.size) { "guardian ${g["id"]} ticks ${hist.size} vs ${exp2.size}" }
            for (i in 0 until minOf(hist.size, exp2.size)) {
                val e = exp2[i]
                if (!(close(hist[i].t, e[0] as Double, 1e-6) && hist[i].level == e[1] && close(hist[i].pScam, e[2] as Double, 2e-4))) {
                    check(false) { "guardian ${g["id"]} tick $i got (${hist[i].t}, ${hist[i].level}, ${hist[i].pScam}) expected $e" }; break
                }
            }
            check(guardian.rule == g["final_rule"]) { "guardian ${g["id"]} rule ${guardian.rule} expected ${g["final_rule"]}" }
        }
        // ---- Network Watch
        val dm = DomainModel(LinearModel.load(File(assets, "models/domain_model.json").readText(), File(assets, "models/domain_model.bin").readBytes(), null))
        for (row in v["domain"].arr()) {
            val r = row.arr(); val h = r[0] as String
            val toks = DomainFeatures.tokens(h)
            check(toks == r[1]) { "domainTokens($h) = $toks expected ${r[1]}" }
            check(close(dm.score(h), r[2] as Double, 1e-6)) { "domainScore($h) = ${dm.score(h)} expected ${r[2]}" }
        }
        val fm = FlowModel.load(File(assets, "models/flow_model.json").readText())
        for (row in v["flow"].arr()) {
            val r = row.arr(); @Suppress("UNCHECKED_CAST") val f = r[0] as Map<String, Any?>
            check(close(fm.score(f), r[1] as Double, 1e-6)) { "flowScore($f) = ${fm.score(f)} expected ${r[1]}" }
        }
        for (sc in v["netwatch"].arr()) {
            val o = sc.obj(); val nw = NetWatch()
            val got = ArrayList<List<Any?>>()
            for (smp in o["samples"].arr()) {
                val x = smp.arr()
                for (e in nw.observe(x[0] as Double, x[1] as String, x[2] as Double, x[3] as Double, x[4] as Boolean, x[5] as Boolean))
                    got.add(listOf(e.t, e.kind, e.weight, e.detail))
            }
            check(got == o["events"]) { "netwatch ${o["name"]} events $got expected ${o["events"]}" }
        }
        val rs = core.playbook.entries.filter { it.id == "remote_support" || it.id == "loan_app" }
        for ((i, np) in v["network_prompt"].arr().withIndex()) {
            val o = np.obj()
            val evs = o["events"].arr().map { val e = it.arr(); (e[0] as String) to (e[1] as String) }
            val ret = when (i) { 0 -> rs; 1 -> emptyList(); else -> rs.take(1) }
            check(LlmContract.networkUserPrompt(evs, o["call"] as Boolean, ret, o["lang"] as String) == o["text"]) { "network prompt $i differs" }
        }
        for (row in v["dns_parse"].arr()) {
            val r = row.arr(); val hex = r[0] as String
            val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            val m = DnsWatch.parse(bytes)
            val got = m?.let { listOf(it.id.toDouble(), it.isResponse, it.name, it.rcode.toDouble()) }
            check(got == r[1]) { "dnsParse($hex) = $got expected ${r[1]}" }
        }
        val dw = DnsWatch(dm, core.urlChecker)
        val dwv = v["dns_watch"].obj()
        val dexp = dwv["events"].arr()
        for ((i, lk) in dwv["lookups"].arr().withIndex()) {
            val x = lk.arr()
            val got = dw.observe(x[0] as Double, x[1] as String, x[2] as String, x[3] as Boolean).map { listOf(it.t, it.kind, it.weight, it.detail) }
            check(got == dexp[i]) { "dnsWatch $x = $got expected ${dexp[i]}" }
        }
        println("parity: $checks checks, ${fails.size} failures")
        return fails
    }
}

fun main(args: Array<String>) {
    val fails = ParityChecks.run(File(args.getOrElse(0) { "../assets" }))
    fails.take(30).forEach { println("FAIL $it") }
    if (fails.isNotEmpty()) kotlin.system.exitProcess(1)
}
