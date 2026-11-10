package kavach.shared

import android.animation.ValueAnimator
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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * KAVACH design system (programmatic, no XML layouts, no AndroidX): "Midnight, Ivory and Gold".
 *  - Dark: midnight #0B1020, surface #151D31, divider #29344B, text #F4F1E8 / #ADB6C8.
 *  - Light: ivory #F7F4ED, white surfaces, ink #15213B, gold text #76551D.
 *  - Cobalt #3157D5 for actions; gold #D6B36A only for identity and highlights.
 *  - Amber = caution and red = urgent are STATUS colours, never decoration. Every state is also
 *    carried by an icon and by words, never by colour alone.
 *  - Type: Inter (bundled, SIL OFL), with the system font as fallback for Devanagari.
 *  - Icons: Lucide line icons (ISC licence) as vector drawables.
 * Contrast of every text pair: docs/design_tokens.md (tools/contrast_check.py).
 */
object Ui {
    class Palette(
        val bg: Int, val card: Int, val cardHi: Int, val stroke: Int, val text: Int, val muted: Int,
        val accent: Int, val onAccent: Int, val accentSoft: Int, val accentText: Int, val gold: Int, val goldText: Int, val goldSoft: Int,
        val ready: Int, val onReady: Int, val listen: Int, val onListen: Int, val listenStroke: Int,
        val caution: Int, val onCaution: Int, val stop: Int, val onStop: Int,
        val good: Int, val goodSoft: Int, val warn: Int, val warnSoft: Int, val bad: Int, val badSoft: Int, val dark: Boolean,
    )

    val DARK = Palette(
        bg = 0xFF0B1020.toInt(), card = 0xFF151D31.toInt(), cardHi = 0xFF1C2640.toInt(), stroke = 0xFF29344B.toInt(),
        text = 0xFFF4F1E8.toInt(), muted = 0xFFADB6C8.toInt(), accent = 0xFF3157D5.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        accentSoft = 0xFF1A2752.toInt(), accentText = 0xFF9DB4FF.toInt(),
        gold = 0xFFD6B36A.toInt(), goldText = 0xFFD6B36A.toInt(), goldSoft = 0xFF2A2619.toInt(),
        ready = 0xFF151D31.toInt(), onReady = 0xFFF4F1E8.toInt(), listen = 0xFF14204A.toInt(), onListen = 0xFFF4F1E8.toInt(), listenStroke = 0xFF3A4E8C.toInt(),
        caution = 0xFFF2B441.toInt(), onCaution = 0xFF1F1300.toInt(), stop = 0xFFB91C1C.toInt(), onStop = 0xFFFFFFFF.toInt(),
        good = 0xFF8FD3A6.toInt(), goodSoft = 0xFF15291F.toInt(), warn = 0xFFF2B441.toInt(), warnSoft = 0xFF2B2313.toInt(),
        bad = 0xFFFCA5A5.toInt(), badSoft = 0xFF341619.toInt(), dark = true,
    )
    val LIGHT = Palette(
        bg = 0xFFF7F4ED.toInt(), card = 0xFFFFFFFF.toInt(), cardHi = 0xFFF3EFE5.toInt(), stroke = 0xFFDED7C7.toInt(),
        text = 0xFF15213B.toInt(), muted = 0xFF4B5670.toInt(), accent = 0xFF3157D5.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        accentSoft = 0xFFE6ECFB.toInt(), accentText = 0xFF2A4BB8.toInt(),
        gold = 0xFFD6B36A.toInt(), goldText = 0xFF76551D.toInt(), goldSoft = 0xFFF6EEDB.toInt(),
        ready = 0xFFFFFFFF.toInt(), onReady = 0xFF15213B.toInt(), listen = 0xFFE6ECFB.toInt(), onListen = 0xFF15213B.toInt(), listenStroke = 0xFFC7D3F5.toInt(),
        caution = 0xFFFBE3B0.toInt(), onCaution = 0xFF4A2F00.toInt(), stop = 0xFFB91C1C.toInt(), onStop = 0xFFFFFFFF.toInt(),
        good = 0xFF1F6B3A.toInt(), goodSoft = 0xFFE3F3E8.toInt(), warn = 0xFF8A5A00.toInt(), warnSoft = 0xFFFBF0D6.toInt(),
        bad = 0xFFB91C1C.toInt(), badSoft = 0xFFFBE6E6.toInt(), dark = false,
    )

