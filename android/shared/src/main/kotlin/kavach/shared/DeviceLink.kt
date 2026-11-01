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
class DeviceLink(context: Context, private val onMessage: (Map<String, Any?>) -> Unit, private val onState: (String) -> Unit) {
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
                    server = a.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                    onState("waiting for Guardian")
                    val s = server!!.accept()
                    server?.close()
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
                val s = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
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

    @Synchronized fun send(msg: Map<String, Any?>): Boolean {
        val o = out ?: return false
        return try { o.write((MiniJson.write(msg) + "\n").toByteArray(Charsets.UTF_8)); o.flush(); true }
        catch (t: Throwable) { Log.w(TAG, "send failed", t); false }
    }

    fun sendEvent(ev: KcEvent) = send(linkedMapOf("type" to "event", "t" to ev.t, "channel" to ev.channel, "kind" to ev.kind, "weight" to ev.weight, "detail" to ev.detail))

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
    }

    companion object {
        private const val TAG = "DeviceLink"
        const val SERVICE_NAME = "KAVACH-link"
        val SERVICE_UUID: UUID = UUID.fromString("6b1c2a0e-6c8f-4f6e-9d3e-4b415641434b")

        fun eventFrom(m: Map<String, Any?>, now: Double): KcEvent? {
            if (m["type"] != "event") return null
            return KcEvent(now, m["channel"] as? String ?: return null, m["kind"] as? String ?: return null,
                (m["weight"] as? Double) ?: 1.0, m["detail"] as? String ?: "")
        }
    }
}
