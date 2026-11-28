package com.github.kr328.clash.design.custom.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Layout
import android.text.TextUtils

/**
 * Drawing state and helpers for the editor's existing indent guides and visible whitespace.
 * This is a behavior-preserving extraction from CodeEditText (CUSTOM #50).
 */
internal class EditorGuideRenderer(
    private val colorGuide: Int,
    private val colorGutterText: Int,
    private val colorTrailing: Int,
    private val colorTabBg: Int,
) {
    private val guidePaint = Paint().apply {
        color = colorGuide
    }
    private val fillPaint = Paint()
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = colorGutterText
    }

    fun drawGuides(
        canvas: Canvas,
        layout: Layout,
        text: Editable,
        displayLine: Int,
        start: Int,
        padLeft: Int,
        padTop: Int,
        charW: Float,
        density: Float,
        lineStarts: IntArray,
    ) {
        guidePaint.strokeWidth = maxOf(1f, density)
        val lineEnd = layout.getLineEnd(displayLine)
        var indent = 0

        while (start + indent < lineEnd && text[start + indent] == ' ') indent++

        if (start + indent >= lineEnd || text[start + indent] == '\n' || text[start + indent] == '\r') {
            indent = blankDepth(text, start, lineStarts)
        }

        if (indent < 3) return

        val top = (layout.getLineTop(displayLine) + padTop).toFloat()
        val bottom = (layout.getLineBottom(displayLine) + padTop).toFloat()
        var column = 2

        while (column < indent) {
            val x = padLeft + column * charW
            canvas.drawLine(x, top, x, bottom, guidePaint)
            column += 2
        }
    }

    private fun blankDepth(text: Editable, start: Int, lineStarts: IntArray): Int {
        val index = lineIndexAt(start, lineStarts)
        var above = 0
        var i = index - 1
        var steps = 0

        while (i >= 0 && steps < 60) {
            val depth = indentAt(text, lineStarts[i], lineEnd(i, text.length, lineStarts))
            if (depth >= 0) {
                above = depth
                break
            }
            i--
            steps++
        }

        var below = 0
        i = index + 1
        steps = 0

        while (i < lineStarts.size && steps < 60) {
            val depth = indentAt(text, lineStarts[i], lineEnd(i, text.length, lineStarts))
            if (depth >= 0) {
                below = depth
                break
            }
            i++
            steps++
        }

        return minOf(above, below)
    }

    private fun indentAt(text: CharSequence, start: Int, end: Int): Int {
        var index = start
        while (index < end && text[index] == ' ') index++
        return if (index >= end || text[index] == '\r') -1 else index - start
    }

    fun drawWhitespace(
        canvas: Canvas,
        layout: Layout,
        text: Editable,
        displayLine: Int,
        start: Int,
        padLeft: Int,
        padTop: Int,
        charW: Float,
        density: Float,
        selectionStart: Int,
        selectionEnd: Int,
        mayHaveTab: Boolean,
    ) {
        val lineEnd = layout.getLineEnd(displayLine).coerceAtMost(text.length)
        if (lineEnd <= start) return

        val top = (layout.getLineTop(displayLine) + padTop).toFloat()
        val bottom = (layout.getLineBottom(displayLine) + padTop).toFloat()
        val endsLogical = lineEnd >= text.length || text[lineEnd - 1] == '\n'
        val textEnd = if (text[lineEnd - 1] == '\n') lineEnd - 1 else lineEnd

        if (mayHaveTab) {
            tabPaint.textSize = layout.paint.textSize * 0.9f
            for (index in start until textEnd) {
                if (text[index] != '\t') continue
                val x1 = padLeft + layout.getPrimaryHorizontal(index)
                val x2 = padLeft + (if (index + 1 < lineEnd) layout.getPrimaryHorizontal(index + 1) else layout.getLineRight(displayLine))
                fillPaint.color = colorTabBg
                canvas.drawRect(x1, top, x2, bottom, fillPaint)
                canvas.drawText("→", (x1 + x2) / 2f, (layout.getLineBaseline(displayLine) + padTop).toFloat(), tabPaint)
            }
        }

        if (!endsLogical) return

        var end = textEnd
        if (end > start && text[end - 1] == '\r') end--
        var from = end
        while (from > start && (text[from - 1] == ' ' || text[from - 1] == '\t')) from--
        if (from == end) return
        if (selectionStart == selectionEnd && selectionStart in from..end) return

        val x1 = padLeft + layout.getPrimaryHorizontal(from)
        val x2 = padLeft + layout.getPrimaryHorizontal(end)
        fillPaint.color = colorTrailing
        canvas.drawRect(x1, top, x2, bottom, fillPaint)
    }

    private fun lineIndexAt(offset: Int, lineStarts: IntArray): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    private fun lineEnd(line: Int, length: Int, lineStarts: IntArray): Int {
        return if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else length
    }
}