    /** The active palette; set by theme() from the phone's light/dark setting. */
    @Volatile var P: Palette = DARK

    val BG get() = P.bg
    val TEXT get() = P.text
    val MUTED get() = P.muted
    val ACCENT get() = P.accent
    val GOLD get() = P.goldText
    val GOOD get() = P.good
    val WARN get() = P.warn
    val BAD get() = P.bad

    /** Icons (Lucide), referenced by screens as Ui.Ic.mic etc. */
    object Ic {
        val shield = R.drawable.ic_shield; val shieldCheck = R.drawable.ic_shield_check; val shieldAlert = R.drawable.ic_shield_alert
        val shieldOff = R.drawable.ic_shield_off; val mic = R.drawable.ic_mic; val micOff = R.drawable.ic_mic_off; val ear = R.drawable.ic_ear
        val phone = R.drawable.ic_phone; val phoneCall = R.drawable.ic_phone_call; val phoneOff = R.drawable.ic_phone_off
        val message = R.drawable.ic_message_square_text; val link = R.drawable.ic_link; val search = R.drawable.ic_search
        val bell = R.drawable.ic_bell; val settings = R.drawable.ic_settings; val chevron = R.drawable.ic_chevron_right
        val check = R.drawable.ic_check; val triangle = R.drawable.ic_triangle_alert; val circleAlert = R.drawable.ic_circle_alert
        val octagon = R.drawable.ic_octagon_alert; val lock = R.drawable.ic_lock; val cpu = R.drawable.ic_cpu
        val languages = R.drawable.ic_languages; val play = R.drawable.ic_play; val smartphone = R.drawable.ic_smartphone
        val users = R.drawable.ic_users; val scan = R.drawable.ic_scan_search; val activity = R.drawable.ic_activity
        val home = R.drawable.ic_house; val radio = R.drawable.ic_radio; val zap = R.drawable.ic_zap; val info = R.drawable.ic_info
        val x = R.drawable.ic_x; val volume = R.drawable.ic_volume_2; val paste = R.drawable.ic_clipboard_paste
        val back = R.drawable.ic_arrow_left; val sparkles = R.drawable.ic_sparkles; val signal = R.drawable.ic_signal
        val help = R.drawable.ic_circle_help; val history = R.drawable.ic_history; val wifi = R.drawable.ic_wifi; val eyeOff = R.drawable.ic_eye_off
    }

    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Pick light/dark palette, load the typeface and paint window + system bars. Call first in onCreate. */
    fun theme(a: Activity, navBar: Int? = null) {
        P = if (isDark(a)) DARK else LIGHT
        loadFont(a)
        a.window.decorView.setBackgroundColor(P.bg)
        @Suppress("DEPRECATION")
        run {
            a.window.statusBarColor = P.bg; a.window.navigationBarColor = navBar ?: P.bg
            var flags = a.window.decorView.systemUiVisibility
            flags = if (P.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            flags = if (P.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            a.window.decorView.systemUiVisibility = flags
        }
    }

    fun dialogTheme(): Int = if (P.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    // ------------------------------------------------------------------ type

    private val faces = HashMap<Int, Typeface>()
    @Volatile private var fontCtx: Context? = null
    private fun loadFont(ctx: Context) { if (fontCtx == null) fontCtx = ctx.applicationContext }

    /** Inter at a given weight (400 regular, 500 medium, 600 semibold, 700 bold); system font if it cannot load. */
    fun font(weight: Int): Typeface = synchronized(faces) {
        faces.getOrPut(weight) {
            try {
                Typeface.Builder(fontCtx!!.assets, "fonts/Inter.ttf").setFontVariationSettings("'wght' $weight, 'opsz' ${if (weight >= 600) 28 else 16}").build()
            } catch (_: Throwable) {
                Typeface.create(if (weight >= 500) "sans-serif-medium" else "sans-serif", if (weight >= 700) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    // ------------------------------------------------------------------ wording (truthful states)

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

    // ------------------------------------------------------------------ basics

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun shape(ctx: Context, fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpf(ctx, radiusDp); setColor(fill)
        if (stroke != null) setStroke(Math.max(1, dpf(ctx, strokeDp).toInt()), stroke)
    }

    private fun ripple(ctx: Context, content: Drawable?, radiusDp: Float): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(if (P.dark) 0x29FFFFFF else 0x1F15213B), content,
            GradientDrawable().apply { cornerRadius = dpf(ctx, radiusDp); setColor(Color.WHITE) })

    fun column(ctx: Context, padDp: Int = 20): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp); setPadding(p, dp(ctx, 8), p, dp(ctx, 28))
        setBackgroundColor(P.bg)
    }

    fun scroll(ctx: Context, child: View): ScrollView = ScrollView(ctx).apply {
        setBackgroundColor(P.bg); isFillViewport = true; isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; addView(child)
    }

    fun space(ctx: Context, hDp: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp)) }

    /** Older screens pass Color.BLACK / DKGRAY / GRAY: mapped to the theme's text colours. */
    private fun themed(color: Int): Int = when (color) {
        Color.BLACK -> P.text
        Color.DKGRAY, Color.GRAY, Color.LTGRAY -> P.muted
        else -> color
    }

    fun text(ctx: Context, s: String = "", sp: Float = 16f, bold: Boolean = false, color: Int = Color.BLACK, weight: Int = 0): TextView = TextView(ctx).apply {
        text = s; textSize = sp; setTextColor(themed(color))
        typeface = font(if (weight > 0) weight else if (bold) 600 else 400)
        if (sp >= 20f) letterSpacing = -0.015f
        setLineSpacing(dpf(ctx, if (sp >= 20f) 2f else 3f), 1f)
        setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
    }

    /** Section title above a group. */
    fun label(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = 13f; setTextColor(P.muted); typeface = font(600); letterSpacing = 0.04f
        setPadding(dp(ctx, 4), dp(ctx, 22), 0, dp(ctx, 10))
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int = 22): ImageView = ImageView(ctx).apply {
        setImageDrawable(ctx.getDrawable(res)?.mutate()?.apply { setTint(color) })
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Icon in a soft rounded tile (used at the start of list rows). */
    fun iconTile(ctx: Context, res: Int, fg: Int = P.goldText, bg: Int = P.goldSoft, sizeDp: Int = 40): FrameLayout = FrameLayout(ctx).apply {
        background = shape(ctx, bg, sizeDp * 0.3f)
        val s = dp(ctx, (sizeDp * 0.5f).toInt())
        addView(ImageView(ctx).apply { setImageDrawable(ctx.getDrawable(res)?.mutate()?.apply { setTint(fg) }) }, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ------------------------------------------------------------------ brand mark + top bar

    /** Drawn shield mark: gold shield on a midnight tile; Guardian adds sound bars. */
    class ShieldMark(ctx: Context, private val listening: Boolean) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val tile = if (listening) 0xFF1B2A5C.toInt() else if (P.dark) 0xFF1C2640.toInt() else 0xFF0B1020.toInt()
            fill.color = tile
            c.drawRoundRect(RectF(0f, 0f, w, h), w * 0.3f, w * 0.3f, fill)
            val p = Path()
            val cx = w * 0.5f
            val sw = w * 0.5f; val top = h * 0.2f; val bot = h * 0.82f
            p.moveTo(cx, top)
            p.lineTo(cx + sw / 2, top + h * 0.1f)
            p.cubicTo(cx + sw / 2, h * 0.55f, cx + sw * 0.3f, h * 0.72f, cx, bot)
            p.cubicTo(cx - sw * 0.3f, h * 0.72f, cx - sw / 2, h * 0.55f, cx - sw / 2, top + h * 0.1f)
            p.close()
            if (listening) {
                line.color = 0xFFD6B36A.toInt(); line.strokeWidth = w * 0.055f
                c.drawPath(p, line)
                line.color = 0xFFF4F1E8.toInt(); line.strokeWidth = w * 0.05f
                val hs = floatArrayOf(0.07f, 0.13f, 0.09f)
                for (i in 0..2) { val x = cx + (i - 1) * w * 0.1f; c.drawLine(x, h * 0.5f - h * hs[i], x, h * 0.5f + h * hs[i], line) }
            } else {
                fill.color = 0xFFD6B36A.toInt(); c.drawPath(p, fill)
                fill.color = tile; c.drawCircle(cx, h * 0.45f, w * 0.06f, fill)
                line.color = tile; line.strokeWidth = w * 0.05f
                c.drawLine(cx, h * 0.47f, cx, h * 0.6f, line)
            }
        }
    }

    /** Round icon button for the top bar. */
    fun iconButton(ctx: Context, res: Int, description: String, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = ripple(ctx, shape(ctx, P.card, 22f, P.stroke), 22f)
        val s = dp(ctx, 20)
        addView(ImageView(ctx).apply { setImageDrawable(ctx.getDrawable(res)?.mutate()?.apply { setTint(P.text) }) }, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        contentDescription = description; isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)).apply { leftMargin = dp(ctx, 8) }
    }

    /** App header: mark, product name, one-line subtitle, optional actions on the right. */
    fun header(ctx: Context, title: String, subtitle: String, listening: Boolean = false, vararg actions: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 10), 0, dp(ctx, 18))
        addView(ShieldMark(ctx, listening), LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 12), 0, 0, 0) }
        words.addView(TextView(ctx).apply { text = title; textSize = 20f; setTextColor(P.text); typeface = font(700); letterSpacing = -0.02f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        if (subtitle.isNotEmpty()) words.addView(TextView(ctx).apply { text = subtitle; textSize = 13f; setTextColor(P.muted); typeface = font(400)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.forEach { addView(it) }
    }

    /** Sub-screen header with a back arrow. */
    fun backHeader(a: Activity, title: String): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(a, 10), 0, dp(a, 18))
        addView(iconButton(a, Ic.back, "Back") { a.finish() }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = 0 })
        addView(TextView(a).apply { text = title; textSize = 20f; setTextColor(P.text); typeface = font(700); letterSpacing = -0.02f; setPadding(dp(a, 14), 0, 0, 0) })
    }

    // ------------------------------------------------------------------ cards and groups

    fun card(ctx: Context, padDp: Int = 18): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp); setPadding(p, p, p, p)
        background = shape(ctx, P.card, 22f, P.stroke)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(ctx, 12) }
    }

    /** A card that holds list rows, separated by inset hairlines. */
    fun group(ctx: Context, vararg rows: View): LinearLayout = card(ctx, 0).apply {
        clipToOutline = true
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(ctx).apply { setBackgroundColor(P.stroke) }, LinearLayout.LayoutParams(MATCH, Math.max(1, dp(ctx, 1) / 2)).apply { leftMargin = dp(ctx, 70) })
            addView(r)
        }
    }

    fun addRow(group: LinearLayout, row: View) {
        val ctx = group.context
        if (group.childCount > 0) group.addView(View(ctx).apply { setBackgroundColor(P.stroke) }, LinearLayout.LayoutParams(MATCH, Math.max(1, dp(ctx, 1) / 2)).apply { leftMargin = dp(ctx, 70) })
        group.addView(row)
    }

    /** List row: icon tile, title, optional subtitle, optional trailing view or chevron. */
    fun item(ctx: Context, iconRes: Int, title: String, subtitle: String? = null, trailing: View? = null, chevron: Boolean = false,
             fg: Int = P.goldText, bg: Int = P.goldSoft, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14)); minimumHeight = dp(ctx, 68)
        addView(iconTile(ctx, iconRes, fg, bg))
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 14), 0, dp(ctx, 10), 0) }
        words.addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(P.text); typeface = font(500) })
        if (!subtitle.isNullOrEmpty()) words.addView(TextView(ctx).apply { text = subtitle; textSize = 13.5f; setTextColor(P.muted); typeface = font(400)
            setLineSpacing(dpf(ctx, 2f), 1f); setPadding(0, dp(ctx, 2), 0, 0) })
        addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) addView(trailing)
        if (chevron) addView(icon(ctx, Ic.chevron, P.muted, 20))
        if (onClick != null) {
            background = ripple(ctx, null, 0f); isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            contentDescription = title + if (subtitle.isNullOrEmpty()) "" else ". $subtitle"
        }
    }

    /** Readiness line: tick or alert icon, name and state in words. */
    fun status(ctx: Context, name: String, ok: Boolean, state: String): LinearLayout =
        item(ctx, if (ok) Ic.check else Ic.circleAlert, name, state, fg = if (ok) P.good else P.warn, bg = if (ok) P.goodSoft else P.warnSoft)

    fun toggle(ctx: Context, checked: Boolean, description: String, onChange: (Boolean) -> Unit): Switch = Switch(ctx).apply {
        isChecked = checked; contentDescription = description
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(if (P.dark) P.gold else P.accent, if (P.dark) 0xFF8D97AD.toInt() else 0xFFFFFFFF.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(if (P.dark) 0xFF6E5B33.toInt() else 0xFF9DB0EE.toInt(), if (P.dark) 0xFF3A465F.toInt() else 0xFFC9C3B4.toInt()))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(P.stroke)
        layoutParams = LinearLayout.LayoutParams(MATCH, Math.max(1, dp(ctx, 1) / 2)).apply { topMargin = dp(ctx, 12); bottomMargin = dp(ctx, 12) }
    }

    // ------------------------------------------------------------------ status orb + hero

    /** The status dial: soft halo rings, a solid core and an icon. Pulses gently while listening. */
    class Orb(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var color = P.gold; private var coreFill = P.card; private var iconColor = P.gold
        private var hollow = true; private var icon: Drawable? = null
        private var phase = 0f; private var anim: ValueAnimator? = null
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun set(iconRes: Int, accent: Int, core: Int, onCore: Int, hollowCore: Boolean, pulse: Boolean) {
            color = accent; coreFill = core; iconColor = onCore; hollow = hollowCore
            icon = context.getDrawable(iconRes)?.mutate()?.apply { setTint(onCore) }
            anim?.cancel(); anim = null; phase = 0f
            if (pulse && ValueAnimator.areAnimatorsEnabled()) anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = DecelerateInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                if (isAttachedToWindow) start()
            }
            invalidate()
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim?.start() }
        override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

        private fun a(c: Int, alpha: Float) = (c and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

        override fun onDraw(c: Canvas) {
            val r = Math.min(width, height) / 2f; val cx = width / 2f; val cy = height / 2f
            paint.style = Paint.Style.FILL
            paint.color = a(color, 0.07f); c.drawCircle(cx, cy, r, paint)
            paint.color = a(color, 0.11f); c.drawCircle(cx, cy, r * 0.8f, paint)
            if (anim != null) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = r * 0.03f
                paint.color = a(color, 0.55f * (1f - phase)); c.drawCircle(cx, cy, r * (0.58f + 0.42f * phase), paint)
                paint.style = Paint.Style.FILL
            }
            val core = r * 0.58f
            paint.color = coreFill; c.drawCircle(cx, cy, core, paint)
            if (hollow) { paint.style = Paint.Style.STROKE; paint.strokeWidth = Math.max(2f, r * 0.028f); paint.color = color
                c.drawCircle(cx, cy, core, paint); paint.style = Paint.Style.FILL }
            icon?.let { val s = (core * 0.56f).toInt(); it.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt()); it.draw(c) }
        }
    }

    /** Status panel: orb + title + subtitle. `compact` puts a small orb beside the words. */
    class Hero(ctx: Context, val compact: Boolean) : LinearLayout(ctx) {
        val orb = Orb(ctx); val title = TextView(ctx); val subtitle = TextView(ctx)
        val extras = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = if (compact) Gravity.START else Gravity.CENTER_HORIZONTAL }
        init {
            orientation = if (compact) HORIZONTAL else VERTICAL
            gravity = if (compact) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            val p = dp(ctx, if (compact) 18 else 24); setPadding(p, p, p, p)
            val os = dp(ctx, if (compact) 64 else 148)
            addView(orb, LinearLayout.LayoutParams(os, os))
            val words = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = if (compact) Gravity.START else Gravity.CENTER_HORIZONTAL
                if (compact) setPadding(dp(ctx, 16), 0, 0, 0) else setPadding(0, dp(ctx, 16), 0, 0) }
            title.apply { textSize = if (compact) 18f else 23f; typeface = font(700); letterSpacing = -0.02f
                gravity = if (compact) Gravity.START else Gravity.CENTER; setLineSpacing(dpf(ctx, 2f), 1f) }
            subtitle.apply { textSize = if (compact) 14f else 15f; typeface = font(400); gravity = if (compact) Gravity.START else Gravity.CENTER
                setLineSpacing(dpf(ctx, 3f), 1f); setPadding(0, dp(ctx, 6), 0, 0) }
            words.addView(title); words.addView(subtitle); words.addView(extras)
            addView(words, if (compact) LinearLayout.LayoutParams(0, WRAP, 1f) else LinearLayout.LayoutParams(MATCH, WRAP))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(ctx, 12) }
        }
    }

    fun banner(ctx: Context, compact: Boolean = false): Hero = Hero(ctx, compact)

    /** kind: "ready" | "off" | "listen" | "caution" | "stop"; icon optional (a sensible default per kind). */
    fun setState(b: Hero, kind: String, title: String, subtitle: String, iconRes: Int? = null) {
        val ctx = b.context
        when (kind) {
            "stop" -> { b.background = shape(ctx, P.stop, 26f); b.title.setTextColor(P.onStop); b.subtitle.setTextColor(P.onStop)
                b.orb.set(iconRes ?: Ic.octagon, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), P.stop, false, true) }
            "caution" -> { b.background = shape(ctx, P.caution, 26f); b.title.setTextColor(P.onCaution); b.subtitle.setTextColor(P.onCaution)
                b.orb.set(iconRes ?: Ic.triangle, P.onCaution, P.onCaution, P.caution, false, false) }
            "listen" -> { b.background = shape(ctx, P.listen, 26f, P.listenStroke); b.title.setTextColor(P.onListen); b.subtitle.setTextColor(if (P.dark) 0xFFC5CEE6.toInt() else P.muted)
                b.orb.set(iconRes ?: Ic.ear, if (P.dark) 0xFF7C9BFF.toInt() else P.accent, P.accent, P.onAccent, false, true) }
            "off" -> { b.background = shape(ctx, P.card, 26f, P.stroke); b.title.setTextColor(P.text); b.subtitle.setTextColor(P.muted)
                b.orb.set(iconRes ?: Ic.shieldOff, P.muted, P.cardHi, P.muted, true, false) }
            else -> { b.background = shape(ctx, P.card, 26f, P.stroke); b.title.setTextColor(P.text); b.subtitle.setTextColor(P.muted)
                b.orb.set(iconRes ?: Ic.shield, P.gold, if (P.dark) P.cardHi else P.goldSoft, P.goldText, true, false) }
        }
        b.title.text = title; b.subtitle.text = subtitle
        b.subtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        b.contentDescription = "$title. $subtitle"
    }

    /** Detector level while listening / protecting. */
    fun setBanner(b: Hero, level: String, lang: String) {
        val kind = when (level) { "stop" -> "stop"; "caution" -> "caution"; else -> "listen" }
        setState(b, kind, levelTitle(level, lang), levelSubtitle(level, lang))
    }

    // ------------------------------------------------------------------ buttons

    class Btn(ctx: Context) : LinearLayout(ctx) {
        val label = TextView(ctx); val glyph = ImageView(ctx)
        var text: CharSequence get() = label.text; set(v) { label.text = v; contentDescription = v }
        override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    }

    private fun styled(ctx: Context, text: String, fill: Int, stroke: Int?, textColor: Int, iconRes: Int?, onClick: () -> Unit): Btn = Btn(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        minimumHeight = dp(ctx, 56); setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 12))
        if (iconRes != null) { glyph.setImageDrawable(ctx.getDrawable(iconRes)?.mutate()?.apply { setTint(textColor) })
            addView(glyph, LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { rightMargin = dp(ctx, 10) }) }
        label.apply { textSize = 16.5f; setTextColor(textColor); typeface = font(600); gravity = Gravity.CENTER }
        addView(label)
        this.text = text
        background = ripple(ctx, shape(ctx, fill, 18f, stroke), 18f)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5) }
    }

    /** Secondary button. */
    fun button(ctx: Context, label: String, iconRes: Int? = null, onClick: () -> Unit): Btn = styled(ctx, label, P.cardHi, P.stroke, P.text, iconRes, onClick)

    /** The one main action on a screen: cobalt. */
    fun primary(ctx: Context, label: String, iconRes: Int? = null, onClick: () -> Unit): Btn = styled(ctx, label, P.accent, null, P.onAccent, iconRes, onClick)

    /** Stop / destructive action: soft red, readable. */
    fun danger(ctx: Context, label: String, iconRes: Int? = null, onClick: () -> Unit): Btn = styled(ctx, label, P.badSoft, if (P.dark) 0xFF7F2D32.toInt() else 0xFFE9B4B4.toInt(), P.bad, iconRes, onClick)

    fun solid(ctx: Context, label: String, fill: Int, textColor: Int, iconRes: Int? = null, onClick: () -> Unit): Btn = styled(ctx, label, fill, null, textColor, iconRes, onClick)

    /** Small text button (inside rows and cards). */
    fun small(ctx: Context, label: String, filled: Boolean = true, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label; textSize = 14f; typeface = font(600); gravity = Gravity.CENTER
        setTextColor(if (filled) P.onAccent else P.accentText)
        setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9)); minHeight = dp(ctx, 40); minWidth = dp(ctx, 72)
        background = ripple(ctx, shape(ctx, if (filled) P.accent else P.accentSoft, 14f), 14f)
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }

    /** Equal-width row; on narrow screens or very large text it stacks vertically. */
    fun row(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        val narrow = ctx.resources.configuration.screenWidthDp < 340 || ctx.resources.configuration.fontScale > 1.3f
        orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, if (narrow) LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5) }
            else LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(ctx, 10); topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5) })
        }
    }

    /** Two or three choices in one pill; the selected one is filled. */
    fun segmented(ctx: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(ctx, P.cardHi, 18f, P.stroke)
        val pad = dp(ctx, 4); setPadding(pad, pad, pad, pad)
        options.forEachIndexed { i, o ->
            addView(TextView(ctx).apply {
                text = o; textSize = 15f; gravity = Gravity.CENTER; typeface = font(if (i == selected) 600 else 500)
                setTextColor(if (i == selected) P.onAccent else P.muted); minHeight = dp(ctx, 46)
                background = if (i == selected) shape(ctx, P.accent, 14f) else ripple(ctx, null, 14f)
                isClickable = true; isFocusable = true; isSelected = i == selected
                contentDescription = o + if (i == selected) " ✓" else ""
                setOnClickListener { if (i != selected) onSelect(i) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 8) }
    }

    fun input(ctx: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(ctx).apply {
        this.hint = hint; inputType = type; textSize = 16f; typeface = font(400)
        setTextColor(P.text); setHintTextColor(if (P.dark) 0xFF8C96AB.toInt() else 0xFF5A6378.toInt())
        val p = dp(ctx, 16); setPadding(p, p, p, p)
        minHeight = dp(ctx, 54)
        background = shape(ctx, if (P.dark) P.bg else P.cardHi, 16f, P.stroke)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 4); bottomMargin = dp(ctx, 6) }
    }

    // ------------------------------------------------------------------ chips, tiles, bubbles

    /** Small rounded tag with optional icon. Hidden until setPill / chip text is set. */
    class Chip(ctx: Context) : LinearLayout(ctx) {
        val glyph = ImageView(ctx); val label = TextView(ctx)
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
            addView(glyph, LinearLayout.LayoutParams(dp(ctx, 15), dp(ctx, 15)).apply { rightMargin = dp(ctx, 7) })
            label.apply { textSize = 13.5f; typeface = font(600) }
            addView(label)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(ctx, 12) }
            visibility = View.GONE
        }
    }

    fun pill(ctx: Context): Chip = Chip(ctx)

    fun setPill(p: Chip, text: String, fg: Int, bg: Int = P.card, iconRes: Int? = null, stroke: Int? = null) {
        p.label.text = text; p.label.setTextColor(fg); p.visibility = View.VISIBLE
        if (iconRes != null) { p.glyph.visibility = View.VISIBLE; p.glyph.setImageDrawable(p.context.getDrawable(iconRes)?.mutate()?.apply { setTint(fg) }) }
        else p.glyph.visibility = View.GONE
        p.background = shape(p.context, bg, 999f, stroke)
        p.contentDescription = text
    }

    fun chip(ctx: Context, text: String, fg: Int, bg: Int, iconRes: Int? = null): Chip = Chip(ctx).also { setPill(it, text, fg, bg, iconRes) }

    /** Number + caption tile. */
    fun stat(ctx: Context, value: String, caption: String, iconRes: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 14); setPadding(p, p, p, p)
        background = shape(ctx, P.card, 20f, P.stroke)
        addView(icon(ctx, iconRes, P.goldText, 18))
        addView(TextView(ctx).apply { text = value; textSize = 24f; typeface = font(700); setTextColor(P.text); letterSpacing = -0.02f; setPadding(0, dp(ctx, 8), 0, 0) })
        addView(TextView(ctx).apply { text = caption; textSize = 12.5f; typeface = font(500); setTextColor(P.muted); maxLines = 2 })
        contentDescription = "$value $caption"
    }

    /** One line of the live transcript. */
    fun bubble(ctx: Context, s: String, partial: Boolean): TextView = TextView(ctx).apply {
        text = s; textSize = 15.5f; typeface = font(400); setLineSpacing(dpf(ctx, 3f), 1f)
        setTextColor(if (partial) P.muted else P.text)
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        background = shape(ctx, if (partial) Color.TRANSPARENT else P.cardHi, 16f, if (partial) P.stroke else null)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(ctx, 4); bottomMargin = dp(ctx, 4); rightMargin = dp(ctx, 24) }
    }

    /** First sentence of an explanation (shown as a headline) and the rest (shown as body text). */
    fun splitLead(s: String): Pair<String, String> {
        val t = s.trim()
        val m = Regex("""^(.{12,140}?[.!?।])\s+(.+)$""", RegexOption.DOT_MATCHES_ALL).find(t) ?: return t to ""
        return m.groupValues[1] to m.groupValues[2]
    }

    /** Centered icon + two lines, for empty lists. */
    fun empty(ctx: Context, iconRes: Int, title: String, subtitle: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(ctx, 24), dp(ctx, 36), dp(ctx, 24), dp(ctx, 36))
        addView(iconTile(ctx, iconRes, P.goldText, P.goldSoft, 56))
        addView(text(ctx, title, 17f, weight = 600).apply { gravity = Gravity.CENTER; setPadding(0, dp(ctx, 14), 0, 0) })
        addView(text(ctx, subtitle, 14f, color = P.muted).apply { gravity = Gravity.CENTER })
    }

    /** Thin progress bar (done of total). */
    fun progress(ctx: Context, done: Int, total: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (i in 0 until total) addView(View(ctx).apply { background = shape(ctx, if (i < done) P.gold else P.stroke, 3f) },
            LinearLayout.LayoutParams(0, dp(ctx, 5), 1f).apply { if (i > 0) leftMargin = dp(ctx, 6) })
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 12); bottomMargin = dp(ctx, 6) }
    }

    /** Setup step: numbered row with an action until done, then a tick. */
    fun step(ctx: Context, n: Int, done: Boolean, title: String, subtitle: String, action: String, onClick: () -> Unit): LinearLayout =
        item(ctx, if (done) Ic.check else when (n) { 1 -> Ic.message; 2 -> Ic.scan; else -> Ic.shield }, title, subtitle,
            trailing = if (done) null else small(ctx, action) { onClick() },
            fg = if (done) P.good else P.goldText, bg = if (done) P.goodSoft else P.goldSoft)

    /** Privacy line with a lock, for the bottom of a screen. */
    fun footnote(ctx: Context, s: String, iconRes: Int = Ic.lock): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 6))
        addView(icon(ctx, iconRes, P.muted, 14))
        addView(TextView(ctx).apply { text = s; textSize = 12.5f; typeface = font(500); setTextColor(P.muted); setPadding(dp(ctx, 8), 0, 0, 0) })
    }

    // ------------------------------------------------------------------ bottom navigation

    /** Screen frame: scrolling content above a fixed bottom bar. Returns (root, content holder). */
    fun tabs(a: Activity, labels: List<String>, icons: List<Int>, selected: Int, onSelect: (Int) -> Unit): Pair<LinearLayout, FrameLayout> {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(P.bg) }
        val holder = FrameLayout(a)
        root.addView(holder, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(View(a).apply { setBackgroundColor(P.stroke) }, LinearLayout.LayoutParams(MATCH, Math.max(1, dp(a, 1) / 2)))
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(P.card); setPadding(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 8)) }
        labels.forEachIndexed { i, l ->
            val on = i == selected
            bar.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(a, 54)
                val pillBox = FrameLayout(a).apply { background = if (on) shape(a, P.accentSoft, 16f) else null
                    val s = dp(a, 22); addView(ImageView(a).apply { setImageDrawable(a.getDrawable(icons[i])?.mutate()?.apply { setTint(if (on) P.accentText else P.muted) }) }, FrameLayout.LayoutParams(s, s, Gravity.CENTER)) }
                addView(pillBox, LinearLayout.LayoutParams(dp(a, 60), dp(a, 32)))
                addView(TextView(a).apply { text = l; textSize = 12f; typeface = font(if (on) 600 else 500); setTextColor(if (on) P.text else P.muted); setPadding(0, dp(a, 4), 0, 0); maxLines = 1; gravity = Gravity.CENTER })
                background = ripple(a, null, 16f); isClickable = true; isFocusable = true; isSelected = on
                contentDescription = l
                setOnClickListener { if (!on) onSelect(i) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(bar)
        return root to holder
    }

    // ------------------------------------------------------------------ system

    fun ensureChannel(ctx: Context, id: String, name: String, importance: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    fun ongoing(ctx: Context, channel: String, title: String, body: String, icon: Int, intent: android.app.PendingIntent?): Notification =
        Notification.Builder(ctx, channel).setContentTitle(title).setContentText(body).setSmallIcon(icon).setOngoing(true)
            .apply { if (intent != null) setContentIntent(intent) }.build()

    fun keepScreenOn(a: Activity) { a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
}
