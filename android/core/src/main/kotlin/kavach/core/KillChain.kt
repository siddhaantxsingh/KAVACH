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

        var level = "none"
        var rule = ""
        when {
            "isolation" in cats && cats.any { it in STRONG_HOOK } -> { level = "stop"; rule = "isolation+authority" }
            cats.containsAll(STRONG_HOOK) -> { level = "stop"; rule = "authority+identity+threat" }
            cats.count { it in ACTION_PAY } >= 2 && !hasSafety -> { level = "stop"; rule = "multi-action" }
            cats.any { it in ACTION_PAY } && cats.any { it in HOOK_FOR_ACTION } && !hasSafety -> { level = "stop"; rule = "hook+action" }
            cross.isNotEmpty() && callScore >= cfg.cautionScore -> { level = "stop"; rule = "cross-channel:" + cross.last().kind }
            cross.size >= 2 && cross.map { it.channel }.toSet().size >= 2 -> { level = "stop"; rule = "cross-channel-multi" }
            score >= cfg.stopScore -> { level = "stop"; rule = "score" }
            score >= cfg.cautionScore -> { level = "caution"; rule = "score" }
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
        val CONTACT_HOOK = setOf("authority_police", "authority_bank", "identity_misuse", "threat", "urgency", "reward_greed", "family_emergency")
        val ACTION_PAY = setOf("credential_request", "remote_access", "link_click", "payment_request")
        val HOOK_FOR_ACTION = CONTACT_HOOK - "urgency"
        val STRONG_HOOK = setOf("authority_police", "threat", "identity_misuse")
        val CROSS_KINDS = setOf("dangerous_link", "risky_apk", "remote_access_active", "payment_app_while_sharing", "scam_sms")
        private val STAGE_ORDER = listOf("contact", "hook", "isolation", "action", "payment")
        fun stageRank(s: String) = STAGE_ORDER.indexOf(s).let { if (it < 0) 99 else it }
        fun round3(x: Double) = Math.round(x * 1000) / 1000.0
    }
}
