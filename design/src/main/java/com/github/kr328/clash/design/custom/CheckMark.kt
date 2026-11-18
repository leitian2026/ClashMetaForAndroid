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
 * (bold, plus the extra stroke of "fake bold"). A fixed-colour dot follows each mark; the glyph colours retain
 * their existing day/night theme adaptation.
 *
 * The mark and dot are drawing only: the underlying text is unchanged, including when copied or exported.
 */
object CheckMark {
    private const val CHECK = '\u2713' // ✓
    private const val CROSS = '\u2717' // ✗
    private const val CHECK_HEAVY = '\u2714' // ✔
    private const val CROSS_HEAVY = '\u2718' // ✘

    // Fixed dot colours, independent of the day/night theme and card background.
    const val DOT_GOOD = 0xFF4ADE80
    const val DOT_BAD = 0xFFFF6B6B

    private fun night(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun good(context: Context): Int = if (night(context)) Color.parseColor("#00E676") else Color.parseColor("#005A0A")

    fun bad(context: Context): Int = if (night(context)) Color.parseColor("#FF5252") else Color.parseColor("#A50000")

    private class MarkSpan(private val color: Int, private val dotColor: Int) : ReplacementSpan() {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private fun markPaint(source: Paint): TextPaint {
            return TextPaint(source).apply {
                this.color = color
                typeface = Typeface.create(typeface, Typeface.BOLD)
                isFakeBoldText = true
            }
        }

        private fun dotMetrics(paint: Paint): Pair<Float, Float> {
            val gap = paint.textSize * 0.25f
            val diameter = paint.textSize * 0.5f
            return gap to diameter
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            val glyph = markPaint(paint).measureText(text, start, end)
            val (gap, diameter) = dotMetrics(paint)
            return (glyph + diameter + gap * 2).toInt()
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
            val glyphWidth = glyphPaint.measureText(text, start, end)
            val (gap, diameter) = dotMetrics(paint)

            val mark = text.subSequence(start, end).toString()
            canvas.drawText(mark, x, y.toFloat(), glyphPaint)

            dotPaint.color = dotColor
            val centerY = y + (paint.ascent() + paint.descent()) / 2f
            canvas.drawCircle(x + glyphWidth + gap + diameter / 2f, centerY, diameter / 2f, dotPaint)
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
                CHECK, CHECK_HEAVY -> text.setSpan(MarkSpan(good(context), DOT_GOOD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                CROSS, CROSS_HEAVY -> text.setSpan(MarkSpan(bad(context), DOT_BAD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
