package kavach.core

/**
 * Per-sentence language choice for the "Auto" call language (Python twin: kavach_core/langpick.py).
 *
 * Every sentence is recognised by both whole-sentence speech models. Each model turns speech of the
 * other language into words that are not real words of its own language, so the transcript kept is
 * the one whose words are more often real, common words of its language:
 *
 *   score = (enKnown - hiKnown) + W * (enCommon - hiCommon)
 *
 * score > MARGIN -> English, otherwise Hindi/Hinglish (the safe default). A sentence close to the
 * margin (short: "okay", "haan") keeps the previous sentence's language.
 */
class LangPick(enWords: List<String>, hiWords: List<String>) {
    private val en = enWords.toHashSet()
    private val hi = hiWords.toHashSet()
    private val enC = enWords.take(COMMON).toHashSet()
    private val hiC = hiWords.take(COMMON).toHashSet()
    @Volatile var last = "hi"
        private set

    private fun share(words: List<String>, s: Set<String>): Double =
        if (words.isEmpty()) 0.0 else words.count { it in s }.toDouble() / (words.size + 1)

    fun score(enText: String, hiText: String): Double {
        val e = EN_RE.findAll(enText.lowercase()).map { it.value }.toList()
        val h = HI_RE.findAll(hiText).map { it.value }.toList()
        return (share(e, en) - share(h, hi)) + W * (share(e, enC) - share(h, hiC))
    }

    /** "en" or "hi" for one sentence. */
    fun pick(enText: String, hiText: String): String {
        val s = score(enText, hiText)
        if (kotlin.math.abs(s - MARGIN) < STICKY) return last
        last = if (s > MARGIN) "en" else "hi"
        return last
    }

    fun reset() { last = "hi" }

    companion object {
        const val W = 1.0
        const val MARGIN = 0.2
        const val STICKY = 0.15
        const val COMMON = 200
        private val EN_RE = Regex("[a-z']+")
        private val HI_RE = Regex("[ऀ-ॿ]+")

        fun fromText(enList: String, hiList: String) =
            LangPick(enList.split(Regex("\\s+")).filter { it.isNotEmpty() }, hiList.split(Regex("\\s+")).filter { it.isNotEmpty() })
    }
}
