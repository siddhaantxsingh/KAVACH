package kavach.shared

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 * KAVACH design system (programmatic, no XML layouts, no AndroidX): "Midnight, Ivory and Gold".
 *  - Dark: midnight #0B1020, surface #151D31, divider #29344B, text #F4F1E8 / #ADB6C8.
 *  - Light: ivory #F7F4ED, white surfaces, ink #15213B, gold text #76551D.
 *  - Cobalt #3157D5 for actions; gold #D6B36A only for identity and highlights.
 *  - Amber = caution and red = urgent are STATUS colours, never decoration. Ready / listening states
 *    use neutral surfaces with words and a shield mark, never green or teal branding.
 * Flat colours, no gradients behind text. Contrast of every pair: docs/design_tokens.md
 * (tools/contrast_check.py). The theme follows the phone's light/dark setting.
 */
object Ui {
    class Palette(
        val bg: Int, val card: Int, val cardHi: Int, val stroke: Int, val text: Int, val muted: Int,
        val accent: Int, val onAccent: Int, val gold: Int, val goldText: Int,
        val ready: Int, val onReady: Int, val listen: Int, val onListen: Int,
        val caution: Int, val onCaution: Int, val stop: Int, val onStop: Int, val good: Int, val dark: Boolean,
    )

    val DARK = Palette(
        bg = 0xFF0B1020.toInt(), card = 0xFF151D31.toInt(), cardHi = 0xFF1C2640.toInt(), stroke = 0xFF29344B.toInt(),
        text = 0xFFF4F1E8.toInt(), muted = 0xFFADB6C8.toInt(), accent = 0xFF3157D5.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        gold = 0xFFD6B36A.toInt(), goldText = 0xFFD6B36A.toInt(),
        ready = 0xFF151D31.toInt(), onReady = 0xFFF4F1E8.toInt(), listen = 0xFF22336B.toInt(), onListen = 0xFFF4F1E8.toInt(),
        caution = 0xFFF2B441.toInt(), onCaution = 0xFF1F1300.toInt(), stop = 0xFFB91C1C.toInt(), onStop = 0xFFFFFFFF.toInt(),
        good = 0xFF8FD3A6.toInt(), dark = true,
    )
    val LIGHT = Palette(
        bg = 0xFFF7F4ED.toInt(), card = 0xFFFFFFFF.toInt(), cardHi = 0xFFFFFFFF.toInt(), stroke = 0xFFDED7C7.toInt(),
        text = 0xFF15213B.toInt(), muted = 0xFF4B5670.toInt(), accent = 0xFF3157D5.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        gold = 0xFFD6B36A.toInt(), goldText = 0xFF76551D.toInt(),
        ready = 0xFFFFFFFF.toInt(), onReady = 0xFF15213B.toInt(), listen = 0xFFE6ECFB.toInt(), onListen = 0xFF15213B.toInt(),
        caution = 0xFFFBE3B0.toInt(), onCaution = 0xFF4A2F00.toInt(), stop = 0xFFB91C1C.toInt(), onStop = 0xFFFFFFFF.toInt(),
        good = 0xFF1F6B3A.toInt(), dark = false,
    )

    /** The active palette; set by theme() from the phone's light/dark setting. */
    @Volatile var P: Palette = DARK

    // shorthands used by screens (read at call time, so they follow the theme)
    val BG get() = P.bg
    val TEXT get() = P.text
    val MUTED get() = P.muted
    val ACCENT get() = P.accent
    val GOLD get() = P.goldText
    val GOOD get() = P.good
    val WARN get() = if (P.dark) P.caution else 0xFF8A5A00.toInt()
    val BAD get() = if (P.dark) 0xFFF87171.toInt() else P.stop

