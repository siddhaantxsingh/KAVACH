package kavach.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Guardian placement check (port of kavach_core/audio_check.py, parity-tested): estimates the
 * speech-to-noise ratio of the last ~5 s of microphone audio from 20 ms frame powers
 * (90th percentile = speech + noise, 10th percentile = room noise).
 * estimate >= 18 dB "good", >= 12 dB "move_closer", below "too_noisy" (the estimate reads ~5 dB high;
 * calibrated in bench/e2e_noise.py).
 */
object AudioCheck {
    fun framePowers(x: FloatArray, sr: Int, frameMs: Int = 20): List<Double> {
        val n = max(1, sr * frameMs / 1000)
        val out = ArrayList<Double>()
        var i = 0
        while (i + n <= x.size) {
            var s = 0.0
            for (k in i until i + n) s += x[k].toDouble() * x[k].toDouble()
            out.add(s / n); i += n
        }
        return out
    }

    private fun pct(sorted: List<Double>, q: Double): Double = sorted[min(sorted.size - 1, (q * (sorted.size - 1)).toInt())]

    fun estimateFromPowers(powers: List<Double>): Double {
        if (powers.isEmpty()) return 0.0
        val p = powers.sorted()
        val quiet = max(pct(p, 0.10), 1e-12)
        val loud = pct(p, 0.90)
        return 10.0 * log10(max(loud - quiet, 1e-12) / quiet)
    }

    fun estimateSnrDb(x: FloatArray, sr: Int): Double = estimateFromPowers(framePowers(x, sr))

    fun verdict(snrDb: Double): String = if (snrDb >= 18.0) "good" else if (snrDb >= 12.0) "move_closer" else "too_noisy"

    /** Rolling window over the live microphone stream (no audio kept, only 250 frame powers). */
    class Live(private val sr: Int, private val frames: Int = 250) {
        private val ring = ArrayDeque<Double>()
        private var acc = 0.0
        private var fill = 0
        private val n = sr / 50
        fun add(samples: FloatArray) {
            for (s in samples) {
                acc += s.toDouble() * s.toDouble(); fill++
                if (fill == n) { ring.addLast(acc / n); if (ring.size > frames) ring.removeFirst(); acc = 0.0; fill = 0 }
            }
        }
        fun ready(): Boolean = ring.size >= frames
        fun snrDb(): Double = estimateFromPowers(ring.toList())
    }
}
