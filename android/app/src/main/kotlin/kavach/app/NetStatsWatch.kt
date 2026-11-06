package kavach.app

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

/**
 * Per-app traffic counters from NetworkStatsManager (needs only usage access, which Shield
 * already has; NO INTERNET permission). Every poll returns how many bytes each app sent and
 * received since the previous poll. Nothing about the content or destination is visible:
 * only byte counts per app, which is exactly what the NetWatch rules need.
 *
 * Android keeps the counters in coarse buckets; we query a FIXED window that starts before
 * the service started, so the per-uid totals only grow and their difference is the traffic
 * since the last poll. Each query asks the system to refresh its counters first, so the
 * deltas are close to real time (OEM dependent; typically within a poll interval).
 */
class NetStatsWatch(private val ctx: Context) {
    data class Delta(val pkg: String, val tx: Long, val rx: Long)

    private val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    private val start = System.currentTimeMillis() - 2 * 3600 * 1000L
    private var prev: Map<Int, LongArray>? = null

    fun poll(): List<Delta> {
        val now = System.currentTimeMillis()
        val totals = HashMap<Int, LongArray>()
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                @Suppress("DEPRECATION")
                val st = nsm.querySummary(type, null, start, now + 60_000L) ?: continue
                val b = NetworkStats.Bucket()
                while (st.hasNextBucket()) {
                    st.getNextBucket(b)
                    if (b.uid < 10000) continue // system uids
                    val a = totals.getOrPut(b.uid) { LongArray(2) }
                    a[0] += b.txBytes; a[1] += b.rxBytes
                }
                st.close()
            } catch (t: Throwable) { Log.w(TAG, "querySummary($type): $t") }
        }
        val p = prev
        prev = totals
        if (p == null) return emptyList() // first poll only sets the baseline
        val out = ArrayList<Delta>()
        for ((uid, a) in totals) {
            val o = p[uid]
            val tx = a[0] - (o?.get(0) ?: 0L)
            val rx = a[1] - (o?.get(1) ?: 0L)
            if (tx <= 0 && rx <= 0) continue
            val pkg = ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: continue
            out.add(Delta(pkg, maxOf(tx, 0L), maxOf(rx, 0L)))
        }
        return out
    }

    companion object { private const val TAG = "NetStatsWatch"; const val POLL_MS = 30_000L }
}