    /** kept for older callers: status colours by level */
    val LEVEL_COLORS get() = mapOf("none" to P.listen, "info" to P.listen, "caution" to P.caution, "stop" to P.stop)

    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Pick light/dark palette and paint window, status bar and navigation bar. Call first in onCreate. */
    fun theme(a: Activity) {
        P = if (isDark(a)) DARK else LIGHT
        a.window.decorView.setBackgroundColor(P.bg)
        @Suppress("DEPRECATION")
        run {
            a.window.statusBarColor = P.bg; a.window.navigationBarColor = P.bg
            var flags = a.window.decorView.systemUiVisibility
            flags = if (P.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            a.window.decorView.systemUiVisibility = flags
        }
    }

    fun dialogTheme(): Int = if (P.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    // ------------------------------------------------------------------ wording (truthful states)

    /** Title for a detector level while actively listening / protecting. */
    fun levelTitle(level: String, lang: String): String = when (level) {
        "stop" -> if (lang == "hi") "रुकिए — यह स्कैम जैसा लग रहा है" else "Stop — this looks like a scam"
        "caution" -> if (lang == "hi") "सावधान — संदिग्ध बातें" else "Caution — suspicious pattern"
        "info" -> if (lang == "hi") "सुन रहा है — ध्यान दें" else "Listening — worth watching"
        else -> if (lang == "hi") "सुन रहा है — अभी कोई खतरे का संकेत नहीं" else "Listening — no warning signs so far"
    }

    fun levelSubtitle(level: String, lang: String): String = when (level) {
        "stop" -> if (lang == "hi") "पैसे, ओटीपी या पिन मत दीजिए। कॉल काटिए।" else "Don't pay or share any OTP or PIN. End the call."
        "caution" -> if (lang == "hi") "पैसे भेजने या कोड बताने से पहले रुकिए।" else "Pause before you send money or share any code."
        "info" -> if (lang == "hi") "कुछ बातें ध्यान देने लायक हैं।" else "Some things in this call are worth watching."
        else -> if (lang == "hi") "यह गारंटी नहीं है — सतर्क रहें।" else "This is not a guarantee — stay alert."
    }

    // ------------------------------------------------------------------ building blocks

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun dpf(ctx: Context, v: Int): Float = v * ctx.resources.displayMetrics.density

    private val SANS = Typeface.create("sans-serif", Typeface.NORMAL)
    private val SANS_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun column(ctx: Context, padDp: Int = 18): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp); setPadding(p, p, p, p)
        setBackgroundColor(P.bg)
    }

    fun scroll(ctx: Context, child: View): ScrollView = ScrollView(ctx).apply {
        setBackgroundColor(P.bg); isFillViewport = true; addView(child)
    }

    /** Older screens pass Color.BLACK / DKGRAY / GRAY: mapped to the theme's text colours. */
    private fun themed(color: Int): Int = when (color) {
        Color.BLACK -> P.text
        Color.DKGRAY, Color.GRAY, Color.LTGRAY -> P.muted
        else -> color
    }

    fun text(ctx: Context, s: String = "", sp: Float = 18f, bold: Boolean = false, color: Int = Color.BLACK): TextView = TextView(ctx).apply {
        text = s; textSize = sp; setTextColor(themed(color))
        typeface = if (bold) SANS_MEDIUM else SANS
        setLineSpacing(dpf(ctx, 3), 1f)
        setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
    }

