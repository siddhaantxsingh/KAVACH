package kavach.core

import kotlin.math.pow

/** Port of kavach_core/killchain.py — cross-channel scam kill-chain engine. */
data class KcEvent(val t: Double, val channel: String, val kind: String, val weight: Double, val detail: String = "")

data class Assessment(
    val level: String, val score: Double, val active: Map<String, Double>, val stages: List<String>,
    val rule: String, val channels: List<String>,
)

class KillChainConfig(
    val active: Double = 0.5,
    val cautionScore: Double = 3.0,
    val stopScore: Double = 7.0,
    val halfLifeS: Map<String, Double> = mapOf("call" to 600.0, "sms" to 1800.0, "link" to 1800.0, "app" to 86400.0, "screen" to 900.0, "network" to 3600.0),
    val crossWindowS: Double = 1800.0,
    val capPerCategory: Double = 3.0,
    val callGraceS: Double = 600.0,
    val longCallS: Double = 180.0,
)

class KillChain(private val stageOf: Map<String, String>, val cfg: KillChainConfig = KillChainConfig()) {
    private val events = ArrayList<KcEvent>()
    var peak = "none"
    var peakRule = ""

    fun reset() { events.clear(); peak = "none"; peakRule = "" }

    @Synchronized fun add(ev: KcEvent) { events.add(ev) }

    fun eventsSnapshot(): List<KcEvent> = synchronized(this) { ArrayList(events) }

    fun evidence(now: Double): LinkedHashMap<String, Double> {
        val ev = LinkedHashMap<String, Double>()
        for (e in eventsSnapshot()) {
            if (e.t > now) continue
            val hl = cfg.halfLifeS[e.channel] ?: 600.0
            ev[e.kind] = (ev[e.kind] ?: 0.0) + e.weight * 0.5.pow((now - e.t) / hl)
        }
        return ev
    }

    /** (start, still active, duration) of the latest unknown-number call if active or ended < callGraceS ago. */
    fun callContext(now: Double, evs: List<KcEvent> = eventsSnapshot()): Triple<Double, Boolean, Double>? {
        val s = evs.filter { it.kind == CALL_START && it.t <= now }.maxOfOrNull { it.t } ?: return null
        val end = evs.filter { it.kind == CALL_END && it.t >= s && it.t <= now }.minOfOrNull { it.t }
            ?: return Triple(s, true, now - s)
        return if (now - end <= cfg.callGraceS) Triple(s, false, end - s) else null
    }

