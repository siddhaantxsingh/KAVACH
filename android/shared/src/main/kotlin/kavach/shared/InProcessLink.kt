package kavach.shared

import android.util.Log
import java.util.concurrent.Executors

/**
 * One app, two parts: the call listener (Guardian) and the protection hub (Shield) live in the same
 * KAVACH app. They exchange exactly the JSON messages they would send over Bluetooth (two phones)
 * or over a broadcast (two apps), but in memory. Every message carries "same_device": true.
 */
object InProcessBus {
    const val SHIELD = "shield"
    const val GUARDIAN = "guardian"

    private class Side(val onMessage: (Map<String, Any?>) -> Unit, val onState: (String) -> Unit)

    private val sides = HashMap<String, Side>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "kavach-inproc").apply { isDaemon = true } }

    fun other(side: String) = if (side == SHIELD) GUARDIAN else SHIELD

    @Synchronized
    fun register(side: String, onMessage: (Map<String, Any?>) -> Unit, onState: (String) -> Unit) {
        sides[side] = Side(onMessage, onState)
        val peer = sides[other(side)]
        if (peer != null) {
            exec.execute { onState("connected (same app)"); peer.onState("connected (same app)") }
        } else {
            exec.execute { onState("waiting for the other part of KAVACH") }
        }
    }

    @Synchronized
    fun unregister(side: String, ifSame: ((Map<String, Any?>) -> Unit)? = null) {
        val s = sides[side] ?: return
        if (ifSame != null && s.onMessage !== ifSame) return
        sides.remove(side)
        sides[other(side)]?.let { p -> exec.execute { p.onState("the other part of KAVACH stopped") } }
    }

    @Synchronized
    fun isConnected(side: String): Boolean = sides.containsKey(side) && sides.containsKey(other(side))

    fun deliver(to: String, msg: Map<String, Any?>): Boolean {
        val target = synchronized(this) { sides[to] } ?: return false
        exec.execute {
            try { target.onMessage(msg) } catch (t: Throwable) { Log.w("InProcessBus", "handler failed", t) }
        }
        return true
    }
}

class InProcessLink(
    private val side: String,
    private val onMessage: (Map<String, Any?>) -> Unit,
    onState: (String) -> Unit,
) : KavachLink {
    init { InProcessBus.register(side, onMessage, onState) }

    override fun send(msg: Map<String, Any?>): Boolean {
        val payload = LinkedHashMap(msg).apply { put("same_device", true) }
        return InProcessBus.deliver(InProcessBus.other(side), payload)
    }

    override fun stop() = InProcessBus.unregister(side, onMessage)
}
