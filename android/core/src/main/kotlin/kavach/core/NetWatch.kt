package kavach.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Network Watch: Kotlin port of kavach_core/netwatch.py (parity-tested).
 *  - NetWatch: per-app traffic patterns (suspicious_upload, beaconing_app) from byte counters.
 *  - DomainModel: machine-generated (DGA) / malicious domain score.
 *  - FlowModel: tiny MLP over per-connection statistics.
 */
class NetWatch(val cfg: Config = Config()) {
    data class Config(
        val windowS: Double = 300.0, val uploadMinBytes: Double = 5e6, val uploadRatio: Double = 3.0, val bgFrac: Double = 0.8,
        val historyS: Double = 3600.0, val slowMinBytes: Double = 4e6, val slowRatio: Double = 10.0, val slowMinBursts: Int = 6,
        val beaconMinEvents: Int = 8, val beaconMinSpanS: Double = 600.0,
        val beaconMinPeriodS: Double = 45.0, val beaconMaxCv: Double = 0.25, val beaconMaxBurst: Double = 64000.0,
        val cooldownS: Double = 3600.0, val wUpload: Double = 2.0, val wBeacon: Double = 1.5,
    )

    private data class S(val t: Double, val tx: Double, val rx: Double, val fg: Boolean)
    private val samples = HashMap<String, ArrayDeque<S>>()
    private val lastFired = HashMap<String, Double>()

    fun reset() { samples.clear(); lastFired.clear() }

    /** tx / rx: bytes the app sent / received since its previous sample. */
    fun observe(t: Double, app: String, tx: Double, rx: Double, foreground: Boolean, suspect: Boolean): List<KcEvent> {
        val q = samples.getOrPut(app) { ArrayDeque() }
        q.addLast(S(t, tx, rx, foreground))
        while (q.isNotEmpty() && t - q.first().t > cfg.historyS) q.removeFirst()
        if (!suspect) return emptyList()
        val out = ArrayList<KcEvent>()
        val win = q.filter { t - it.t <= cfg.windowS }
        val sent = win.sumOf { it.tx }
        val recv = win.sumOf { it.rx }
        val bgSent = win.filter { !it.fg }.sumOf { it.tx }
        if (sent >= cfg.uploadMinBytes && sent >= cfg.uploadRatio * (recv + 1.0) && bgSent >= cfg.bgFrac * sent) {
            fire(t, app, "suspicious_upload", cfg.wUpload,
                "%s sent %s MB in the background in %d min".format(java.util.Locale.ROOT, app, mb(sent), Math.floor(cfg.windowS / 60 + 0.5).toLong()))?.let { out.add(it) }
        } else {
            // slow exfiltration: stays under the 5-minute limit but keeps going
            val bg = q.filter { !it.fg }
            val hSent = bg.sumOf { it.tx }
            val hRecv = q.sumOf { it.rx }
            val nUp = bg.count { it.tx >= 50000 }
            if (hSent >= cfg.slowMinBytes && hSent >= cfg.slowRatio * (hRecv + 1.0) && nUp >= cfg.slowMinBursts)
                fire(t, app, "suspicious_upload", cfg.wUpload,
                    "%s sent %s MB in the background in the last hour".format(java.util.Locale.ROOT, app, mb(hSent)))?.let { out.add(it) }
        }
        val bursts = q.filter { !it.fg && it.tx + it.rx > 0 && it.tx + it.rx <= cfg.beaconMaxBurst }.map { it.t }
        val big = q.any { !it.fg && it.tx + it.rx > cfg.beaconMaxBurst }
        if (bursts.size >= cfg.beaconMinEvents && !big) {
            val recent = bursts.takeLast(12)
            val iv = recent.zipWithNext { a, b -> b - a }
            val mean = iv.sum() / iv.size
            val v = iv.sumOf { (it - mean) * (it - mean) } / iv.size
            val cv = if (mean > 0) sqrt(v) / mean else 1e9
            if (recent.last() - recent.first() >= cfg.beaconMinSpanS && mean >= cfg.beaconMinPeriodS && cv <= cfg.beaconMaxCv) {
                fire(t, app, "beaconing_app", cfg.wBeacon, "%s contacts the internet every %d s in the background".format(
                    java.util.Locale.ROOT, app, Math.floor(mean + 0.5).toLong()))?.let { out.add(it) }
            }
        }
        return out
    }

