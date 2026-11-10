package kavach.core

/** Port of kavach_core/lexicon.py (scam-cue phrase matcher). */
data class CueHit(val category: String, val phrase: String, val start: Int, val end: Int, val stage: String, val weight: Double)

class CueCategory(
    val name: String,
    val stage: String,
    val weight: Double,
    val negatable: Boolean,
    val labelEn: String,
    val labelHi: String,
) {
    val phrases = ArrayList<List<String>>()
    val rawPhrases = ArrayList<String>()
}

class Lexicon(cuesJson: String) {
    val negators: Set<String>
    val stages: List<String>
    val categories = LinkedHashMap<String, CueCategory>()

    init {
        val d = MiniJson.parse(cuesJson).obj()
        negators = d["negators"].strList().map { Normalize.normalize(it) }.toSet()
        stages = d["stages"].strList()
        for ((name, cv) in d["categories"].obj()) {
            val c = cv.obj()
            val cat = CueCategory(
                name, c["stage"] as String, (c["weight"] as Double), c["negatable"] as Boolean,
                c["label_en"] as String, c["label_hi"] as String,
            )
            for (ph in c["phrases"].strList()) {
                val toks = ArrayList<String>()
                for (t in ph.split(" ")) {
                    when {
                        t == "~" -> toks.add("~")
                        t.endsWith("*") -> toks.add(Normalize.normalize(t.dropLast(1)) + "*")
                        else -> toks.addAll(Normalize.normalize(t).split(" "))
                    }
                }
                val clean = toks.filter { it.isNotEmpty() }
                if (clean.isNotEmpty() && clean[0] != "~") {
                    cat.phrases.add(clean)
                    cat.rawPhrases.add(ph)
                }
            }
            categories[name] = cat
        }
    }

    val stageOf: Map<String, String> get() = categories.mapValues { it.value.stage }

    private fun negated(toks: List<String>, s: Int, e: Int): Boolean {
        val lo = maxOf(0, s - NEG_BEFORE)
        val hi = minOf(toks.size, e + NEG_AFTER)
        for (k in lo until hi) if (toks[k] in negators) return true
        return false
    }

    fun matchTokens(toks: List<String>): List<CueHit> {
        val positions = HashMap<String, MutableList<Int>>()
        toks.forEachIndexed { i, t -> positions.getOrPut(t) { ArrayList() }.add(i) }
        val prefixCache = HashMap<String, List<Int>>()
        fun starts(first: String): List<Int> {
            if (first.endsWith("*") && first.length > 1) {
                return prefixCache.getOrPut(first) {
                    val pre = first.dropLast(1)
                    positions.filterKeys { it.startsWith(pre) }.values.flatten().sorted()
                }
            }
            return positions[first] ?: emptyList()
        }

        val hits = ArrayList<CueHit>()
        for (cat in categories.values) {
            val covered = HashSet<Int>()
            for (pi in cat.phrases.indices) {
                val pat = cat.phrases[pi]
                for (i in starts(pat[0])) {
                    if (i in covered) continue
                    val e = matchAt(pat, toks, i, 0)
                    if (e < 0) continue
                    if (cat.negatable && negated(toks, i, e)) continue
                    hits.add(CueHit(cat.name, cat.rawPhrases[pi], i, e, cat.stage, cat.weight))
                    for (k in i until e) covered.add(k)
                }
            }
        }
        // "never tell your OTP to anyone" is the bank's safety advice, not a scammer's "tell no one":
        // an isolation hit whose words overlap a safety-advice hit is dropped.
        val safe = hits.filter { it.category == "safety_advice" }.map { it.start to it.end }
        val kept = if (safe.isEmpty()) hits else hits.filter { h -> !(h.category == "isolation" && safe.any { (s, e) -> h.start < e && s < h.end }) }
        return kept.sortedWith(compareBy<CueHit>({ it.start }, { it.category }))
    }

    fun match(text: String): List<CueHit> = matchTokens(Normalize.tokens(text))

    fun categoriesIn(text: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (h in match(text)) out[h.category] = (out[h.category] ?: 0) + 1
        return out
    }

    companion object {
        const val GAP_MAX = 4
        const val NEG_BEFORE = 2
        const val NEG_AFTER = 2

        private fun tokMatch(p: String, tok: String): Boolean =
            if (p.endsWith("*") && p.length > 1) tok.startsWith(p.dropLast(1)) else tok == p

        /** End index (exclusive) of a match of pat[j:] starting at toks[i], or -1. Greedy-shortest. */
        fun matchAt(pat: List<String>, toks: List<String>, i: Int, j: Int): Int {
            if (j == pat.size) return i
            val p = pat[j]
            if (p == "~") {
                for (g in 0..GAP_MAX) {
                    if (i + g > toks.size) break
                    val e = matchAt(pat, toks, i + g, j + 1)
                    if (e >= 0) return e
                }
                return -1
            }
            if (i >= toks.size || !tokMatch(p, toks[i])) return -1
            return matchAt(pat, toks, i + 1, j + 1)
        }
    }
}
