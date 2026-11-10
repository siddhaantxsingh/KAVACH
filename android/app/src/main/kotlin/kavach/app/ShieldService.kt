package kavach.app

import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import kavach.shared.KavachRuntime
import kavach.shared.Ui

/**
 * Always-on protection on the user's phone:
 *  - link server for the Guardian;
 *  - App Inspector on every newly installed package;
 *  - Screen-share Guard v0: polls UsageStats (every 2 s, only while the screen is on) to know
 *    whether a remote-access app is active (foreground, or running a foreground service) and
 *    raises the STOP card the moment a payment / banking app comes to the foreground.
 */
class ShieldService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var inspector: AppInspector
    private var lastPoll = System.currentTimeMillis() - 60_000
    private val remoteActive = HashMap<String, Long>() // pkg -> last seen active (ms)
    private val remoteFgs = HashSet<String>()
    private var lastStopFor: String? = null

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            inspector.inspect(pkg)?.let { r ->
                ShieldHub.onAppRisk(c, r)
                if (r.remoteAccess) ShieldHub.onRemoteAccessActive(c, pkg)
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            try { pollUsage() } catch (_: Throwable) { }
            handler.postDelayed(this, 2000)
        }
    }

    // ---- Network Watch: per-app traffic, also while the screen is off (spyware often uploads at night)
    @Volatile private var fgPkg: String? = null
    private var netStats: NetStatsWatch? = null
    private val suspectCache = HashMap<String, Pair<Long, Boolean>>()
    private val netPoll = object : Runnable {
        override fun run() {
            try { pollNet() } catch (_: Throwable) { }
            handler.postDelayed(this, NetStatsWatch.POLL_MS)
        }
    }

    private fun isSuspect(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        suspectCache[pkg]?.let { if (now - it.first < 3600_000L) return it.second }
        val trusted = getSharedPreferences("kavach", Context.MODE_PRIVATE).getStringSet("net_trusted", emptySet()) ?: emptySet()
        val r = if (pkg in trusted) null else inspector.inspect(pkg)
        val recent = try { now - packageManager.getPackageInfo(pkg, 0).firstInstallTime < 7 * 24 * 3600 * 1000L } catch (_: Throwable) { false }
        val sus = r != null && (r.level != "safe" || r.remoteAccess || r.reasons.any { it.startsWith("installed from outside") } || recent)
        suspectCache[pkg] = now to sus
        return sus
    }

    private fun pollNet() {
        val ns = netStats ?: return
        val pmgr = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val fg = if (pmgr.isInteractive) fgPkg else null
        for (d in ns.poll()) ShieldHub.onAppTraffic(this, d.pkg, d.tx.toDouble(), d.rx.toDouble(), d.pkg == fg, isSuspect(d.pkg))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        running = true
        super.onCreate()
        Ui.ensureChannel(this, "shield", "KAVACH protection", android.app.NotificationManager.IMPORTANCE_LOW)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Ui.ongoing(this, "shield", "KAVACH is protecting this phone", "Messages, links, apps and screen sharing are checked on this phone only.",
            android.R.drawable.ic_lock_idle_lock, pi)
        if (Build.VERSION.SDK_INT >= 34) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(3, n)
        inspector = AppInspector(this)
        KavachRuntime.ensureLoaded(this) { core ->
            ShieldHub.attach(core); ShieldHub.startLink(this); ShieldHub.startAdvisor(this); ShieldHub.startCallAwareness(this)
        }
        registerReceiver(pkgReceiver, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") })
        if (hasUsageAccess(this)) {
            handler.post(poll)
            netStats = NetStatsWatch(this)
            handler.post(netPoll)
        }
    }

    private fun pollUsage() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive) return
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val nowMs = System.currentTimeMillis()
        val events = usm.queryEvents(lastPoll, nowMs)
        lastPoll = nowMs
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val remote = pkg in inspector.remoteAccessApps
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_PAUSED -> if (fgPkg == pkg) fgPkg = null
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    fgPkg = pkg
                    if (remote) { remoteActive[pkg] = e.timeStamp; ShieldHub.onRemoteAccessActive(this, pkg) }
                    else if (pkg in inspector.paymentApps) { ShieldHub.onPaymentAppOpened(this, pkg); onPaymentForeground(pkg, e.timeStamp) }
                }
                FOREGROUND_SERVICE_START -> if (remote) { remoteFgs.add(pkg); remoteActive[pkg] = e.timeStamp; ShieldHub.onRemoteAccessActive(this, pkg) }
                FOREGROUND_SERVICE_STOP -> if (remote) remoteFgs.remove(pkg)
            }
        }
    }

    private fun onPaymentForeground(paymentPkg: String, ts: Long) {
        val active = remoteFgs.firstOrNull() ?: remoteActive.entries.firstOrNull { ts - it.value < REMOTE_WINDOW_MS }?.key ?: return
        if (lastStopFor == "$active>$paymentPkg@${ts / 60000}") return
        lastStopFor = "$active>$paymentPkg@${ts / 60000}"
        ShieldHub.onScreenShareRisk(this, active, paymentPkg)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(poll)
        try { unregisterReceiver(pkgReceiver) } catch (_: Throwable) { }
        ShieldHub.stopLink()
        super.onDestroy()
    }

    companion object {
        /** true only while the protection service is actually alive (drives the "Protection on" state) */
        @Volatile var running = false
        const val REMOTE_WINDOW_MS = 10 * 60 * 1000L
        const val FOREGROUND_SERVICE_START = 19 // UsageEvents.Event.FOREGROUND_SERVICE_START (API 29)
        const val FOREGROUND_SERVICE_STOP = 20

        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
