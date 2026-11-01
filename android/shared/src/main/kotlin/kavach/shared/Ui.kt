package kavach.shared

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Programmatic UI helpers (no XML layouts, no AndroidX): large type and high contrast,
 * because the primary users are elderly people under stress.
 */
object Ui {
    val LEVEL_COLORS = mapOf("none" to 0xFF2E7D32.toInt(), "info" to 0xFF1565C0.toInt(), "caution" to 0xFFF9A825.toInt(), "stop" to 0xFFC62828.toInt())

    fun levelTitle(level: String, lang: String): String = when (level) {
        "stop" -> if (lang == "hi") "रुकिए — यह स्कैम है" else "STOP — this is a scam"
        "caution" -> if (lang == "hi") "सावधान — संदिग्ध कॉल" else "CAUTION — suspicious"
        "info" -> if (lang == "hi") "ध्यान दें" else "Listening — watch out"
        else -> if (lang == "hi") "सुरक्षित — सुन रहा है" else "Guarding — all clear"
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun column(ctx: Context, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp); setPadding(p, p, p, p)
    }

    fun scroll(ctx: Context, child: View): ScrollView = ScrollView(ctx).apply { addView(child) }

    fun text(ctx: Context, s: String = "", sp: Float = 18f, bold: Boolean = false, color: Int = Color.BLACK): TextView = TextView(ctx).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
    }

    fun banner(ctx: Context): TextView = TextView(ctx).apply {
        textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        val p = dp(ctx, 18); setPadding(p, p, p, p)
        background = GradientDrawable().apply { cornerRadius = dp(ctx, 16).toFloat(); setColor(LEVEL_COLORS["none"]!!) }
    }

    fun setBanner(b: TextView, level: String, lang: String) {
        b.text = levelTitle(level, lang)
        (b.background as GradientDrawable).setColor(LEVEL_COLORS[level] ?: LEVEL_COLORS["none"]!!)
        b.setTextColor(if (level == "caution") Color.BLACK else Color.WHITE)
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label; textSize = 18f; isAllCaps = false; setOnClickListener { onClick() }
    }

    fun ensureChannel(ctx: Context, id: String, name: String, importance: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    fun ongoing(ctx: Context, channel: String, title: String, body: String, icon: Int, intent: android.app.PendingIntent?): Notification =
        Notification.Builder(ctx, channel).setContentTitle(title).setContentText(body).setSmallIcon(icon).setOngoing(true)
            .apply { if (intent != null) setContentIntent(intent) }.build()

    fun keepScreenOn(a: Activity) { a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
}
