package kavach.netwatch

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Start / stop the DNS watch and see recent verdicts. Plain screen, no AndroidX. */
class NetWatchActivity : Activity() {
    private lateinit var status: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(0xFF0A1020.toInt())
        @Suppress("DEPRECATION") run { window.statusBarColor = 0xFF0A1020.toInt() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40); setBackgroundColor(0xFF0A1020.toInt()) }
        col.addView(TextView(this).apply { text = "KAVACH Network Watch"; textSize = 26f; setTextColor(Color.WHITE) })
        col.addView(TextView(this).apply {
            textSize = 15f; setTextColor(0xFF9AA6BF.toInt())
            text = "Optional. Checks the NAMES of websites each app contacts (not what is sent) and warns when an app " +
                "talks to a fake bank site or behaves like malware. Android will show a key icon because this uses " +
                "the VPN feature locally on this phone; your traffic is not sent to any KAVACH server.\n" +
                "वैकल्पिक: हर ऐप किन वेबसाइटों से जुड़ता है, सिर्फ उनके नाम जाँचता है।"
        })
        col.addView(Button(this).apply { text = "Start Network Watch"; textSize = 20f; setOnClickListener { start() } })
        col.addView(Button(this).apply { text = "Stop"; textSize = 20f; setOnClickListener {
            startService(Intent(this@NetWatchActivity, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)) } })
        status = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 30, 0, 0) }
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun start() {
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 7) else onActivityResult(7, RESULT_OK, null)
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 7 && resultCode == RESULT_OK) startService(Intent(this, DnsVpnService::class.java))
    }

    private fun refresh() {
        status.text = "Status: ${DnsVpnService.state} · lookups checked: ${DnsVpnService.lookups}\n\n" +
            (synchronized(DnsVpnService.recent) { DnsVpnService.recent.toList() }.joinToString("\n\n").ifEmpty { "No warnings." })
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }
}
