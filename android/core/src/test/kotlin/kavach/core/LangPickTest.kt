package kavach.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The Kotlin language picker gives the same scores and the same sentence-by-sentence choices as
 *  kavach_core/langpick.py (vectors: assets/parity/langpick.json, checked on the Python side by tests/test_langpick.py). */
class LangPickTest {
    @Test
    fun matchesPython() {
        val assets = listOf(File("../../assets"), File("../assets"), File("assets")).first { File(it, "parity/langpick.json").exists() }
        val lp = LangPick.fromText(File(assets, "langid/en_words.txt").readText(), File(assets, "langid/hi_words.txt").readText())
        val cases = MiniJson.parse(File(assets, "parity/langpick.json").readText()) as List<*>
        for (c in cases) {
            val m = c as Map<*, *>
            val en = m["en"] as String; val hi = m["hi"] as String
            assertEquals("score: $en | $hi", (m["score"] as Number).toDouble(), lp.score(en, hi), 1e-9)
            assertEquals("pick: $en | $hi", m["pick"], lp.pick(en, hi))
        }
    }
}
