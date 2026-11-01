package kavach.core

import java.net.IDN

/** Port of kavach_core/url_check.py (Link Check). */
data class UrlVerdict(
    val url: String, val host: String, val registrable: String, val risk: Double, val level: String,
    val reasons: List<String>, val nearestGenuine: String?,
) {
    fun reasonText(lang: String): List<String> = reasons.map { UrlChecker.REASONS[it]!!.let { p -> if (lang == "en") p.first else p.second } }
}

class UrlChecker(genuineJson: String, private val model: LinearModel?, modelThreshold: Double? = null) {
    private val domains: Set<String>
    private val suffixes: List<String>
    private val brands: List<String>
    private val brandSet: Set<String>
    private val badTlds: Set<String>
    private val shorteners: Set<String>
    private val userContent: List<String>
    private val genuineLabels: List<String>
    private val modelThreshold: Double = modelThreshold ?: (model?.threshold ?: 0.5)

    init {
        val d = MiniJson.parse(genuineJson).obj()
        domains = d["domains"].strList().toSet()
        suffixes = d["trusted_suffixes"].strList()
        brandSet = d["brands"].strList().toSet()
        // Python: sorted(set(brands), key=len, reverse=True) -> stable sort of an unordered set.
        // To be deterministic in both languages we sort by (-len, name).
        brands = brandSet.sortedWith(compareBy<String>({ -it.length }, { it }))
        badTlds = d["suspicious_tlds"].strList().toSet()
        shorteners = d["shorteners"].strList().toSet()
        userContent = d["user_content_hosts"].strList()
        genuineLabels = (domains.filter { it.contains('.') }.map { labelOf(it) } + brandSet.filter { it.length >= 5 }).toSortedSet().toList()
    }

    fun isGenuine(host: String): Boolean {
        if (userContent.any { host == it || host.endsWith(".$it") }) return false
        if (suffixes.any { host == it || host.endsWith(".$it") }) return true
        return domains.any { host == it || host.endsWith(".$it") }
    }

    private fun isUserContent(host: String) = userContent.any { host == it || host.endsWith(".$it") }

    fun findBrand(host: String): String? {
        val pieces = host.split(PIECE_SPLIT).filter { it.isNotEmpty() }
        for (p in pieces) for (b in brands) {
            if (b.length < 3) continue
            if (p == b) return b
            if (p.startsWith(b) && (p.substring(b.length) in GLUE_WORDS || p.substring(b.length) in brandSet)) return b
            if (p.endsWith(b) && (p.substring(0, p.length - b.length) in GLUE_WORDS || p.substring(0, p.length - b.length) in brandSet)) return b
        }
        return null
    }

    fun modelText(host: String, path: String): String = (host + path).split(MODEL_SPLIT).joinToString(" ").trim()

