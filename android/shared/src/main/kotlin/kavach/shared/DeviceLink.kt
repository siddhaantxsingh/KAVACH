package kavach.shared

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kavach.core.KcEvent
import kavach.core.KillChain
import kavach.core.MiniJson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

/**
 * Offline link between the Guardian (call listener) and the user's phone over Bluetooth
 * RFCOMM. No internet, no pairing PIN prompt beyond normal one-time pairing. Messages are
 * newline-delimited JSON objects:
 *   {"type":"event","t":..,"channel":"link","kind":"dangerous_link","weight":2.5,"detail":"host"}
 *   {"type":"level","level":"stop","say":"...","scam_type":"digital_arrest"}
 * The user's phone runs the server; the Guardian connects to it (it was paired once).
 */
@SuppressLint("MissingPermission") // callers check BLUETOOTH_CONNECT before starting the link
class DeviceLink(context: Context, private val onMessage: (Map<String, Any?>) -> Unit, private val onState: (String) -> Unit) : KavachLink {
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var running = false
    @Volatile var connectedTo: String? = null
        private set

    val available: Boolean get() = adapter?.isEnabled == true

    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    /** User's phone: accept one Guardian at a time, forever, until stop(). */
    fun startServer() {
        val a = adapter ?: run { onState("bluetooth unavailable"); return }
        running = true
        Thread({
            while (running) {
                try {
                    server = a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID) // authenticated + encrypted: phones must be paired
                    onState("waiting for Guardian")
                    val s = server!!.accept()
                    server?.close()
                    // only phones the user has paired in Bluetooth settings may talk to KAVACH
                    if (bondedDevices().none { it.address == s.remoteDevice?.address }) { s.close(); onState("refused an unpaired device"); continue }
                    attach(s)
                } catch (t: Throwable) {
                    if (running) { Log.w(TAG, "server error", t); onState("link error: ${t.message}"); Thread.sleep(2000) }
                }
            }
        }, "kavach-link-server").start()
    }

    /** Guardian: connect to the paired user phone. */
    fun connect(device: BluetoothDevice) {
        running = true
        Thread({
            try {
                adapter?.cancelDiscovery()
                val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID) // authenticated + encrypted
                onState("connecting to ${device.name}")
                s.connect()
                attach(s)
            } catch (t: Throwable) {
                Log.w(TAG, "connect failed", t)
                onState("could not connect: ${t.message}")
            }
        }, "kavach-link-client").start()
    }

    private fun attach(s: BluetoothSocket) {
        socket = s
        out = s.outputStream
        connectedTo = s.remoteDevice?.name ?: "device"
        onState("connected to $connectedTo")
        try {
            BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)).use { r ->
                while (running) {
                    val line = r.readLine() ?: break
                    if (line.length > MAX_LINE) { Log.w(TAG, "dropped an oversized message (${line.length} chars)"); continue }
                    try { (MiniJson.parse(line) as? Map<*, *>)?.let { m -> @Suppress("UNCHECKED_CAST") onMessage(m as Map<String, Any?>) } }
                    catch (t: Throwable) { Log.w(TAG, "bad message: $line") }
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "link read error", t)
        } finally {
            connectedTo = null; out = null
            try { s.close() } catch (_: Throwable) { }
            onState("disconnected")
        }
    }

    @Synchronized override fun send(msg: Map<String, Any?>): Boolean {
        val o = out ?: return false
        return try { o.write((MiniJson.write(msg) + "\n").toByteArray(Charsets.UTF_8)); o.flush(); true }
        catch (t: Throwable) { Log.w(TAG, "send failed", t); false }
    }

    override fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
    }

    companion object {
        private const val TAG = "DeviceLink"
        const val SERVICE_NAME = "KAVACH-link"
        val SERVICE_UUID: UUID = UUID.fromString("6b1c2a0e-6c8f-4f6e-9d3e-4b415641434b")

        /** Longest accepted message line; real messages are well under 1 KB. */
        const val MAX_LINE = 8192
        private val ACCEPTED_KINDS = KillChain.CROSS_KINDS + setOf(KillChain.CALL_START, KillChain.CALL_END, KillChain.PAYMENT_OPEN)

        /** Events from the other phone are evidence, never commands: only the kinds a phone forwards are
         *  accepted, weights are clamped and the detail text is cut, so a bad or replayed message cannot
         *  inject arbitrary speech cues or outweigh what this phone hears itself. */
        fun eventFrom(m: Map<String, Any?>, now: Double): KcEvent? {
            if (m["type"] != "event") return null
            val kind = m["kind"] as? String ?: return null
            if (kind !in ACCEPTED_KINDS) return null
            val w = ((m["weight"] as? Double) ?: 1.0).coerceIn(0.0, 3.0)
            return KcEvent(now, (m["channel"] as? String ?: return null).take(16), kind, w, (m["detail"] as? String ?: "").take(120))
        }
    }
}
