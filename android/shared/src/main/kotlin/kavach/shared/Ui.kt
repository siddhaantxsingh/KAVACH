package kavach.shared

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Programmatic UI kit (no XML layouts, no AndroidX). A calm, premium dark theme in the style of a
 * banking app: deep navy, rounded cards, one clear accent colour, large type and high contrast,
 * because the primary users are elderly people under stress.
 */
object Ui {
    // ---- palette
    const val BG = 0xFF0A1020.toInt()
    const val CARD = 0xFF131B2E.toInt()
    const val CARD_HI = 0xFF1A2440.toInt()
    const val STROKE = 0xFF26314F.toInt()
    const val TEXT = 0xFFF2F5FA.toInt()
    const val MUTED = 0xFF9AA6BF.toInt()
    const val FAINT = 0xFF6B7794.toInt()
    const val ACCENT = 0xFF4C8DFF.toInt()
    const val ACCENT_2 = 0xFF7A5CFF.toInt()
    const val GOOD = 0xFF22C55E.toInt()
    const val WARN = 0xFFF5B83D.toInt()
    const val BAD = 0xFFEF4444.toInt()

    val LEVEL_COLORS = mapOf("none" to 0xFF15803D.toInt(), "info" to 0xFF1D4ED8.toInt(), "caution" to 0xFFD97706.toInt(), "stop" to 0xFFDC2626.toInt())
    private val LEVEL_GRADIENT = mapOf(
        "none" to intArrayOf(0xFF0F766E.toInt(), 0xFF15803D.toInt()),
        "info" to intArrayOf(0xFF1E3A8A.toInt(), 0xFF2563EB.toInt()),
        "caution" to intArrayOf(0xFFB45309.toInt(), 0xFFF59E0B.toInt()),
        "stop" to intArrayOf(0xFF991B1B.toInt(), 0xFFDC2626.toInt()),
    )

    fun levelTitle(level: String, lang: String): String = when (level) {
        "stop" -> if (lang == "hi") "रुकिए — यह स्कैम है" else "STOP — this is a scam"
        "caution" -> if (lang == "hi") "सावधान — संदिग्ध कॉल" else "Caution — looks suspicious"
        "info" -> if (lang == "hi") "ध्यान से सुन रहा है" else "Listening carefully"
        else -> if (lang == "hi") "सुरक्षित" else "All clear"
    }