    fun assess(now: Double): Assessment {
        val evs = eventsSnapshot()
        val ev = evidence(now)
        val active = LinkedHashMap<String, Double>()
        for ((k, v) in ev) if (v >= cfg.active) active[k] = v
        var score = 0.0
        for ((k, v) in ev) if (k != "safety_advice" && v > 0) score += minOf(v, cfg.capPerCategory)
        score += ev["safety_advice"] ?: 0.0
        if (active.keys.none { it in CONTACT_HOOK }) score *= 0.5
        val cats = active.keys - "safety_advice"
        val stages = cats.mapNotNull { stageOf[it] }.toSet().sortedBy { stageRank(it) }
        val channels = evs.filter { it.t <= now }.map { it.channel }.toSortedSet().toList()

        val callCats = evs.filter { it.channel == "call" && it.t <= now }.map { it.kind }.toSet().intersect(cats)
        var callScore = 0.0
        for ((k, v) in ev) if (k in callCats) callScore += minOf(v, cfg.capPerCategory)
        val cross = evs.filter { it.kind in CROSS_KINDS && now - it.t >= 0 && now - it.t <= cfg.crossWindowS }
        val hasSafety = (ev["safety_advice"] ?: 0.0) <= -cfg.active
        val ctx = callContext(now, evs)
        val acts = evs.filter {
            (it.kind in CALL_ACTIONS || it.kind == PAYMENT_OPEN) && it.t <= now &&
                ((ctx != null && it.t >= ctx.first) || (ctx == null && now - it.t <= cfg.crossWindowS))
        }
        val convo = callScore >= cfg.cautionScore ||
            evs.any { it.kind == "scam_call" && it.t <= now && (ctx == null || it.t >= ctx.first - 60) }

        var level = "none"
        var rule = ""
        when {
            "isolation" in cats && cats.any { it in STRONG_HOOK } -> { level = "stop"; rule = "isolation+authority" }
            cats.containsAll(STRONG_HOOK) -> { level = "stop"; rule = "authority+identity+threat" }
            cats.count { it in ACTION_PAY } >= 2 && !hasSafety -> { level = "stop"; rule = "multi-action" }
            cats.any { it in ACTION_PAY } && cats.any { it in HOOK_FOR_ACTION } && !hasSafety -> { level = "stop"; rule = "hook+action" }
            cats.any { it in VICTIM_ACT } && cats.any { it in VICTIM_PRETEXT } && !hasSafety -> { level = "stop"; rule = "victim-complying" }
            convo && acts.isNotEmpty() -> { level = "stop"; rule = "call+action:" + acts.last().kind }
            ctx != null && acts.any { it.kind in CALL_ACTIONS } -> { level = "stop"; rule = "unknown-call+" + acts.last { it.kind in CALL_ACTIONS }.kind }
            cross.isNotEmpty() && callScore >= cfg.cautionScore -> { level = "stop"; rule = "cross-channel:" + cross.last().kind }
            cross.size >= 2 && cross.map { it.channel }.toSet().size >= 2 -> { level = "stop"; rule = "cross-channel-multi" }
            score >= cfg.stopScore -> { level = "stop"; rule = "score" }
            score >= cfg.cautionScore -> { level = "caution"; rule = "score" }
            ctx != null && ctx.third >= cfg.longCallS && acts.any { it.kind == PAYMENT_OPEN } -> { level = "caution"; rule = "long-unknown-call+payment" }
            cats.any { it == "authority_police" || it == "authority_bank" } &&
                cats.any { it in CONTACT_HOOK && it != "authority_police" && it != "authority_bank" } -> { level = "caution"; rule = "authority+hook" }
            cross.isNotEmpty() -> { level = "caution"; rule = "single-channel:" + cross.last().kind }
            score > 0 && cats.isNotEmpty() -> { level = "info"; rule = "cue" }
        }
        if (LEVELS.indexOf(level) > LEVELS.indexOf(peak)) { peak = level; peakRule = rule }
        val activeR = LinkedHashMap<String, Double>()
        for ((k, v) in active) activeR[k] = round3(v)
        return Assessment(peak, round3(score), activeR, stages, peakRule, channels)
    }

