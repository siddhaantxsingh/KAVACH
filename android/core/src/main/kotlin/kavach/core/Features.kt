package kavach.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Port of kavach_core/features.py: FNV-1a hashed n-grams + linear model. */
object Features {
    private const val FNV_OFFSET = 0x811C9DC5L
    private const val FNV_PRIME = 0x01000193L
    val CHAR_N = intArrayOf(3, 4, 5)
    const val CUE_SCALE = 3.0

    fun fnv1a32(s: String): Long {
        var h = FNV_OFFSET
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * FNV_PRIME) and 0xFFFFFFFFL
        }
        return h
    }

    /** (feature string, token index) pairs; char n-grams are taken over code points. */
    fun featureStrings(toks: List<String>): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(toks.size * 24)
        for ((i, w) in toks.withIndex()) {
            out.add("w|$w" to i)
            val p = " $w "
            val cps = p.codePoints().toArray()
            for (n in CHAR_N) {
                for (k in 0..cps.size - n) out.add(("c|" + String(cps, k, n)) to i)
            }
        }
        for (i in 0 until toks.size - 1) out.add(("b|" + toks[i] + " " + toks[i + 1]) to i)
        return out
    }

    fun cueFeatureStrings(hits: List<CueHit>): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (h in hits) out.add(("k|" + h.category) to h.start)
        val cats = hits.map { it.category }.toSortedSet().toList()
        for (i in cats.indices) for (j in i + 1 until cats.size) out.add(("kk|" + cats[i] + "+" + cats[j]) to -1)
        return out
    }

    class Vec(val values: LinkedHashMap<Int, Double>, val attrib: List<Pair<Int, Int>>)

    fun vectorize(toks: List<String>, dim: Int, hits: List<CueHit>?): Vec {
        val counts = LinkedHashMap<Int, Int>()
        val cueIdx = HashSet<Int>()
        val attrib = ArrayList<Pair<Int, Int>>()
        for ((f, ti) in featureStrings(toks)) {
            val idx = (fnv1a32(f) % dim).toInt()
            counts[idx] = (counts[idx] ?: 0) + 1
            attrib.add(idx to ti)
        }
        if (hits != null) {
            for ((f, ti) in cueFeatureStrings(hits)) {
                val idx = (fnv1a32(f) % dim).toInt()
                counts[idx] = (counts[idx] ?: 0) + 1
                cueIdx.add(idx)
                if (ti >= 0) attrib.add(idx to ti)
            }
        }
        val vec = LinkedHashMap<Int, Double>()
        for ((k, v) in counts) vec[k] = (1.0 + ln(v.toDouble())) * (if (k in cueIdx) CUE_SCALE else 1.0)
        var ss = 0.0
        for (v in vec.values) ss += v * v
        val norm = if (ss > 0) sqrt(ss) else 1.0
        for (k in vec.keys) vec[k] = vec[k]!! / norm
        return Vec(vec, attrib)
    }

    fun sigmoid(z: Double): Double = if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z) / (1.0 + exp(z))
}

/**
 * Hashed linear model. Weights: <name>.bin (float32 little-endian), meta: <name>.json.
 * When meta.cue_features is true the lexicon must be supplied.
 */
class LinearModel(val name: String, val dim: Int, private val weights: FloatArray, val bias: Double, val threshold: Double,
                  private val lexicon: Lexicon?) {

    private fun hits(toks: List<String>) = lexicon?.matchTokens(toks)

    fun logitTokens(toks: List<String>): Double {
        val v = Features.vectorize(toks, dim, hits(toks))
        var z = bias
        for ((k, x) in v.values) z += weights[k].toDouble() * x
        return z
    }

    fun predictProba(text: String): Double = Features.sigmoid(logitTokens(Normalize.tokens(text)))

    /** Top tokens by positive contribution (the "evidence" words shown to the user). */
    fun explain(text: String, topK: Int = 5): List<Pair<String, Double>> {
        val toks = Normalize.tokens(text)
        val v = Features.vectorize(toks, dim, hits(toks))
        val occ = HashMap<Int, Int>()
        for ((idx, _) in v.attrib) occ[idx] = (occ[idx] ?: 0) + 1
        val perTok = DoubleArray(toks.size)
        for ((idx, ti) in v.attrib) perTok[ti] += weights[idx].toDouble() * v.values[idx]!! / occ[idx]!!
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, Double>>()
        for (i in perTok.indices.sortedByDescending { perTok[it] }) {
            if (perTok[i] <= 0 || toks[i] in seen) continue
            seen.add(toks[i]); out.add(toks[i] to perTok[i])
            if (out.size == topK) break
        }
        return out
    }

    companion object {
        fun load(metaJson: String, weightBytes: ByteArray, lexicon: Lexicon?): LinearModel {
            val m = MiniJson.parse(metaJson).obj()
            val dim = (m["dim"] as Double).toInt()
            require(weightBytes.size == dim * 4) { "weight file does not match dim" }
            val fb = ByteBuffer.wrap(weightBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val w = FloatArray(dim)
            fb.get(w)
            val cue = m["cue_features"] as? Boolean ?: false
            require(!cue || lexicon != null) { "model ${m["name"]} needs the cue lexicon" }
            return LinearModel(m["name"] as String, dim, w, m["bias"] as Double, (m["threshold"] as? Double) ?: 0.5, if (cue) lexicon else null)
        }
    }
}
