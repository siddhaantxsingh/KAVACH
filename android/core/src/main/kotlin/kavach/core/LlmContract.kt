package kavach.core

/**
 * Port of kavach_core/llm.py — the Llama 3.2 1B contract: prompts, JSON verdict
 * validation (evidence quotes must be verbatim), and the offline fallback.
 */
data class Verdict(
    val scam: Boolean, val type: String, val stage: String, val confidence: Double,
    val evidence: List<String>, val say: String, val action: String, val source: String = "llm",
) {
    fun toJson(): String = MiniJson.write(linkedMapOf(
        "scam" to scam, "type" to type, "stage" to stage, "confidence" to confidence,
        "evidence" to evidence, "say" to say, "action" to action,
    ))
}

object LlmContract {
    val STAGES = listOf("none", "contact", "hook", "isolation", "action", "payment")
    val ACTIONS = listOf("none", "continue_with_care", "verify_independently", "do_not_share", "do_not_pay", "hang_up")
    const val MAX_SAY_WORDS = 35
    private const val BOT = "<|begin_of_text|>"
    private const val SH = "<|start_header_id|>"
    private const val EH = "<|end_header_id|>"
    private const val EOT = "<|eot_id|>"

    fun systemPrompt(typeIds: List<String>): String =
        "You are KAVACH, an on-device scam-call guardian for people in India. " +
            "You read a live phone-call transcript (Hindi, English or Hinglish; it comes from speech recognition and has errors) " +
            "and decide whether the caller is running a scam.\n" +
            "Rules:\n" +
            "- Police, CBI, customs, courts and banks NEVER ask for money, OTP, PIN, screen sharing or app installs on a call, " +
            "and there is no 'digital arrest' in Indian law.\n" +
            "- Genuine calls (bank fraud alerts that say never share OTP, delivery agents, relatives) must not be called scams.\n" +
            "- Quote evidence only as exact words from the transcript.\n" +
            "- 'say' is spoken to a frightened, possibly elderly person: short, calm, clear, in the requested language, " +
            "and must include the next step. Helpline: 1930.\n" +
            "- 'say' is YOUR advice to the user in your own words. Never repeat what the caller said.\n" +
            "Answer with ONE JSON object and nothing else, keys in this order:\n" +
            "{\"scam\": true|false, \"type\": one of [" + typeIds.joinToString(", ") + ", \"other\", \"none\"], " +
            "\"stage\": one of [" + STAGES.joinToString(", ") + "], \"confidence\": 0.0-1.0, " +
            "\"evidence\": [up to 3 short exact quotes], \"say\": \"max 35 words\", " +
            "\"action\": one of [" + ACTIONS.joinToString(", ") + "]}\n" +
            "Example: {\"scam\": true, \"type\": \"digital_arrest\", \"stage\": \"payment\", \"confidence\": 0.9, " +
            "\"evidence\": [\"arrest warrant has been issued\"], \"say\": \"This is a scam. Real police never ask for money on a call. " +
            "Do not send anything. Hang up and call 1930.\", \"action\": \"hang_up\"}"

    fun userPrompt(transcript: String, active: Map<String, Double>, retrieved: List<PlaybookEntry>, lang: String): String {
        val cues = active.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${String.format(java.util.Locale.ROOT, "%.1f", it.value)}" }
            .ifEmpty { "none" }
        val pb = retrieved.joinToString("\n") { "- ${it.id}: ${it.storyEn} TRUTH: ${it.truthEn}" }
        val language = if (lang == "hi") "Hindi (Devanagari)" else "English"
        return "Relevant scam patterns:\n$pb\nDetected cue scores: $cues\nReply language for 'say': $language\n" +
            "Transcript (most recent last):\n\"\"\"${transcript.trim()}\"\"\""
    }

