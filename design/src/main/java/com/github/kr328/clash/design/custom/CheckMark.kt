package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan

/**
 * CUSTOM (CUSTOM.md #57, not upstream): every ✓ / ✔ is drawn green and every ✗ / ✘ red, in the heaviest weight
 * (bold, plus the extra stroke of "fake bold"). A fixed-colour, fully saturated dot follows each mark (pixel-aligned); the glyph colours retain
 * their existing day/night theme adaptation.
 *
 * The mark and dot are drawing only: the underlying text is unchanged, including when copied or exported.
 */
object CheckMark {
    private const val CHECK = '\u2713' // ✓
    private const val CROSS = '\u2717' // ✗
    private const val CHECK_HEAVY = '\u2714' // ✔
    private const val CROSS_HEAVY = '\u2718' // ✘

    // Fixed colour of the "in use" dot on the proxy page (ProxyView), independent of the theme and card background.
    const val DOT_GOOD: Int = -11870592 // #FF4ADE80

    // Fixed, fully saturated colours of the dot behind every ✓ / ✗ (CUSTOM.md #153), independent of the theme.
    const val MARK_DOT_GOOD: Int = -16718218 // #FF00E676
    const val MARK_DOT_BAD: Int = -59580 // #FFFF1744

    // Width in pixels of the same-colour outline of the dot; it makes the anti-aliased edge pixels more solid.
    private const val DOT_STROKE = 0.5f

    private fun night(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun good(context: Context): Int = if (night(context)) Color.parseColor("#00E676") else Color.parseColor("#005A0A")

    fun bad(context: Context): Int = if (night(context)) Color.parseColor("#FF5252") else Color.parseColor("#A50000")

    private class MarkSpan(private val color: Int, private val dotColor: Int) : ReplacementSpan() {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = DOT_STROKE
        }

        private fun markPaint(source: Paint): TextPaint {
            return TextPaint(source).apply {
                this.color = color
                typeface = Typeface.create(typeface, Typeface.BOLD)
                isFakeBoldText = true
            }
        }

        /** Whole pixels, so the circle's edges sit on pixel boundaries: the diameter is even, the gap is an integer. */
        private fun dotMetrics(paint: Paint): Pair<Int, Int> {
            val gap = Math.round(paint.textSize * 0.25f)
            val diameter = Math.round(paint.textSize * 0.65f) and 1.inv()

            return gap to maxOf(diameter, 2)
        }

        private fun glyphWidth(paint: Paint, text: CharSequence, start: Int, end: Int): Int {
            return Math.ceil(markPaint(paint).measureText(text, start, end).toDouble()).toInt()
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            val (gap, diameter) = dotMetrics(paint)

            return glyphWidth(paint, text, start, end) + diameter + gap * 2
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val glyphPaint = markPaint(paint)
            val (gap, diameter) = dotMetrics(paint)
            val glyph = glyphWidth(paint, text, start, end)

            val mark = text.subSequence(start, end).toString()
            canvas.drawText(mark, x, y.toFloat(), glyphPaint)

            // The stroke grows the circle by half its width on every side; take that off the radius so the dot
            // keeps the diameter above.
            dotPaint.color = dotColor
            val radius = diameter / 2f - DOT_STROKE / 2f
            val centerX = Math.round(x + glyph + gap).toFloat() + diameter / 2f
            val centerY = Math.round(y + (paint.ascent() + paint.descent()) / 2f).toFloat()

            canvas.drawCircle(centerX, centerY, radius, dotPaint)
        }
    }

    /** [text] with every ✓, ✗, ✔ and ✘ painted; the text itself is not changed. A null stays null. */
    fun paint(context: Context, text: CharSequence?): CharSequence? {
        if (text == null) return null

        var found = false

        for (c in text) {
            if (c == CHECK || c == CROSS || c == CHECK_HEAVY || c == CROSS_HEAVY) {
                found = true

                break
            }
        }

        if (!found) return text

        val out = if (text is Spannable) text else SpannableString(text)

        apply(context, out, 0, out.length)

        return out
    }

    /** Paints the ✓, ✗, ✔ and ✘ in [from, to) of a text that is being built. */
    fun apply(context: Context, text: Spannable, from: Int, to: Int) {
        val end = minOf(to, text.length)

        for (i in from until end) {
            when (text[i]) {
                CHECK, CHECK_HEAVY -> text.setSpan(MarkSpan(good(context), MARK_DOT_GOOD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                CROSS, CROSS_HEAVY -> text.setSpan(MarkSpan(bad(context), MARK_DOT_BAD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
