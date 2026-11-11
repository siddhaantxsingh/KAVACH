package kavach.netwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import kavach.core.DnsWatch
import kavach.core.DomainModel
import kavach.core.KcEvent
import kavach.core.LinearModel
import kavach.core.MiniJson
import kavach.core.UrlChecker
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * DNS-only local VPN. Only traffic to a private "DNS server" address is routed into this app;
 * all other traffic flows normally and is never seen. For each lookup we learn WHICH app asked
 * (getConnectionOwnerUid) and WHAT name, forward the query unchanged to the phone's own DNS
 * server, and judge name + answer (NXDOMAIN) with kavach.core.DnsWatch. Verdicts go to KAVACH
 * Shield over the same-phone link as kind "malicious_domain".
 *
 * Limits (documented in docs/network_watch.md): apps that use their own DNS-over-HTTPS, or a
 * phone set to "Private DNS: strict" (DNS-over-TLS), bypass this; IPv4 DNS only.
 */
class DnsVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var reader: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)
    private lateinit var watch: DnsWatch
    private val outLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        watch = DnsWatch(loadDomainModel(this), loadUrlChecker(this))
        val b = Builder().setSession("KAVACH Network Watch")
            .addAddress(TUN_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32) // ONLY the fake DNS server is routed through us
            .setBlocking(true)
        try { b.addDisallowedApplication(packageName) } catch (_: Throwable) { }
        tun = b.establish() ?: run { stopSelf(); return START_NOT_STICKY }
        running = true
        // a running VPN is kept alive by the system; the notification just keeps the user informed
        getSystemService(NotificationManager::class.java).notify(1, notification("Checking which websites apps contact. Only website names are checked, on this phone."))
        reader = Thread({ loop() }, "dns-tun").also { it.start() }
        state = "on"
        return START_STICKY
    }

    private fun loop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try { input.read(buf) } catch (t: Throwable) { if (running) Log.w(TAG, "read", t); break }
            if (n <= 0) continue
            val pkt = buf.copyOf(n)
            try { handle(pkt, output) } catch (t: Throwable) { Log.w(TAG, "packet", t) }
        }
    }

    /** IPv4/UDP/53 only. */
    private fun handle(p: ByteArray, out: FileOutputStream) {
        if (p.size < 28 || (p[0].toInt() shr 4) != 4) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p[9].toInt() != 17 || p.size < ihl + 8) return
        val dstPort = u16(p, ihl + 2)
        if (dstPort != 53) return
        val srcIp = p.copyOfRange(12, 16)
        val dstIp = p.copyOfRange(16, 20)
        val srcPort = u16(p, ihl)
        val payload = p.copyOfRange(ihl + 8, p.size)
        val q = DnsWatch.parse(payload) ?: return
        val pkg = ownerPackage(srcIp, srcPort, dstIp)
        pool.execute {
            val resp = forward(payload) ?: return@execute
            synchronized(outLock) { try { out.write(udpPacket(dstIp, srcIp, 53, srcPort, resp)) } catch (_: Throwable) { } }
            val r = DnsWatch.parse(resp)
            val evs = synchronized(watch) { watch.observe(SystemClock.elapsedRealtime() / 1000.0, pkg, q.name, r?.rcode == 3) }
            lookups++
            synchronized(apps) {
                val a = apps.getOrPut(pkg) { AppStat(pkg) }
                a.lookups++; a.lastHost = q.name; a.lastAt = System.currentTimeMillis()
                if (evs.isNotEmpty()) a.flagged++
            }
            for (ev in evs) onVerdict(ev, pkg, q.name)
        }
    }

    private fun ownerPackage(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray): String = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
            InetSocketAddress(InetAddress.getByAddress(dstIp), 53))
        packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
    } catch (t: Throwable) { "unknown-app" }

    /** Forward to the phone's normal DNS server over a socket that bypasses this VPN. */
    private fun forward(query: ByteArray): ByteArray? {
        val server = upstream() ?: return null
        return try {
            DatagramSocket().use { s ->
                protect(s)
                s.soTimeout = 4000
                s.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
                val rb = ByteArray(4096)
                val dp = DatagramPacket(rb, rb.size)
                s.receive(dp)
                rb.copyOf(dp.length)
            }
        } catch (t: Throwable) { null }
    }

    private fun upstream(): InetAddress? {
        val cm = getSystemService(ConnectivityManager::class.java)
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            cm.getLinkProperties(n)?.dnsServers?.firstOrNull { it is Inet4Address }?.let { return it }
        }
        return null // no fallback to a public resolver: we never send lookups somewhere new
    }

    private fun onVerdict(ev: KcEvent, pkg: String, host: String) {
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        recent.add(0, Finding(System.currentTimeMillis(), label, host, ev.detail)); while (recent.size > 30) recent.removeAt(recent.size - 1)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(100 + (host.hashCode() and 0xFFF), Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("\"$label\" contacted a dangerous website")
            .setContentText(ev.detail).setStyle(Notification.BigTextStyle().bigText(ev.detail +
                ". Do not log in or pay inside \"$label\". If you don't recognise this app, remove it (its data on this phone is deleted with it)."))
            .setAutoCancel(true).build())
        // tell KAVACH Shield (same-phone link, signature permission); it adds this to the kill chain
        val msg = linkedMapOf<String, Any?>("type" to "event", "t" to ev.t, "channel" to ev.channel, "kind" to ev.kind,
            "weight" to ev.weight, "detail" to "$label: ${ev.detail}", "source" to "netwatch", "same_device" to true)
        try {
            sendBroadcast(Intent(LINK_ACTION).setPackage(SHIELD).putExtra("json", MiniJson.write(msg)).putExtra("from", packageName), LINK_PERMISSION)
        } catch (t: Throwable) { Log.w(TAG, "link", t) }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "KAVACH Network Watch", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, NetWatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("KAVACH Network Watch is on")
            .setContentText(text).setContentIntent(pi).setOngoing(true).build()
    }

    private fun shutdown() {
        running = false
        try { tun?.close() } catch (_: Throwable) { }
        tun = null
        state = "off"
        try { getSystemService(NotificationManager::class.java).cancel(1) } catch (_: Throwable) { }
    }

    override fun onRevoke() { shutdown(); super.onRevoke() }
    override fun onDestroy() { shutdown(); pool.shutdownNow(); super.onDestroy() }

    companion object {
        private const val TAG = "KavachDnsVpn"
        const val ACTION_STOP = "kavach.netwatch.STOP"
        private const val CH = "netwatch"
        private const val TUN_ADDR = "10.111.222.2"
        private const val DNS_ADDR = "10.111.222.1"
        /** the one KAVACH app (package id kept from the former Guardian app) */
        private const val SHIELD = "io.kavach.guardian"
        private const val LINK_ACTION = "io.kavach.LINK"
        private const val LINK_PERMISSION = "io.kavach.permission.LINK"
        @Volatile var state = "off"
        @Volatile var lookups = 0L
        data class Finding(val at: Long, val app: String, val host: String, val detail: String)
        class AppStat(val pkg: String) { var lookups = 0; var flagged = 0; var lastHost = ""; var lastAt = 0L }
        val recent: MutableList<Finding> = java.util.Collections.synchronizedList(ArrayList())
        val apps = HashMap<String, AppStat>()

        /** Tell the KAVACH app about something Network Watch found (signature-protected broadcast). */
        fun report(ctx: Context, msg: Map<String, Any?>) {
            try {
                ctx.sendBroadcast(Intent(LINK_ACTION).setPackage(SHIELD).putExtra("json", MiniJson.write(msg + mapOf("source" to "netwatch", "same_device" to true)))
                    .putExtra("from", ctx.packageName), LINK_PERMISSION)
            } catch (t: Throwable) { Log.w(TAG, "link", t) }
        }

        fun loadDomainModel(ctx: Context) = DomainModel(LinearModel.load(
            ctx.assets.open("models/domain_model.json").use { it.readBytes().toString(Charsets.UTF_8) },
            ctx.assets.open("models/domain_model.bin").use { it.readBytes() }, null))

        fun loadUrlChecker(ctx: Context) = UrlChecker(
            ctx.assets.open("genuine_domains.json").use { it.readBytes().toString(Charsets.UTF_8) },
            LinearModel.load(ctx.assets.open("models/url_model.json").use { it.readBytes().toString(Charsets.UTF_8) },
                ctx.assets.open("models/url_model.bin").use { it.readBytes() }, null))

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

        /** IPv4 + UDP packet; UDP checksum 0 (allowed for IPv4), IPv4 header checksum computed. */
        fun udpPacket(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
            val len = 28 + payload.size
            val p = ByteArray(len)
            p[0] = 0x45; p[2] = (len shr 8).toByte(); p[3] = len.toByte(); p[8] = 64; p[9] = 17
            System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4)
            var sum = 0
            for (i in 0 until 20 step 2) sum += u16(p, i)
            while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            val cs = sum.inv() and 0xFFFF
            p[10] = (cs shr 8).toByte(); p[11] = cs.toByte()
            p[20] = (sport shr 8).toByte(); p[21] = sport.toByte(); p[22] = (dport shr 8).toByte(); p[23] = dport.toByte()
            val ul = 8 + payload.size
            p[24] = (ul shr 8).toByte(); p[25] = ul.toByte()
            System.arraycopy(payload, 0, p, 28, payload.size)
            return p
        }
    }
}
