package kavach.core

import java.io.File

/**
 * Micro-benchmark of the per-tick cost of the always-on path (lexicon + kill-chain + linear
 * classifier over a 120-token window) and of Message Shield / Link Check, in the shipped
 * Kotlin code. Run on a laptop JVM here, and on device via the Guardian's perf overlay.
 *   kotlin -cp ... kavach.core.EngineBenchKt <repo>/assets
 */
fun main(args: Array<String>) {
    val core = KavachCore(ParityChecks.FileAssets(File(args.getOrElse(0) { "../assets" })))
    val lines = listOf("मैं मुंबई साइबर क्राइम ब्रांच से इंस्पेक्टर विक्रम सिंह बोल रहा हूं", "aapke aadhaar se money laundering hua hai arrest warrant nikla hai",
        "do not disconnect this call and do not tell anyone not even your family", "apne paise rbi ke safe account mein transfer kijiye")
    val g = core.newGuardian()
    repeat(200) { i -> g.onSegment(lines[i % lines.size], i * 3.0) }
    repeat(2000) { g.tick(600.0) } // warm-up (JIT)
    val n = 20000
    var t0 = System.nanoTime()
    repeat(n) { g.tick(600.0 + it * 0.001) }
    val tickUs = (System.nanoTime() - t0) / 1000.0 / n
    val msg = "Dear Customer your SBI account will be BLOCKED today. Update KYC: http://sbi-kyc-update.xyz/login"
    repeat(500) { core.shield.check(msg) }
    t0 = System.nanoTime()
    repeat(5000) { core.shield.check(msg) }
    val msgUs = (System.nanoTime() - t0) / 1000.0 / 5000
    t0 = System.nanoTime()
    repeat(5000) { core.urlChecker.check("hdfcbnak-secure.top/login") }
    val urlUs = (System.nanoTime() - t0) / 1000.0 / 5000
    val load0 = System.nanoTime(); KavachCore(ParityChecks.FileAssets(File(args.getOrElse(0) { "../assets" }))); val loadMs = (System.nanoTime() - load0) / 1e6
    println("{\"guardian_tick_us\": %.1f, \"message_check_us\": %.1f, \"url_check_us\": %.1f, \"core_load_ms\": %.0f, \"jvm\": \"%s\", \"cpu\": \"%s\"}"
        .format(tickUs, msgUs, urlUs, loadMs, System.getProperty("java.vm.version"), System.getProperty("os.arch")))
}