    private fun mb(n: Double): String { val t = Math.floor(n / 1e5 + 0.5).toLong(); return "${t / 10}.${t % 10}" }

    private fun fire(t: Double, app: String, kind: String, w: Double, detail: String): KcEvent? {
        val key = "$app\u0000$kind"
        val last = lastFired[key]
        if (last != null && t - last < cfg.cooldownS) return null
        lastFired[key] = t
        return KcEvent(t, "network", kind, w, detail)
    }
}

object DomainFeatures {
    private val CONSONANTS = "bcdfghjklmnpqrstvwxz".toSet()
    private val VOWELS = "aeiouy".toSet()

    fun core(host: String): Pair<List<String>, String> {
        var h = host.trim().lowercase().trim('.')
        if (h.startsWith("www.")) h = h.substring(4)
        val parts = h.split('.').filter { it.isNotEmpty() }
        if (parts.size >= 3 && parts.takeLast(2).joinToString(".") in UrlChecker.MULTI_SUFFIXES)
            return parts.dropLast(2) to parts.takeLast(2).joinToString(".")
        if (parts.size >= 2) return parts.dropLast(1) to parts.last()
        return parts to ""
    }

    fun tokens(host: String): List<String> {
        val (core, tld) = core(host)
        if (core.isEmpty()) return emptyList()
        var main = core[0]
        for (l in core) if (l.length > main.length) main = l
        val toks = ArrayList<String>()
        for (l in core) toks.addAll(l.split('-').filter { it.isNotEmpty() })
        val n = main.length
        val counts = LinkedHashMap<Char, Int>()
        for (c in main) counts[c] = (counts[c] ?: 0) + 1
        var ent = 0.0
        for (c in counts.values) { val p = c.toDouble() / n; ent -= p * (ln(p) / ln(2.0)) }
        var run = 0; var best = 0
        for (c in main) { run = if (c in CONSONANTS) run + 1 else 0; best = max(best, run) }
        val digits = main.count { it.isDigit() }
        val vowels = main.count { it in VOWELS }
        toks += listOf("#tld:$tld", "#len:${min(n / 3, 10)}", "#dig:${min(digits, 6)}", "#ent:${(ent * 2 + 1e-9).toInt()}",
            "#cons:${min(best, 8)}", "#vow:${(10.0 * vowels / max(n, 1)).toInt()}", "#nlab:${min(core.size, 4)}")
        return toks
    }
}

class DomainModel(private val m: LinearModel) {
    val threshold: Double get() = m.threshold
    fun score(host: String): Double {
        val t = DomainFeatures.tokens(host)
        return if (t.isEmpty()) 0.0 else Features.sigmoid(m.logitTokens(t))
    }
}

class FlowModel(spec: Map<String, Any?>) {
    private val mean = (spec["mean"] as List<*>).map { (it as Double) }
    private val std = (spec["std"] as List<*>).map { (it as Double) }
    private val layers: List<Pair<List<List<Double>>, List<Double>>> = (spec["layers"] as List<*>).map { l ->
        val m = l as Map<*, *>
        (m["w"] as List<*>).map { r -> (r as List<*>).map { it as Double } } to (m["b"] as List<*>).map { it as Double }
    }
    val threshold = (spec["threshold"] as? Double) ?: 0.5

    fun score(flow: Map<String, Any?>): Double {
        var h = vector(flow).mapIndexed { i, v -> (v - mean[i]) / std[i] }
        for ((li, layer) in layers.withIndex()) {
            val (w, b) = layer
            val out = DoubleArray(b.size)
            for (o in b.indices) { var z = b[o]; val row = w[o]; for (i in row.indices) z += row[i] * h[i]; out[o] = z }
            h = if (li < layers.size - 1) out.map { max(0.0, it) } else out.toList()
        }
        return Features.sigmoid(h[0])
    }

