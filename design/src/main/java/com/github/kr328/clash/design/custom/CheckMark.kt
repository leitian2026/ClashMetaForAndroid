package com.github.kr328.clash.design.custom

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle

/**
 * CUSTOM (CUSTOM.md #57, not upstream): every ✓ in the app is drawn green and every ✗ red, in the heaviest weight
 * (bold, plus the extra stroke of "fake bold") and in the brightest green and red, by day and by night.
 *
 * It only looks at the text it is given, once, when the text is put on the screen (nothing runs on its own).
 */
object CheckMark {
    private const val CHECK = '\u2713' // ✓
    private const val CROSS = '\u2717' // ✗
    private const val CHECK_HEAVY = '\u2714' // ✔
    private const val CROSS_HEAVY = '\u2718' // ✘

    private val GOOD = Color.parseColor("#00E676")
    private val BAD = Color.parseColor("#FF5252")

    fun good(context: Context): Int = GOOD

    fun bad(context: Context): Int = BAD

    private class MarkSpan(private val color: Int) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            tp.color = color
            tp.typeface = Typeface.create(tp.typeface, Typeface.BOLD)
            tp.isFakeBoldText = true
        }
    }

    /** [text] with every ✓ and ✗ painted; the text itself is not changed. A null stays null. */
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

    /** Paints the ✓ and ✗ in [from, to) of a text that is being built. */
    fun apply(context: Context, text: Spannable, from: Int, to: Int) {
        val end = minOf(to, text.length)

        for (i in from until end) {
            when (text[i]) {
                CHECK, CHECK_HEAVY -> text.setSpan(MarkSpan(good(context)), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                CROSS, CROSS_HEAVY -> text.setSpan(MarkSpan(bad(context)), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