    /** Variant for a high-risk SMS / WhatsApp message on the user's phone (same JSON contract). */
    fun messageUserPrompt(message: String, active: Map<String, Double>, urlReasons: List<String>, retrieved: List<PlaybookEntry>, lang: String): String {
        val cues = active.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${String.format(java.util.Locale.ROOT, "%.1f", it.value)}" }
            .ifEmpty { "none" }
        val pb = retrieved.joinToString("\n") { "- ${it.id}: ${it.storyEn} TRUTH: ${it.truthEn}" }
        val language = if (lang == "hi") "Hindi (Devanagari)" else "English"
        val links = urlReasons.joinToString("; ").ifEmpty { "none" }
        return "Relevant scam patterns:\n$pb\nDetected cue scores: $cues\nLink check findings: $links\n" +
            "Reply language for 'say': $language\nThis is a text MESSAGE the user received, not a call. Message:\n\"\"\"${message.trim()}\"\"\""
    }

    val NETWORK_KIND_TEXT = mapOf(
        "suspicious_upload" to "an app secretly sent a lot of data from the phone",
        "beaconing_app" to "an app keeps contacting a server in the background at a steady rhythm (remote-control pattern)",
        "malicious_domain" to "an app contacted a fake or malware website",
    )

    /** Network Watch variant (same JSON contract). events: (kind, detail). */
    fun networkUserPrompt(events: List<Pair<String, String>>, inUnknownCall: Boolean, retrieved: List<PlaybookEntry>, lang: String): String {
        val pb = retrieved.joinToString("\n") { "- ${it.id}: ${it.storyEn} TRUTH: ${it.truthEn}" }
        val language = if (lang == "hi") "Hindi (Devanagari)" else "English"
        val lines = events.joinToString("\n") { (k, d) -> "- ${NETWORK_KIND_TEXT[k] ?: k}: \"$d\"" }.ifEmpty { "- none" }
        val call = if (inUnknownCall) "yes, a call with an unknown number is going on right now" else "no"
        return "Relevant scam patterns:\n$pb\nNetwork Watch findings on the user's phone:\n$lines\n" +
            "During a call from an unknown number: $call\nReply language for 'say': $language\n" +
            "This is NOT a call transcript. Explain simply what the app may be doing (stealing data, remote control) " +
            "and what to do now (uninstall the app, turn off mobile data / Wi-Fi, call 1930)."
    }

    /** (prefix, suffix). The prefix never changes: pre-fill it once into the KV cache. */
    fun formatLlama3(system: String, user: String): Pair<String, String> =
        "$BOT${SH}system$EH\n\n$system$EOT" to "${SH}user$EH\n\n$user$EOT${SH}assistant$EH\n\n"

    fun repairSuffix(suffix: String, previous: String, errors: List<String>): String =
        "$suffix${previous.trim()}$EOT${SH}user$EH\n\nYour previous answer was not valid: " + errors.take(3).joinToString("; ") +
            ". Reply again with ONE valid JSON object only.$EOT${SH}assistant$EH\n\n"