    fun check(url: String): UrlVerdict {
        val (host, path) = splitUrl(url)
        val uhost = toUnicodeHost(host)
        val reg = registrableDomain(host)
        if (isGenuine(host)) return UrlVerdict(url, host, reg, 0.0, "safe", listOf("genuine"), null)
        val reasons = ArrayList<String>()
        var nearest: String? = null
        val label = labelOf(reg)

        if (IP_RE.matches(host)) reasons.add("ip_host")
        if (authority(url).contains('@')) reasons.add("at_sign")
        if (host in shorteners || reg in shorteners) reasons.add("shortener")
        if (path.lowercase().split("?")[0].endsWith(".apk")) reasons.add("apk_download")
        val tld = if (host.contains('.')) host.substringAfterLast('.') else ""
        if (tld in badTlds) reasons.add("suspicious_tld")
        // any internationalised label is a look-alike signal (decoders differ between platforms)
        if (host.contains("xn--") || uhost != host || uhost.any { it.code > 127 }) reasons.add("homoglyph")

        val brand = findBrand(host)
        if (isUserContent(host)) {
            if (brand != null || brands.any { it.length >= 4 && path.lowercase().contains(it) }) reasons.add("user_content_brand")
        } else if (brand != null && brand !in WEAK_BRANDS) {
            reasons.add("brand_in_unofficial_domain"); nearest = brand
        } else if (brand == "kyc" || brand == "upi") {
            reasons.add("risk_words")
        }

        if ("homoglyph" !in reasons && "brand_in_unofficial_domain" !in reasons && label.length >= 4) {
            val dl = deconfuse(label)
            for (g in genuineLabels) {
                if (g.length < 4) continue
                if (dl == deconfuse(g) && label != g) { reasons.add("homoglyph"); nearest = g; break }
                if (isTyposquat(label, g)) { reasons.add("typosquat"); nearest = g; break }
            }
        }
        val low = (host + path).lowercase()
        if ("risk_words" !in reasons && RISK_WORDS.count { low.contains(it) } >= 2) reasons.add("risk_words")

        var ruleRisk = reasons.maxOfOrNull { RULE_RISK[it] ?: 0.0 } ?: 0.0
        val weak = reasons.count { it == "shortener" || it == "suspicious_tld" || it == "risk_words" }
        if (weak >= 2) ruleRisk = maxOf(ruleRisk, 0.75)
        val modelP = model?.predictProba(modelText(host, path)) ?: 0.0
        val t = modelThreshold
        val modelRisk = if (modelP < t) 0.5 * modelP / t else 0.5 + 0.5 * (modelP - t) / maxOf(1e-6, 1 - t)
        if (modelRisk >= 0.5 && reasons.isEmpty()) reasons.add("model")
        val risk = maxOf(ruleRisk, modelRisk)
        val level = if (risk >= 0.8) "dangerous" else if (risk >= 0.5) "suspicious" else "unknown"
        return UrlVerdict(url, host, reg, Math.round(risk * 10000) / 10000.0, level, reasons, nearest)
    }

