package kavach.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kavach.core.KcEvent
import kavach.core.MiniJson

/** What both apps need from a link, whether it is Bluetooth (two phones) or same-phone. */
interface KavachLink {
    fun send(msg: Map<String, Any?>): Boolean
    fun sendEvent(ev: KcEvent): Boolean =
        send(linkedMapOf("type" to "event", "t" to ev.t, "channel" to ev.channel, "kind" to ev.kind, "weight" to ev.weight, "detail" to ev.detail))
    fun stop()
}

/**
 * Same-phone link: KAVACH Guardian and KAVACH Shield installed on ONE Android phone (the
 * victim's call happens on another phone on speaker, e.g. an iPhone). The two apps exchange
 * the same JSON messages as over Bluetooth, through explicit broadcasts that only apps signed
 * with the same key can send or receive (signature permission io.kavach.permission.LINK).
 * Every message carries "same_device": true so the receiver knows both screens are one screen.
 */
class LocalLink(
    private val ctx: Context,
    private val targetPackage: String,
    private val onMessage: (Map<String, Any?>) -> Unit,
    private val onState: (String) -> Unit,
) : KavachLink {
    private var registered = false
    @Volatile var peerSeen = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getStringExtra(EXTRA_FROM) == ctx.packageName) return
            val raw = i.getStringExtra(EXTRA_JSON) ?: return
            @Suppress("UNCHECKED_CAST")
            val m = try { MiniJson.parse(raw) as? Map<String, Any?> } catch (t: Throwable) { null } ?: return
            // KAVACH Network Watch (optional app) also sends events here; it is not the Guardian
            if (!peerSeen && m["source"] != "netwatch") { peerSeen = true; onState("connected (same phone)") }
            when (m["type"]) {
                "hello" -> send(mapOf("type" to "hello_ack"))
                "hello_ack" -> Unit
                else -> onMessage(m)
            }
        }
    }

    fun start() {
        if (registered) return
        val f = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, PERMISSION, null, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, f, PERMISSION, null)
        registered = true
        onState("same-phone link: waiting for the other KAVACH app")
        send(mapOf("type" to "hello"))
    }

    override fun send(msg: Map<String, Any?>): Boolean = try {
        val payload = LinkedHashMap(msg).apply { put("same_device", true) }
        ctx.sendBroadcast(Intent(ACTION).setPackage(targetPackage).putExtra(EXTRA_JSON, MiniJson.write(payload))
            .putExtra(EXTRA_FROM, ctx.packageName), PERMISSION)
        true
    } catch (t: Throwable) {
        Log.w("LocalLink", "send failed", t); false
    }

    override fun stop() {
        if (registered) try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }
        registered = false
    }

    companion object {
        const val ACTION = "io.kavach.LINK"
        const val PERMISSION = "io.kavach.permission.LINK"
        const val EXTRA_JSON = "json"
        const val EXTRA_FROM = "from"
        const val GUARDIAN_PACKAGE = "io.kavach.guardian"
        const val SHIELD_PACKAGE = "io.kavach.shield"
        /** Value of the Guardian's "link" setting meaning "Shield is on this same phone". */
        const val SAME_PHONE = "local"
    }
}
