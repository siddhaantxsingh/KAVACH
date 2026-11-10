package kavach.core

import kotlin.math.sqrt

/** Port of kavach_core/retrieval.py — on-device retrieval over the scam playbook. */
class PlaybookEntry(val raw: Map<String, Any?>) {
    val id = raw["id"] as String
    val nameEn = raw["name_en"] as String
    val nameHi = raw["name_hi"] as String
    val keyCues = raw["key_cues"].strList()
    val storyEn = raw["story_en"] as String
    val truthEn = raw["truth_en"] as String
    val truthHi = raw["truth_hi"] as String
    val actionEn = raw["action_en"] as String
    val actionHi = raw["action_hi"] as String
    val keywords: List<String> = if (raw["keywords"] != null) raw["keywords"].strList() else emptyList()
}

class Playbook(playbookJson: String) {
    val entries: List<PlaybookEntry>
    val helpline: String
    private val vecs: List<Map<Int, Double>>

    init {
        val d = MiniJson.parse(playbookJson).obj()
        entries = d["scam_types"].arr().map { PlaybookEntry(it.obj()) }
        helpline = d["helpline"].obj()["number"] as String
        vecs = entries.map { Features.vectorize(Normalize.tokens(it.nameEn + " " + it.storyEn), RET_DIM, null).values }
    }

    val typeIds: List<String> get() = entries.map { it.id }

    fun rank(active: Map<String, Double>, text: String = "", k: Int = 3): List<Pair<PlaybookEntry, Double>> {
        val toks = if (text.isNotEmpty()) Normalize.tokens(text) else emptyList()
        val tv = if (text.isNotEmpty()) Features.vectorize(toks, RET_DIM, null).values else emptyMap()
        val words = toks.toSet()
        val scored = entries.indices.map { i ->
            val e = entries[i]
            var overlap = 0.0
            for (c in e.keyCues) overlap += minOf(active[c] ?: 0.0, 3.0) / 3.0
            overlap /= sqrt(e.keyCues.size.toDouble())
            var cos = 0.0
            for ((idx, v) in tv) cos += v * (vecs[i][idx] ?: 0.0)
            val bonus = if (e.keywords.any { it in words }) KEYWORD_BONUS else 0.0
            e to (0.7 * overlap + 0.3 * cos + bonus)
        }
        return scored.sortedByDescending { it.second }.take(k)
    }

    companion object { const val RET_DIM = 1 shl 16; const val KEYWORD_BONUS = 0.3 }
}