    companion object {
        val LEVELS = listOf("none", "info", "caution", "stop")
        val CONTACT_HOOK = setOf("authority_police", "authority_bank", "identity_misuse", "threat", "urgency", "reward_greed", "family_emergency", "victim_fear")
        val ACTION_PAY = setOf("credential_request", "remote_access", "link_click", "payment_request")
        val HOOK_FOR_ACTION = CONTACT_HOOK - "urgency"
        val STRONG_HOOK = setOf("authority_police", "threat", "identity_misuse")
        /** what the person next to the Guardian says; normal alone, STOP only with a scam pretext */
        val VICTIM_ACT = setOf("victim_sharing", "victim_paying")
        val VICTIM_PRETEXT = STRONG_HOOK + setOf("victim_fear", "authority_bank")
        val CROSS_KINDS = setOf("dangerous_link", "risky_apk", "remote_access_active", "payment_app_while_sharing", "scam_sms", "scam_call",
            "suspicious_upload", "beaconing_app", "malicious_domain")
        const val CALL_START = "unknown_call_start"
        const val CALL_END = "call_end"
        const val PAYMENT_OPEN = "payment_app_opened"
        val CALL_ACTIONS = setOf("dangerous_link", "risky_apk", "remote_access_active", "payment_app_while_sharing", "suspicious_upload",
            "malicious_domain")

        /** Plain-language reason for a rule, for both screens ("which channel triggered it"). */
        fun ruleLabel(rule: String, lang: String): String {
            val hi = lang == "hi"
            val base = if (rule.startsWith("unknown-call+")) "unknown-call" else rule.substringBefore(':').substringBefore('(')
            val what = rule.substringAfter(':', "").ifEmpty { rule.substringAfter("unknown-call+", "") }
            val act = mapOf(
                "dangerous_link" to (if (hi) "खतरनाक लिंक" else "a dangerous link"), "risky_apk" to (if (hi) "जोखिम भरा ऐप" else "a risky app"),
                "remote_access_active" to (if (hi) "स्क्रीन-शेयर ऐप" else "a screen-sharing app"),
                "payment_app_while_sharing" to (if (hi) "स्क्रीन शेयर के दौरान पेमेंट ऐप" else "a payment app while the screen is shared"),
                "payment_app_opened" to (if (hi) "पेमेंट ऐप" else "a payment app"), "suspicious_upload" to (if (hi) "संदिग्ध डेटा अपलोड" else "a suspicious data upload"),
                "scam_sms" to (if (hi) "स्कैम एसएमएस" else "a scam SMS"), "scam_call" to (if (hi) "संदिग्ध कॉल" else "a suspicious call"),
                "beaconing_app" to (if (hi) "छुपकर डेटा भेजता ऐप" else "an app secretly sending data"),
                "malicious_domain" to (if (hi) "खतरनाक वेबसाइट से संपर्क" else "contact with a dangerous website"),
            )[what] ?: what
            return when (base) {
                "isolation+authority" -> if (hi) "कॉल: अधिकारी बनकर बात छुपाने को कहा" else "Call: posed as an officer and demanded secrecy"
                "authority+identity+threat" -> if (hi) "कॉल: पुलिस, आधार और गिरफ्तारी की धमकी" else "Call: police claim + Aadhaar + arrest threat"
                "multi-action", "hook+action" -> if (hi) "कॉल: ओटीपी / पैसे / ऐप की मांग" else "Call: asked for OTP / money / an app"
                "call+action" -> if (hi) "संदिग्ध कॉल + $act" else "Suspicious call + $act"
                "unknown-call" -> if (hi) "अनजान नंबर से कॉल + $act" else "Unknown caller + $act"
                "victim-complying" -> if (hi) "आप ओटीपी / पैसे दे रहे हैं, और कॉल करने वाला डरा रहा है" else "You are giving an OTP / money to a caller who is scaring you"
                "long-unknown-call+payment" -> if (hi) "लंबी अनजान कॉल के दौरान पेमेंट ऐप" else "Payment app during a long unknown call"
                "cross-channel" -> if (hi) "संदिग्ध कॉल + $act" else "Suspicious call + $act"
                "cross-channel-multi" -> if (hi) "एक से ज़्यादा जगह स्कैम के संकेत (मैसेज, लिंक या ऐप)" else "Scam signs in more than one place (message, link or app)"
                "classifier" -> if (hi) "बातचीत का पैटर्न स्कैम जैसा" else "Conversation pattern matches scams"
                "llm-early-warning" -> if (hi) "एआई (लामा) ने स्कैम पहचाना" else "AI (Llama) recognised the scam"
                "single-channel" -> if (hi) "जोखिम: $act" else "Risk: $act"
                else -> rule
            }
        }
        private val STAGE_ORDER = listOf("contact", "hook", "isolation", "action", "payment")
        fun stageRank(s: String) = STAGE_ORDER.indexOf(s).let { if (it < 0) 99 else it }
        fun round3(x: Double) = Math.round(x * 1000) / 1000.0
    }
}