    fun levelSubtitle(level: String, lang: String): String = when (level) {
        "stop" -> if (lang == "hi") "पैसे, ओटीपी या पिन मत दीजिए। कॉल काटिए।" else "Do not pay or share any OTP or PIN. End the call."
        "caution" -> if (lang == "hi") "कोई भी पैसा भेजने से पहले रुकिए।" else "Pause before you send any money."
        "info" -> if (lang == "hi") "कुछ बातें ध्यान देने लायक हैं।" else "Some things are worth watching."
        else -> if (lang == "hi") "कवच आपकी सुरक्षा कर रहा है — सब कुछ इसी फ़ोन पर।" else "KAVACH is protecting you — everything stays on this phone."
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun dpf(ctx: Context, v: Int): Float = v * ctx.resources.displayMetrics.density

    /** Window background + status/navigation bar colours. Call once in onCreate. */
    fun theme(a: Activity) {
        a.window.decorView.setBackgroundColor(BG)
        @Suppress("DEPRECATION")
        run { a.window.statusBarColor = BG; a.window.navigationBarColor = BG }
    }

    fun column(ctx: Context, padDp: Int = 18): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp); setPadding(p, p, p, p)
        setBackgroundColor(BG)
    }

    fun scroll(ctx: Context, child: View): ScrollView = ScrollView(ctx).apply {
        setBackgroundColor(BG); isFillViewport = true; addView(child)
    }

    /** Older screens pass Color.BLACK / DKGRAY / GRAY: mapped to the dark theme's text colours. */
    private fun themed(color: Int): Int = when (color) {
        Color.BLACK -> TEXT
        Color.DKGRAY -> MUTED
        Color.GRAY, Color.LTGRAY -> FAINT
        else -> color
    }

    fun text(ctx: Context, s: String = "", sp: Float = 18f, bold: Boolean = false, color: Int = Color.BLACK): TextView = TextView(ctx).apply {
        text = s; textSize = sp; setTextColor(themed(color))
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dpf(ctx, 3), 1f)
        setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
    }

    /** Small grey caps label above a card. */
    fun label(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s.uppercase(); textSize = 12f; letterSpacing = 0.12f; setTextColor(FAINT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(ctx, 4), dp(ctx, 18), 0, dp(ctx, 8))
    }

    /** App header: shield mark, product name, one-line subtitle. */
    fun header(ctx: Context, title: String, subtitle: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 14))
        val mark = TextView(ctx).apply {
            text = "🛡"; textSize = 24f; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT_2)).apply { cornerRadius = dpf(ctx, 14) }
        }
        addView(mark, LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)))
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 14), 0, 0, 0) }
        words.addView(TextView(ctx).apply {
            text = title; textSize = 22f; setTextColor(TEXT); letterSpacing = 0.02f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        words.addView(TextView(ctx).apply { text = subtitle; textSize = 13f; setTextColor(MUTED) })
        addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Rounded card. */
    fun card(ctx: Context, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp); setPadding(p, p, p, p)
        background = GradientDrawable().apply { cornerRadius = dpf(ctx, 20); setColor(CARD); setStroke(dp(ctx, 1), STROKE) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(ctx, 12) }
    }

    /** Status panel: big title + subtitle on a level-coloured gradient. Returns the title view (use setBanner). */
    fun banner(ctx: Context): TextView = TextView(ctx).apply {
        textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.START or Gravity.CENTER_VERTICAL
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(dpf(ctx, 4), 1f)
        val p = dp(ctx, 22); setPadding(p, p, p, p)
        minHeight = dp(ctx, 120)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, LEVEL_GRADIENT["none"]!!).apply { cornerRadius = dpf(ctx, 24) }
        elevation = dpf(ctx, 6)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(ctx, 10) }
    }

    fun setBanner(b: TextView, level: String, lang: String) {
        val glyph = when (level) { "stop" -> "⛔"; "caution" -> "⚠️"; "info" -> "👂"; else -> "🛡" }
        val title = levelTitle(level, lang)
        val sub = levelSubtitle(level, lang)
        val s = android.text.SpannableString("$glyph  $title\n$sub")
        val subStart = glyph.length + 2 + title.length + 1
        s.setSpan(android.text.style.RelativeSizeSpan(0.55f), subStart, s.length, 0)
        s.setSpan(android.text.style.StyleSpan(Typeface.NORMAL), subStart, s.length, 0)
        s.setSpan(android.text.style.ForegroundColorSpan(0xE6FFFFFF.toInt()), subStart, s.length, 0)
        b.text = s
        b.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, LEVEL_GRADIENT[level] ?: LEVEL_GRADIENT["none"]!!)
            .apply { cornerRadius = dpf(b.context, 24) }
        b.setTextColor(Color.WHITE)
    }

    private fun styled(ctx: Context, label: String, fill: Int?, gradient: IntArray?, stroke: Int?, textColor: Int, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label; textSize = 17f; isAllCaps = false; setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            stateListAnimator = null; elevation = 0f
            minHeight = dp(ctx, 54); minimumHeight = dp(ctx, 54)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            val shape = (if (gradient != null) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, gradient) else GradientDrawable()).apply {
                cornerRadius = dpf(ctx, 16)
                if (fill != null) setColor(fill)
                if (stroke != null) setStroke(dp(ctx, 1), stroke)
            }
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5) }
        }

    /** Default (secondary) button: dark, outlined. */
    fun button(ctx: Context, label: String, onClick: () -> Unit): Button = styled(ctx, label, CARD_HI, null, STROKE, TEXT, onClick)

    /** Main call-to-action: blue-violet gradient. */
    fun primary(ctx: Context, label: String, onClick: () -> Unit): Button = styled(ctx, label, null, intArrayOf(ACCENT, ACCENT_2), null, Color.WHITE, onClick)

    /** Destructive / stop action. */
    fun danger(ctx: Context, label: String, onClick: () -> Unit): Button = styled(ctx, label, 0x1FEF4444, null, BAD, 0xFFFCA5A5.toInt(), onClick)

    /** Filled solid colour (e.g. white button on the red STOP screen). */
    fun solid(ctx: Context, label: String, fill: Int, textColor: Int, onClick: () -> Unit): Button = styled(ctx, label, fill, null, null, textColor, onClick)

    /** Equal-width row of views with a small gap. */
    fun row(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(ctx, 10); topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5)
            })
        }
    }

    /** Rounded dark text field. */
    fun input(ctx: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(ctx).apply {
        this.hint = hint; inputType = type; textSize = 17f
        setTextColor(TEXT); setHintTextColor(FAINT)
        val p = dp(ctx, 14); setPadding(p, p, p, p)
        background = GradientDrawable().apply { cornerRadius = dpf(ctx, 14); setColor(BG); setStroke(dp(ctx, 1), STROKE) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 4); bottomMargin = dp(ctx, 6) }
    }

    /** Small rounded status pill (e.g. "Placement: good"). */
    fun pill(ctx: Context): TextView = TextView(ctx).apply {
        textSize = 14f; setTextColor(TEXT); visibility = View.GONE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(ctx, 8) }
    }

    fun setPill(p: TextView, text: String, color: Int) {
        p.text = text; p.visibility = View.VISIBLE; p.setTextColor(color)
        p.background = GradientDrawable().apply { cornerRadius = dpf(p.context, 999); setColor((color and 0x00FFFFFF) or 0x26000000); setStroke(dp(p.context, 1), (color and 0x00FFFFFF) or 0x66000000) }
    }

    /** Setup step line: ✓ done (green) or a numbered button. */
    fun step(ctx: Context, done: Boolean, doneText: String, todoText: String, onClick: () -> Unit): View =
        if (done) text(ctx, "✓  $doneText", 16f, bold = true, color = GOOD).apply { setPadding(dp(ctx, 4), dp(ctx, 10), 0, dp(ctx, 10)) }
        else primary(ctx, todoText, onClick)

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply { topMargin = dp(ctx, 8); bottomMargin = dp(ctx, 8) }
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