    fun extractJsonObject(text: String): String? {
        var start = text.indexOf('{')
        while (start >= 0) {
            var depth = 0; var inStr = false; var esc = false
            for (i in start until text.length) {
                val ch = text[i]
                if (inStr) {
                    if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
                } else if (ch == '"') inStr = true
                else if (ch == '{') depth++
                else if (ch == '}') { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    fun validate(raw: String, transcript: String, typeIds: List<String>): Pair<Verdict?, List<String>> {
        val errors = ArrayList<String>()
        val js = extractJsonObject(raw) ?: return null to listOf("no JSON object found")
        val parsed = try { MiniJson.parse(js) } catch (e: MiniJson.ParseException) { return null to listOf("invalid JSON: ${e.message}") }
        val d = parsed as? Map<*, *> ?: return null to listOf("top level is not an object")
        val scam: Boolean = when (val s = d["scam"]) {
            is Boolean -> s
            is String -> when (s.trim().lowercase()) { "true", "yes" -> true; "false", "no" -> false; else -> null }
            else -> null
        } ?: return null to listOf("'scam' must be true or false")

        var typ = (d["type"] ?: "other").toString().trim()
        if (typ !in typeIds && typ != "other" && typ != "none") { errors.add("unknown type '$typ' -> other"); typ = "other" }
        var stage = (d["stage"] ?: "none").toString().trim()
        if (stage !in STAGES) { errors.add("unknown stage '$stage' -> none"); stage = "none" }
        var conf = when (val c = d["confidence"]) {
            null -> 0.5
            is Double -> c
            is String -> c.toDoubleOrNull() ?: run { errors.add("confidence not a number"); 0.5 }
            else -> { errors.add("confidence not a number"); 0.5 }
        }
        conf = conf.coerceIn(0.0, 1.0)

        val normTr = " " + Normalize.normalize(transcript) + " "
        val evidence = ArrayList<String>()
        val rawEv = d["evidence"] as? List<*> ?: emptyList<Any?>()
        for (q in rawEv) {
            if (q !is String) continue
            val nq = Normalize.normalize(q)
            if (nq.isNotEmpty() && normTr.contains(" $nq ")) evidence.add(q.trim()) else errors.add("dropped non-verbatim quote '${q.take(40)}'")
        }
        val ev = evidence.take(3)
        if (scam && ev.isEmpty() && rawEv.isNotEmpty()) conf = minOf(conf, 0.6)
        var say = (d["say"] ?: "").toString().trim()
        if (sayCopiesTranscript(say, transcript)) return null to listOf("'say' repeats the caller's words; write short advice TO THE USER instead")
        val words = say.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size > MAX_SAY_WORDS) say = words.take(MAX_SAY_WORDS).joinToString(" ")
        var action = (d["action"] ?: "none").toString().trim()
        if (action !in ACTIONS) { errors.add("unknown action '$action'"); action = if (scam) "hang_up" else "none" }
        if (!scam && typ == "other") typ = "none"
        return Verdict(scam, typ, stage, Math.round(conf * 1000) / 1000.0, ev, say, action, "llm") to errors
    }

    /** The advice mostly repeats the call itself (>= 60% of its words, at least 4, come from the call). */
    fun sayCopiesTranscript(say: String, transcript: String): Boolean {
        val words = Normalize.normalize(say).split(' ').filter { it.isNotEmpty() }
        if (words.size < 4) return false
        val heard = Normalize.normalize(transcript).split(' ').toSet()
        return words.count { it in heard }.toDouble() / words.size >= 0.6
    }

    fun fallbackVerdict(level: String, active: Map<String, Double>, retrieved: List<PlaybookEntry>, labels: Map<String, Pair<String, String>>,
                        stages: List<String>, lang: String): Verdict {
        if ((level != "caution" && level != "stop") || retrieved.isEmpty()) return Verdict(false, "none", "none", 0.2, emptyList(), "", "none", "fallback")
        val top = retrieved[0]
        val cats = active.entries.sortedByDescending { it.value }.map { it.key }.filter { it in labels && it != "safety_advice" }.take(2)
        val signs = cats.joinToString("; ") { if (lang == "en") labels[it]!!.first else labels[it]!!.second }
        val say = if (lang == "hi") {
            val head = if (level == "stop") "रुकिए! यह कॉल ${top.nameHi} स्कैम जैसी लग रही है।" else "सावधान! यह कॉल संदिग्ध है।"
            "$head $signs. ${top.truthHi} ${top.actionHi}"
        } else {
            val head = if (level == "stop") "Stop! This call looks like a ${top.nameEn} scam." else "Caution: this call looks suspicious."
            "$head $signs. ${top.truthEn} ${top.actionEn}"
        }
        return Verdict(true, top.id, stages.lastOrNull() ?: "contact", if (level == "stop") 0.9 else 0.6, emptyList(), say,
            if (level == "stop") "hang_up" else "verify_independently", "fallback")
    }
}
