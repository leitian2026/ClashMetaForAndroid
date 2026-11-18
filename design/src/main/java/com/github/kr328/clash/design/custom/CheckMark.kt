package com.github.kr328.clash.design.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan

/**
 * CUSTOM (CUSTOM.md #57, not upstream): every ✓ / ✔ is drawn as a solid green circle with a white check inside, and
 * every ✗ / ✘ as a solid red circle with a white cross inside (CUSTOM.md #154). The icon is drawn with a path, not
 * with the font's glyph, in fixed, fully saturated colours that do not change with the day/night theme.
 *
 * The icon is drawing only: the underlying text is unchanged, including when copied or exported.
 */
object CheckMark {
    private const val CHECK = '\u2713' // ✓
    private const val CROSS = '\u2717' // ✗
    private const val CHECK_HEAVY = '\u2714' // ✔
    private const val CROSS_HEAVY = '\u2718' // ✘

    // Fixed colour of the "in use" dot on the proxy page (ProxyView), independent of the theme and card background.
    const val DOT_GOOD: Int = -12724657 // #FF3DD64F, same as the "已应用" badge background in the editor history list (EditorActivity, CUSTOM.md #156 step 33)

    // Fixed, fully saturated colours of the circle behind every ✓ / ✗ (CUSTOM.md #153), independent of the theme.
    const val MARK_DOT_GOOD: Int = -16718218 // #FF00E676
    const val MARK_DOT_BAD: Int = -59580 // #FFFF1744

    // Width in pixels of the same-colour outline of the circle; it makes the anti-aliased edge pixels more solid.
    private const val DOT_STROKE = 0.5f

    private class MarkSpan(private val good: Boolean) : ReplacementSpan() {
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = DOT_STROKE
            color = if (good) MARK_DOT_GOOD else MARK_DOT_BAD
        }

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }

        private val path = android.graphics.Path()

        /** Whole pixels, so the circle's edges sit on pixel boundaries: the diameter is even, the gap is an integer. */
        private fun diameter(paint: Paint): Int = maxOf(Math.round(paint.textSize * 0.6f) and 1.inv(), 2)

        private fun gap(paint: Paint): Int = Math.round(paint.textSize * 0.2f)

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            return diameter(paint) + gap(paint)
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
            val d = diameter(paint)
            val cx = Math.round(x).toFloat() + d / 2f
            val cy = Math.round(y + (paint.ascent() + paint.descent()) / 2f).toFloat()

            // The outline grows the circle by half its width on every side; take that off the radius so the
            // circle keeps the diameter above.
            canvas.drawCircle(cx, cy, d / 2f - DOT_STROKE / 2f, circlePaint)

            linePaint.strokeWidth = d * 0.12f
            path.rewind()

            if (good) {
                path.moveTo(cx - d * 0.25f, cy + d * 0.02f)
                path.lineTo(cx - d * 0.07f, cy + d * 0.20f)
                path.lineTo(cx + d * 0.25f, cy - d * 0.18f)
            } else {
                path.moveTo(cx - d * 0.20f, cy - d * 0.20f)
                path.lineTo(cx + d * 0.20f, cy + d * 0.20f)
                path.moveTo(cx - d * 0.20f, cy + d * 0.20f)
                path.lineTo(cx + d * 0.20f, cy - d * 0.20f)
            }

            canvas.drawPath(path, linePaint)
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
                CHECK, CHECK_HEAVY -> text.setSpan(MarkSpan(true), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                CROSS, CROSS_HEAVY -> text.setSpan(MarkSpan(false), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
