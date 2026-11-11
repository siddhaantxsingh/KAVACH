package kavach.netwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Check my Wi-Fi": a user-started scan of the home network this phone is on (the /24 around the
 * phone's own address, Wi-Fi or Ethernet only, never mobile data). For every address it tries a TCP
 * connection to a short list of services that attackers abuse on home and small-office networks
 * (remote debugging of TV boxes, Telnet on cameras and routers, open file shares, remote desktop,
 * router remote management). Nothing is sent except those connection attempts; no data is read.
 */
object LanScan {
    data class Service(val port: Int, val name: String, val level: String, val whyEn: String, val whyHi: String, val fixEn: String, val fixHi: String)
    data class Device(val ip: String, val isRouter: Boolean, val isThisPhone: Boolean, val open: List<Service>) {
        val level: String get() = when {
            open.any { it.level == "urgent" } -> "urgent"
            open.any { it.level == "caution" } -> "caution"
            else -> "info"
        }
    }
    data class Result(val network: String, val scanned: Int, val devices: List<Device>, val seconds: Double, val error: String? = null)

    val SERVICES = listOf(
        Service(5555, "Android remote debugging (ADB)", "urgent",
            "Anyone on this Wi-Fi can take full control of this device (install apps, read the screen). Malware spreads this way between TV boxes.",
            "इस Wi-Fi पर कोई भी इस डिवाइस को पूरी तरह कंट्रोल कर सकता है (ऐप डालना, स्क्रीन देखना)। TV बॉक्स में मैलवेयर ऐसे ही फैलता है।",
            "On that device: Settings → Developer options → turn off 'ADB debugging' / 'Network debugging'.",
            "उस डिवाइस पर: Settings → Developer options → 'ADB debugging' / 'Network debugging' बंद करें।"),
        Service(23, "Telnet", "urgent",
            "An old login service with no encryption. Botnets like Mirai break into cameras and routers through it with default passwords.",
            "बिना एन्क्रिप्शन वाली पुरानी लॉगिन सेवा। Mirai जैसे बॉटनेट डिफ़ॉल्ट पासवर्ड से कैमरों और राउटरों में इसी से घुसते हैं।",
            "Turn off Telnet in the device's settings, change its default password and update its firmware.",
            "डिवाइस की सेटिंग में Telnet बंद करें, डिफ़ॉल्ट पासवर्ड बदलें और फ़र्मवेयर अपडेट करें।"),
        Service(3389, "Remote Desktop (RDP)", "caution",
            "This computer accepts remote logins. Scammers who get a password can control it from anywhere on the network.",
            "यह कंप्यूटर रिमोट लॉगिन लेता है। पासवर्ड मिलने पर कोई भी इसे नेटवर्क से कंट्रोल कर सकता है।",
            "Turn off Remote Desktop if you don't use it, and use a strong password.",
            "ज़रूरत न हो तो Remote Desktop बंद करें, और मज़बूत पासवर्ड रखें।"),
        Service(445, "Windows file sharing (SMB)", "caution",
            "Shared folders are open to this network. Ransomware spreads between computers through this service.",
            "शेयर किए फ़ोल्डर इस नेटवर्क पर खुले हैं। रैनसमवेयर इसी सेवा से कंप्यूटरों में फैलता है।",
            "Share only the folders you need, with a password, and keep Windows updated.",
            "सिर्फ़ ज़रूरी फ़ोल्डर पासवर्ड के साथ शेयर करें और Windows अपडेट रखें।"),
        Service(7547, "Router remote management (TR-069)", "caution",
            "The internet provider's remote-management port is reachable. Old router firmware has been hijacked through it.",
            "इंटरनेट कंपनी का रिमोट-मैनेजमेंट पोर्ट खुला है। पुराने राउटर फ़र्मवेयर इसी से हैक हुए हैं।",
            "Update the router firmware; ask your provider whether remote management is needed.",
            "राउटर का फ़र्मवेयर अपडेट करें; अपनी कंपनी से पूछें कि रिमोट मैनेजमेंट ज़रूरी है या नहीं।"),
        Service(21, "FTP file server", "caution",
            "Files can be read with a password that travels unencrypted over the network.",
            "फ़ाइलें ऐसे पासवर्ड से खुलती हैं जो नेटवर्क पर बिना एन्क्रिप्शन के जाता है।",
            "Turn off FTP if not needed, or use a strong password.",
            "ज़रूरत न हो तो FTP बंद करें, नहीं तो मज़बूत पासवर्ड रखें।"),
        Service(554, "Camera video stream (RTSP)", "caution",
            "A camera's live video can be opened from this network. Many cameras ship without a password on this stream.",
            "इस नेटवर्क से कैमरे का लाइव वीडियो खोला जा सकता है। कई कैमरों में इस पर पासवर्ड नहीं होता।",
            "Set a camera password and update the camera app/firmware.",
            "कैमरे का पासवर्ड सेट करें और उसका ऐप/फ़र्मवेयर अपडेट करें।"),
        Service(22, "SSH remote login", "info",
            "Encrypted remote login. Safe if the password is strong.",
            "एन्क्रिप्टेड रिमोट लॉगिन। मज़बूत पासवर्ड हो तो सुरक्षित।",
            "Make sure the default password has been changed.",
            "देख लें कि डिफ़ॉल्ट पासवर्ड बदला जा चुका है।"),
        Service(80, "Web settings page (HTTP)", "info",
            "The device has a settings page on this network (routers, printers, cameras).",
            "इस डिवाइस का सेटिंग पेज इस नेटवर्क पर है (राउटर, प्रिंटर, कैमरा)।",
            "Change the default admin password on that page.",
            "उस पेज पर डिफ़ॉल्ट एडमिन पासवर्ड बदल दें।"),
        Service(8080, "Web settings page (alternate port)", "info",
            "A second web page on this device, often a camera or router admin page.",
            "इस डिवाइस का दूसरा वेब पेज, अक्सर कैमरा या राउटर का एडमिन पेज।",
            "Change the default admin password on that page.",
            "उस पेज पर डिफ़ॉल्ट एडमिन पासवर्ड बदल दें।"),
    )