    /** Section label: sentence case, readable size and contrast (no tracked caps, no thin grey). */
    fun label(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = 15f; setTextColor(P.muted); typeface = SANS_MEDIUM
        setPadding(dp(ctx, 4), dp(ctx, 18), 0, dp(ctx, 8))
        isFocusable = false
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    /** Drawn shield mark (gold outline on cobalt/midnight); Guardian adds two sound arcs. */
    class ShieldMark(ctx: Context, private val listening: Boolean) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        init { contentDescription = if (listening) "KAVACH Guardian" else "KAVACH Shield"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            fill.color = if (P.dark) 0xFF1C2640.toInt() else 0xFF15213B.toInt()
            c.drawRoundRect(RectF(0f, 0f, w, h), w * 0.28f, w * 0.28f, fill)
            val p = Path()
            val cx = if (listening) w * 0.42f else w * 0.5f
            val sw = w * 0.46f; val top = h * 0.2f; val bot = h * 0.82f
            p.moveTo(cx, top)
            p.lineTo(cx + sw / 2, top + h * 0.1f)
            p.cubicTo(cx + sw / 2, h * 0.55f, cx + sw * 0.3f, h * 0.72f, cx, bot)
            p.cubicTo(cx - sw * 0.3f, h * 0.72f, cx - sw / 2, h * 0.55f, cx - sw / 2, top + h * 0.1f)
            p.close()
            fill.color = 0xFFD6B36A.toInt()
            c.drawPath(p, fill)
            line.color = if (P.dark) 0xFF1C2640.toInt() else 0xFF15213B.toInt(); line.strokeWidth = w * 0.06f
            c.drawLine(cx, h * 0.34f, cx, h * 0.66f, line)
            if (listening) {
                line.color = 0xFFD6B36A.toInt(); line.strokeWidth = w * 0.055f
                c.drawArc(RectF(w * 0.62f, h * 0.38f, w * 0.78f, h * 0.62f), -60f, 120f, false, line)
                c.drawArc(RectF(w * 0.62f, h * 0.28f, w * 0.92f, h * 0.72f), -55f, 110f, false, line)
            }
        }
    }

