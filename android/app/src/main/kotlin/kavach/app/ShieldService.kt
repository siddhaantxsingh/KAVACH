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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Ui.ensureChannel(this, "shield", "KAVACH protection", android.app.NotificationManager.IMPORTANCE_LOW)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Ui.ongoing(this, "shield", "KAVACH is protecting this phone", "Messages, links, apps and screen sharing are checked on this phone only.",
            android.R.drawable.ic_lock_idle_lock, pi)
        if (Build.VERSION.SDK_INT >= 34) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(3, n)
        inspector = AppInspector(this)
        KavachRuntime.ensureLoaded(this) { core -> ShieldHub.attach(core); ShieldHub.startLink(this); ShieldHub.startAdvisor(this) }
        registerReceiver(pkgReceiver, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") })
        if (hasUsageAccess(this)) handler.post(poll)
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
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (remote) { remoteActive[pkg] = e.timeStamp; ShieldHub.onRemoteAccessActive(this, pkg) }
                    else if (pkg in inspector.paymentApps) onPaymentForeground(pkg, e.timeStamp)
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
        handler.removeCallbacks(poll)
        try { unregisterReceiver(pkgReceiver) } catch (_: Throwable) { }
        ShieldHub.stopLink()
        super.onDestroy()
    }

    companion object {
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
