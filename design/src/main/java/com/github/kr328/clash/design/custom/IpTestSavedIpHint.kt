package com.github.kr328.clash.design.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import com.github.kr328.clash.design.R
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import kotlin.math.ceil

/**
 * CUSTOM #43: draw saved-IP hints inside the matching input lines.
 * Read the saved snapshot once per dialog; input edits only compare against this cached set.
 */
internal object IpTestSavedIpHint {
    private data class LineRange(val start: Int, val end: Int)

    private class SavedIpLineSpan(
        private val endExclusive: Int,
        private val extraHeight: Int,
        private val label: String,
        private val textSizePx: Float,
        private val textColor: Int,
        private val horizontalPadding: Float,
    ) : LineHeightSpan, LineBackgroundSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: Paint.FontMetricsInt,
        ) {
            // A wrapped paragraph may call this for several visual lines. Enlarge only its last one.
            if (end == endExclusive) {
                fm.descent += extraHeight
                fm.bottom += extraHeight
            }
        }

        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int,
        ) {
            // Draw once, in the extra space below the final visual line of this input row.
            if (end != endExclusive) return

            val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSizePx
                color = textColor
                alpha = (Color.alpha(textColor) * 0.75f).toInt().coerceIn(0, 255)
            }
            val metrics = hintPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val extraTop = bottom - extraHeight
            val hintBaseline = extraTop + (extraHeight - textHeight) / 2f - metrics.ascent
            canvas.drawText(label, left + horizontalPadding, hintBaseline, hintPaint)
        }
    }

    fun create(context: Context, input: TextInputEditText) {
        val saved = SavedIpStore.read(context).operators
            .asSequence()
            .flatMap { it.ips.asSequence() }
            .map { normalize(it.address) }
            .filter { it.isNotEmpty() }
            .toHashSet()

        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        val textSizePx = 12f * scaledDensity
        val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textSizePx }
        val metrics = measurePaint.fontMetrics
        val extraHeight = ceil(metrics.descent - metrics.ascent).toInt() + (2f * density).toInt()
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val textColor = try {
            attributes.getColor(0, 0xFF666666.toInt())
        } finally {
            attributes.recycle()
        }
        val label = context.getString(R.string.iptest_saved_ip_exists)
        val horizontalPadding = 2f * density

        var lastRanges = emptyList<LineRange>()
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                val text = editable ?: return
                val newRanges = findMatchingLines(text, saved)
                if (newRanges == lastRanges) return

                text.getSpans(0, text.length, SavedIpLineSpan::class.java)
                    .forEach(text::removeSpan)

                for (range in newRanges) {
                    text.setSpan(
                        SavedIpLineSpan(
                            endExclusive = range.end,
                            extraHeight = extraHeight,
                            label = label,
                            textSizePx = textSizePx,
                            textColor = textColor,
                            horizontalPadding = horizontalPadding,
                        ),
                        range.start,
                        range.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                lastRanges = newRanges
            }
        }
        input.addTextChangedListener(watcher)
        watcher.afterTextChanged(input.text)
    }

    private fun findMatchingLines(text: CharSequence, saved: Set<String>): List<LineRange> {
        if (text.isEmpty() || saved.isEmpty()) return emptyList()

        val matches = ArrayList<LineRange>()
        var lineStart = 0
        while (lineStart < text.length) {
            var contentEnd = lineStart
            while (contentEnd < text.length && text[contentEnd] != '\n') contentEnd++

            var tokenStart = lineStart
            while (tokenStart < contentEnd && text[tokenStart].isWhitespace()) tokenStart++
            var tokenEnd = tokenStart
            while (tokenEnd < contentEnd && !isTokenDelimiter(text[tokenEnd])) tokenEnd++

            if (tokenEnd > tokenStart) {
                val token = text.subSequence(tokenStart, tokenEnd).toString()
                // Avoid treating arbitrary words as addresses; no DNS or network parsing is performed.
                if ((token.any { it == '.' } || token.any { it == ':' }) &&
                    token.all { it.isLetterOrDigit() || it in ".:[]%_-" }
                ) {
                    val address = normalize(token)
                    if (address.isNotEmpty() && address in saved) {
                        val lineEnd = if (contentEnd < text.length) contentEnd + 1 else contentEnd
                        matches += LineRange(lineStart, lineEnd)
                    }
                }
            }

            lineStart = if (contentEnd < text.length) contentEnd + 1 else text.length
        }
        return matches
    }

    private fun isTokenDelimiter(char: Char): Boolean =
        char.isWhitespace() || char == ',' || char == '，' || char == '#' ||
            char == ';' || char == '；' || char == '、'

    private fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("[") && value.contains(']')) {
            value = value.substring(1, value.indexOf(']'))
        } else if (value.count { it == ':' } == 1 && value.substringAfterLast(':').all(Char::isDigit)) {
            value = value.substringBeforeLast(':')
        }
        return value.lowercase(Locale.ROOT)
    }
}