    /** App header: drawn mark, product name, one-line subtitle. */
    fun header(ctx: Context, title: String, subtitle: String, listening: Boolean = false): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 14))
        addView(ShieldMark(ctx, listening), LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)))
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 14), 0, 0, 0) }
        words.addView(TextView(ctx).apply { text = title; textSize = 22f; setTextColor(P.text); typeface = SANS_MEDIUM
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        words.addView(TextView(ctx).apply { text = subtitle; textSize = 14f; setTextColor(P.muted) })
        addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun card(ctx: Context, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp); setPadding(p, p, p, p)
        background = GradientDrawable().apply { cornerRadius = dpf(ctx, 18); setColor(P.card); setStroke(dp(ctx, 1), P.stroke) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(ctx, 12) }
    }

    /** Status panel. Use setBanner (detector level) or setState (idle / setup / demo / any wording). */
    fun banner(ctx: Context): TextView = TextView(ctx).apply {
        textSize = 24f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; typeface = SANS_MEDIUM
        setLineSpacing(dpf(ctx, 4), 1f)
        val p = dp(ctx, 20); setPadding(p, p, p, p)
        minHeight = dp(ctx, 110)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(ctx, 10) }
    }

    /** kind: "ready" | "listen" | "caution" | "stop" */
    fun setState(b: TextView, kind: String, title: String, subtitle: String) {
        val (bg, fg, border) = when (kind) {
            "stop" -> Triple(P.stop, P.onStop, P.stop)
            "caution" -> Triple(P.caution, P.onCaution, P.caution)
            "listen" -> Triple(P.listen, P.onListen, if (P.dark) 0xFF3A4E8C.toInt() else 0xFFC7D3F5.toInt())
            else -> Triple(P.ready, P.onReady, P.stroke)
        }
        val s = android.text.SpannableString("$title\n$subtitle")
        val subStart = title.length + 1
        s.setSpan(android.text.style.RelativeSizeSpan(0.66f), subStart, s.length, 0)
        b.text = s
        b.setTextColor(fg)
        b.background = GradientDrawable().apply { cornerRadius = dpf(b.context, 22); setColor(bg); setStroke(dp(b.context, 1), border) }
        b.contentDescription = "$title. $subtitle"
    }

    /** Detector level while listening / protecting. */
    fun setBanner(b: TextView, level: String, lang: String) {
        val kind = when (level) { "stop" -> "stop"; "caution" -> "caution"; else -> "listen" }
        setState(b, kind, levelTitle(level, lang), levelSubtitle(level, lang))
    }

    private fun styled(ctx: Context, label: String, fill: Int, stroke: Int?, textColor: Int, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label; textSize = 17f; isAllCaps = false; setTextColor(textColor); typeface = SANS_MEDIUM
            stateListAnimator = null; elevation = 0f
            minHeight = dp(ctx, 56); minimumHeight = dp(ctx, 56)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            val shape = GradientDrawable().apply { cornerRadius = dpf(ctx, 14); setColor(fill); if (stroke != null) setStroke(dp(ctx, 1), stroke) }
            background = RippleDrawable(ColorStateList.valueOf(if (P.dark) 0x33FFFFFF else 0x2215213B), shape, null)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5) }
        }

    /** Secondary button (default). */
    fun button(ctx: Context, label: String, onClick: () -> Unit): Button = styled(ctx, label, P.cardHi, P.stroke, P.text, onClick)

    /** The one main action on a screen: cobalt. */
    fun primary(ctx: Context, label: String, onClick: () -> Unit): Button = styled(ctx, label, P.accent, null, P.onAccent, onClick)

    /** Stop / destructive action: outlined, readable. */
    fun danger(ctx: Context, label: String, onClick: () -> Unit): Button =
        styled(ctx, label, P.cardHi, if (P.dark) 0xFFF87171.toInt() else P.stop, if (P.dark) 0xFFFCA5A5.toInt() else P.stop, onClick)

    fun solid(ctx: Context, label: String, fill: Int, textColor: Int, onClick: () -> Unit): Button = styled(ctx, label, fill, null, textColor, onClick)

    /** Equal-width row; on narrow screens or very large text it stacks vertically. */
    fun row(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        val narrow = ctx.resources.configuration.screenWidthDp < 360 || ctx.resources.configuration.fontScale > 1.3f
        orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, if (narrow) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5) }
            else LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(ctx, 10); topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5)
            })
        }
    }

    fun input(ctx: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(ctx).apply {
        this.hint = hint; inputType = type; textSize = 17f
        setTextColor(P.text); setHintTextColor(P.muted)
        val p = dp(ctx, 14); setPadding(p, p, p, p)
        minHeight = dp(ctx, 52)
        background = GradientDrawable().apply { cornerRadius = dpf(ctx, 12); setColor(P.bg); setStroke(dp(ctx, 1), P.stroke) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 4); bottomMargin = dp(ctx, 6) }
    }

    fun pill(ctx: Context): TextView = TextView(ctx).apply {
        textSize = 15f; setTextColor(P.text); visibility = View.GONE; typeface = SANS_MEDIUM
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(ctx, 8) }
    }

    /** Pill with a readable text colour on a soft card background (meaning carried by words, not colour). */
    fun setPill(p: TextView, text: String, color: Int) {
        p.text = text; p.visibility = View.VISIBLE; p.setTextColor(color)
        p.background = GradientDrawable().apply { cornerRadius = dpf(p.context, 999); setColor(P.card); setStroke(dp(p.context, 1), color) }
    }

    /** Setup step: a button until done, then a tick line with words. */
    fun step(ctx: Context, done: Boolean, doneText: String, todoText: String, onClick: () -> Unit): View =
        if (done) text(ctx, "✓  $doneText", 16f, bold = true, color = P.good).apply { setPadding(dp(ctx, 4), dp(ctx, 10), 0, dp(ctx, 10)) }
        else primary(ctx, todoText, onClick)

    /** Model / readiness line: name, then state in words. */
    fun status(ctx: Context, name: String, ok: Boolean, state: String): TextView =
        text(ctx, (if (ok) "✓  " else "•  ") + "$name — $state", 15f, color = if (ok) P.good else WARN)

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(P.stroke)
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