    companion object {
        val URL_RE = Regex(
            "((?:https?://|hxxps?://|www\\.)[^ <>\"']+|(?<![A-Za-z0-9_])(?:[a-z0-9-]+\\.)+[a-z]{2,24}(?:/[^ <>\"']*)?)",
            RegexOption.IGNORE_CASE,
        )
        private val IP_RE = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
        private val PIECE_SPLIT = Regex("[.\\-_0-9]+")
        private val MODEL_SPLIT = Regex("[./\\-_?=&]+")
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        private val HXXP = Regex("^hxxp", RegexOption.IGNORE_CASE)
        private val DIGITS_DOTS = Regex("[\\d.]+")
        private val WEAK_BRANDS = setOf("kyc", "upi", "gst", "lic", "bob", "pan")
        val MULTI_SUFFIXES = setOf("co.in", "org.in", "net.in", "gov.in", "nic.in", "ac.in", "edu.in", "res.in", "firm.in", "gen.in", "ind.in",
            "bank.in", "fin.in", "co.uk", "org.uk", "com.au", "co.jp", "com.br", "com.cn", "com.sg", "co.za")
        private val CONFUSABLE = listOf("rn" to "m", "vv" to "w", "0" to "o", "1" to "l", "l" to "i", "3" to "e", "5" to "s", "@" to "a")
        val RISK_WORDS = listOf("kyc", "update", "verify", "verification", "login", "secure", "account", "block", "suspend", "reward", "refund",
            "cashback", "challan", "lottery", "prize", "bonus", "kbc", "aadhar", "aadhaar", "pan", "otp", "upi", "gift", "claim", "apk")
        val GLUE_WORDS: Set<String> = RISK_WORDS.toSet() + setOf("online", "bank", "netbanking", "net", "care", "help", "helpline", "support", "service",
            "services", "india", "in", "app", "pay", "rewards", "card", "cards", "official", "customer", "portal", "gov", "my", "e", "web", "points", "alert")
        val RULE_RISK = mapOf("brand_in_unofficial_domain" to 0.9, "typosquat" to 0.9, "homoglyph" to 0.95, "ip_host" to 0.8, "at_sign" to 0.8,
            "apk_download" to 0.85, "user_content_brand" to 0.85, "shortener" to 0.5, "suspicious_tld" to 0.45, "risk_words" to 0.4)
        val REASONS = mapOf(
            "genuine" to ("Matches an official domain" to "आधिकारिक डोमेन से मेल खाता है"),
            "brand_in_unofficial_domain" to ("Uses a bank/government name but is not its official website" to "बैंक/सरकारी नाम का इस्तेमाल, पर आधिकारिक वेबसाइट नहीं"),
            "typosquat" to ("Spelled almost like an official website" to "आधिकारिक वेबसाइट जैसी मिलती-जुलती स्पेलिंग"),
            "homoglyph" to ("Uses look-alike letters to imitate a real website" to "असली वेबसाइट की नकल के लिए मिलते-जुलते अक्षर"),
            "ip_host" to ("Uses a raw IP address instead of a name" to "नाम की जगह सीधा आईपी पता"),
            "at_sign" to ("Hides the real destination with '@'" to "'@' से असली पता छुपाया गया"),
            "shortener" to ("Shortened link hides the real website" to "छोटा लिंक असली वेबसाइट छुपाता है"),
            "apk_download" to ("Downloads an Android app (APK) outside the Play Store" to "प्ले स्टोर के बाहर से ऐप (एपीके) डाउनलोड"),
            "suspicious_tld" to ("Uses a domain ending often used in scams" to "स्कैम में अक्सर इस्तेमाल होने वाला डोमेन"),
            "user_content_brand" to ("Free website builder page pretending to be an official site" to "मुफ्त वेबसाइट पेज जो आधिकारिक साइट होने का दिखावा करता है"),
            "risk_words" to ("Contains words like KYC, verify, refund, reward" to "केवाईसी, वेरिफाई, रिफंड, इनाम जैसे शब्द"),
            "model" to ("Pattern matches known phishing links" to "जाने-माने फिशिंग लिंक जैसा पैटर्न"),
        )

        fun extractUrls(text: String): List<String> {
            val t = buildString { for (c in text) append(if (c.isWhitespace()) ' ' else c) }
            val out = ArrayList<String>()
            for (m in URL_RE.findAll(t)) {
                val u = m.groupValues[1].trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                val head = u.split("/")[0]
                if ((u.contains('.') && !DIGITS_DOTS.matches(head)) || u.lowercase().startsWith("http") || u.lowercase().startsWith("hxxp")) out.add(u)
            }
            return out
        }

        fun splitUrl(url: String): Pair<String, String> {
            var u = url.trim()
            u = HXXP.replace(u, "http")
            u = SCHEME.replace(u, "")
            val slash = u.indexOf('/')
            var hostport = if (slash >= 0) u.substring(0, slash) else u
            val rest = if (slash >= 0) u.substring(slash + 1) else ""
            val path = if (rest.isNotEmpty()) "/$rest" else ""
            if (hostport.contains('@')) hostport = hostport.substringAfterLast('@')
            var host = hostport.split(":")[0].trim('.').lowercase()
            if (host.startsWith("www.")) host = host.substring(4)
            return host to path
        }

        fun authority(url: String): String = SCHEME.replace(url.trim(), "").split("/", limit = 2)[0]

        fun toUnicodeHost(host: String): String =
            if (!host.contains("xn--")) host else try { IDN.toUnicode(host) } catch (e: Exception) { host }

        fun registrableDomain(host: String): String {
            val parts = host.split(".")
            if (parts.size >= 3 && parts.takeLast(2).joinToString(".") in MULTI_SUFFIXES) return parts.takeLast(3).joinToString(".")
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        }

        fun labelOf(registrable: String): String {
            val parts = registrable.split(".")
            if (parts.size >= 3 && parts.takeLast(2).joinToString(".") in MULTI_SUFFIXES) return parts[parts.size - 3]
            return parts[0]
        }

        fun deconfuse(s: String): String { var x = s; for ((a, b) in CONFUSABLE) x = x.replace(a, b); return x }

        fun osaDistance(a: String, b: String, cap: Int = 3): Int {
            if (Math.abs(a.length - b.length) > cap) return cap + 1
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
            return d[a.length][b.length]
        }

        fun isTyposquat(label: String, genuine: String): Boolean {
            if (genuine.length < 5 || label == genuine) return false
            val dist = osaDistance(label, genuine, 2)
            if (dist == 1) return label.length <= genuine.length
            return dist == 2 && genuine.length >= 8 && label.length == genuine.length
        }
    }
}
