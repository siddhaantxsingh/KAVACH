package kavach.guardian

import android.content.Context
import android.os.SystemClock
import kavach.core.MiniJson
import kavach.core.obj2
import kotlin.concurrent.thread

/**
 * Replays a scripted call transcript (assets/demo/<name>.json) in real time, through exactly
 * the same engine path as live ASR. Used for rehearsal, for UI testing without ASR models,
 * and as the fallback if the room is too noisy on demo day.
 */
class DemoPlayer(ctx: Context, name: String, private val onPartial: (String) -> Unit, private val onFinal: (String, Double) -> Unit) {
    private val lines: List<Pair<Double, String>>
    @Volatile private var running = false
    private var t0 = 0L

    init {
        val d = MiniJson.parse(ctx.assets.open("demo/$name.json").use { it.readBytes().toString(Charsets.UTF_8) }).obj2()
        @Suppress("UNCHECKED_CAST")
        lines = (d["lines"] as List<Any?>).map { val l = it as List<Any?>; (l[0] as Double) to (l[1] as String) }
    }

    fun now(): Double = (SystemClock.elapsedRealtime() - t0) / 1000.0

    fun start() {
        running = true
        t0 = SystemClock.elapsedRealtime()
        thread(name = "kavach-demo") {
            for ((t, text) in lines) {
                // words appear progressively like ASR partials
                val words = text.split(" ")
                val startAt = t - words.size / 2.6
                while (running && now() < startAt) Thread.sleep(50)
                for (i in 1..words.size) {
                    if (!running) return@thread
                    onPartial(words.take(i).joinToString(" "))
                    Thread.sleep(380)
                }
                onFinal(text, now())
            }
        }
    }

    fun stop() { running = false }
}