    /** IPv4 address and prefix of the phone on its Wi-Fi/Ethernet network, plus the router. */
    private fun localNet(ctx: Context): Triple<ByteArray, Int, ByteArray?>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.address
            return Triple(la.address.address, la.prefixLength, gw)
        }
        return null
    }

    fun scan(ctx: Context, onProgress: (done: Int, total: Int) -> Unit): Result {
        val t0 = System.currentTimeMillis()
        val net = localNet(ctx) ?: return Result("-", 0, emptyList(), 0.0, "not on Wi-Fi")
        val (me, prefix, gw) = net
        // never more than the 254 neighbours of a /24, even on bigger networks
        val base = me.copyOf().also { it[3] = 0 }
        val hosts = (1..254).map { byteArrayOf(base[0], base[1], base[2], it.toByte()) }
        val myIp = ip(me)
        val gwIp = gw?.let { ip(it) }
        val pool = Executors.newFixedThreadPool(64)
        val found = java.util.concurrent.ConcurrentHashMap<String, MutableList<Service>>()
        val done = AtomicInteger(0)
        val total = hosts.size * SERVICES.size
        for (h in hosts) for (s in SERVICES) pool.execute {
            val addr = ip(h)
            if (open(addr, s.port)) found.getOrPut(addr) { java.util.Collections.synchronizedList(ArrayList()) }.add(s)
            val d = done.incrementAndGet()
            if (d % 64 == 0 || d == total) onProgress(d, total)
        }
        pool.shutdown()
        pool.awaitTermination(90, TimeUnit.SECONDS)
        val devices = found.map { (addr, svcs) -> Device(addr, addr == gwIp, addr == myIp, svcs.sortedBy { SERVICES.indexOf(it) }) }
            .sortedWith(compareBy({ rank(it.level) }, { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }))
        return Result("${ip(base)}/24" + if (prefix < 24) " (of /$prefix)" else "", hosts.size, devices, (System.currentTimeMillis() - t0) / 1000.0)
    }

    private fun rank(l: String) = when (l) { "urgent" -> 0; "caution" -> 1; else -> 2 }
    private fun ip(b: ByteArray) = b.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun open(host: String, port: Int): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), 300); true }
    } catch (_: Throwable) { false }
}
