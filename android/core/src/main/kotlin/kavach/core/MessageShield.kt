package kavach.core

/** Port of kavach_core/message_shield.py — scam SMS / notification screening with Link Check. */
data class MessageVerdict(
    val text: String, val pScam: Double, val level: String, val cues: Map<String, Int>, val urls: List<UrlVerdict>,
    val topTokens: List<Pair<String, Double>>,
) {
    fun events(t: Double): List<KcEvent> {
        val evs = ArrayList<KcEvent>()
        if (level == "dangerous") evs.add(KcEvent(t, "sms", "scam_sms", 2.0, text.take(80)))
        for (u in urls) if (u.level == "dangerous") evs.add(KcEvent(t, "link", "dangerous_link", 2.5, u.host))
        for ((c, n) in cues) if (c != "safety_advice") evs.add(KcEvent(t, "sms", c, 0.5 * n, "sms"))
        return evs
    }
}

class MessageShield(private val model: LinearModel?, private val lexicon: Lexicon, val urls: UrlChecker) {
    fun check(text: String): MessageVerdict {
        val p = model?.predictProba(text) ?: 0.0
        val cues = lexicon.categoriesIn(text)
        val uv = UrlChecker.extractUrls(text).map { urls.check(it) }
        val worst = uv.maxOfOrNull { it.risk } ?: 0.0
        val hasAction = cues.keys.any { it in ACTION_CATS } || uv.isNotEmpty()
        val level = when {
            p >= 0.9 || worst >= 0.8 || (p >= 0.6 && hasAction) -> "dangerous"
            p >= 0.6 || worst >= 0.5 -> "suspicious"
            else -> "safe"
        }
        val top = if (model != null && level != "safe") model.explain(text, 4) else emptyList()
        return MessageVerdict(text, Math.round(p * 10000) / 10000.0, level, cues, uv, top)
    }

    companion object { val ACTION_CATS = setOf("credential_request", "remote_access", "link_click", "payment_request") }
}

/** Where the shared assets come from: the repo's assets/ folder in tests, Android assets on device. */
interface AssetSource {
    fun text(name: String): String
    fun bytes(name: String): ByteArray
}

/** Loads everything once. ~3 MB of weights; load off the main thread. */
class KavachCore(src: AssetSource) {
    val lexicon = Lexicon(src.text("cues.json"))
    val playbook = Playbook(src.text("playbook.json"))
    val cueModel = LinearModel.load(src.text("models/cue_model.json"), src.bytes("models/cue_model.bin"), lexicon)
    val smsModel = LinearModel.load(src.text("models/sms_model.json"), src.bytes("models/sms_model.bin"), lexicon)
    val urlModel = LinearModel.load(src.text("models/url_model.json"), src.bytes("models/url_model.bin"), lexicon)
    val urlChecker = UrlChecker(src.text("genuine_domains.json"), urlModel)
    val shield = MessageShield(smsModel, lexicon, urlChecker)

    fun newGuardian(cfg: GuardianConfig = GuardianConfig()) = Guardian(lexicon, cueModel, playbook, cfg)
}