    companion object {
        val NUM = listOf("dur", "spkts", "dpkts", "sbytes", "dbytes", "rate", "smean", "dmean",
            "ct_srv_src", "ct_dst_ltm", "ct_src_dport_ltm", "ct_dst_sport_ltm", "ct_dst_src_ltm", "ct_srv_dst")
        val CAT = linkedMapOf(
            "proto" to listOf("tcp", "udp", "icmp"),
            "state" to listOf("FIN", "CON", "INT", "REQ", "RST"),
            "service" to listOf("-", "dns", "http", "ssl", "ftp", "ftp-data", "smtp", "ssh", "pop3"),
        )

        fun vector(flow: Map<String, Any?>): List<Double> {
            val x = ArrayList<Double>()
            for (k in NUM) x.add(kotlin.math.ln1p(max(((flow[k] as? Number)?.toDouble()) ?: 0.0, 0.0)))
            for ((k, vals) in CAT) {
                val v = flow[k]?.toString() ?: ""
                for (c in vals) x.add(if (v == c) 1.0 else 0.0)
                x.add(if (v in vals) 0.0 else 1.0)
            }
            return x
        }

        fun load(json: String) = FlowModel(MiniJson.parse(json).obj())
    }
}

/** DNS lookups per app -> malicious_domain (port of kavach_core.netwatch.DnsWatch). */
class DnsWatch(private val dm: DomainModel, private val uc: UrlChecker?, private val windowS: Double = 600.0,
               private val minDga: Int = 3, private val cooldownS: Double = 3600.0) {
    private val dga = HashMap<String, ArrayDeque<Pair<Double, String>>>()
    private val lastFired = HashMap<String, Double>()

    fun observe(t: Double, app: String, host: String, nxdomain: Boolean): List<KcEvent> {
        val h = host.trim().lowercase().trim('.')
        if (h.isEmpty() || '.' !in h) return emptyList()
        val out = ArrayList<KcEvent>()
        if (uc != null) {
            val v = uc.check(h)
            if (v.level == "dangerous" && v.reasons.any { it in IMPERSONATION })
                fire(t, "$app|$h", 2.0, "$app opened a look-alike website $h")?.let { out.add(it) }
        }
        if (nxdomain && dm.score(h) >= dm.threshold) {
            val q = dga.getOrPut(app) { ArrayDeque() }
            q.addLast(t to h)
            while (q.isNotEmpty() && t - q.first().first > windowS) q.removeFirst()
            val n = q.map { it.second }.toSet().size
            if (n >= minDga) fire(t, "$app|dga", 1.5, "$app looked up $n machine-generated domains that do not exist")?.let { out.add(it) }
        }
        return out
    }

    private fun fire(t: Double, key: String, w: Double, detail: String): KcEvent? {
        val last = lastFired[key]
        if (last != null && t - last < cooldownS) return null
        lastFired[key] = t
        return KcEvent(t, "network", "malicious_domain", w, detail)
    }

    companion object {
        val IMPERSONATION = setOf("typosquat", "homoglyph", "brand_in_unofficial_domain")

        data class DnsMsg(val id: Int, val isResponse: Boolean, val name: String, val rcode: Int)

        /** Minimal DNS message parser over a UDP payload (offset/len into a packet buffer). */
        fun parse(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): DnsMsg? {
            if (len < 12) return null
            fun b(i: Int) = buf[off + i].toInt() and 0xFF
            val id = (b(0) shl 8) or b(1)
            val flags = (b(2) shl 8) or b(3)
            if (((b(4) shl 8) or b(5)) < 1) return null
            var i = 12
            val labels = ArrayList<String>()
            while (true) {
                if (i >= len) return null
                val n = b(i)
                if (n == 0) break
                if (n and 0xC0 != 0 || i + 1 + n > len || labels.size > 127) return null
                val sb = StringBuilder()
                for (k in 0 until n) { val c = b(i + 1 + k); sb.append(if (c < 128) c.toChar().lowercaseChar() else '\uFFFD') }
                labels.add(sb.toString()); i += 1 + n
            }
            return DnsMsg(id, flags and 0x8000 != 0, labels.joinToString("."), flags and 0x000F)
        }
    }
}
