package com.github.kr328.clash.design.custom.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.style.LeadingMarginSpan
import android.text.TextUtils

/**
 * Applies continuation-line margins when text changes and draws separators for visible wrapped lines.
 * The renderer keeps no time-based data: spans are updated on edits, loads and width changes.
 */
internal class EditorWrapRenderer(
    private val density: Float,
    separatorColor: Int,
) {
    private class ContinuationIndentSpan(private val marginPx: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean): Int = if (first) 0 else marginPx

        override fun drawLeadingMargin(
            canvas: Canvas,
            paint: Paint,
            x: Int,
            dir: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            first: Boolean,
            layout: Layout,
        ) = Unit
    }

    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = separatorColor
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, density)
    }

    private var appliedColumns = -1

    /** Rebuilds spans after loading a new editor window or changing its available width. */
    fun updateAll(text: Editable, lineStarts: IntArray, visibleColumns: Int, charWidth: Float, force: Boolean = false) {
        if (!force && visibleColumns == appliedColumns) return
        for (span in text.getSpans(0, text.length, ContinuationIndentSpan::class.java)) text.removeSpan(span)
        appliedColumns = visibleColumns
        if (visibleColumns <= 0) return
        for (line in lineStarts.indices) applyLine(text, lineStarts, line, visibleColumns, charWidth)
    }

    /** Updates only the logical lines touched by the edit. */
    fun updateEditedLines(
        text: Editable,
        lineStarts: IntArray,
        editStart: Int,
        insertedLength: Int,
        visibleColumns: Int,
        charWidth: Float,
    ) {
        if (visibleColumns <= 0) return
        if (visibleColumns != appliedColumns) {
            updateAll(text, lineStarts, visibleColumns, charWidth, force = true)
            return
        }

        val first = lineIndexAt(editStart.coerceIn(0, text.length), lineStarts)
        val last = lineIndexAt((editStart + insertedLength).coerceIn(0, text.length), lineStarts)
        val from = lineStarts[first]
        val to = lineEndExclusive(last, text.length, lineStarts)
        for (span in text.getSpans(from, to, ContinuationIndentSpan::class.java)) text.removeSpan(span)
        for (line in first..last) applyLine(text, lineStarts, line, visibleColumns, charWidth)
    }

    /** Clears the old window's derived spans and initializes the new window. */
    fun reset(text: Editable, lineStarts: IntArray, visibleColumns: Int, charWidth: Float) {
        for (span in text.getSpans(0, text.length, ContinuationIndentSpan::class.java)) text.removeSpan(span)
        appliedColumns = -1
        updateAll(text, lineStarts, visibleColumns, charWidth, force = true)
    }

    private fun applyLine(
        text: Editable,
        lineStarts: IntArray,
        line: Int,
        visibleColumns: Int,
        charWidth: Float,
    ) {
        val start = lineStarts[line]
        val endExclusive = lineEndExclusive(line, text.length, lineStarts)
        var contentEnd = endExclusive
        if (contentEnd > start && text[contentEnd - 1] == '\n') contentEnd--
        if (contentEnd > start && text[contentEnd - 1] == '\r') contentEnd--
        val length = contentEnd - start
        if (length <= visibleColumns) return

        val indent = EditorWrapStyle.leadingIndentColumns(TextUtils.substring(text, start, contentEnd))
        val margin = ((indent + 4) * charWidth).toInt().coerceAtLeast(1)
        val spanEnd = endExclusive.coerceAtLeast(start + 1)
        if (spanEnd <= text.length) {
            text.setSpan(ContinuationIndentSpan(margin), start, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Draws a separator only when the last display row of a wrapped logical line is visible. */
    fun drawSeparators(
        canvas: Canvas,
        layout: Layout,
        text: Editable,
        firstVisibleDisplayLine: Int,
        lastVisibleDisplayLine: Int,
        lineStarts: IntArray,
        visibleColumns: Int,
        padLeft: Int,
        padRight: Int,
        padTop: Int,
        viewWidth: Int,
        scrollX: Int,
    ) {
        if (visibleColumns <= 0 || lineStarts.isEmpty()) return

        for (displayLine in firstVisibleDisplayLine..lastVisibleDisplayLine) {
            val displayStart = layout.getLineStart(displayLine).coerceIn(0, text.length)
            val logicalIndex = lineIndexAt(displayStart, lineStarts)
            val logicalStart = lineStarts[logicalIndex]
            val logicalEnd = lineEnd(logicalIndex, text.length, lineStarts)
            val logicalLength = logicalEnd - logicalStart
            if (logicalLength <= visibleColumns) continue

            val firstDisplay = layout.getLineForOffset(logicalStart)
            val lastOffset = (logicalEnd - 1).coerceAtLeast(logicalStart)
            val lastDisplay = layout.getLineForOffset(lastOffset)
            val displayCount = lastDisplay - firstDisplay + 1
            if (!EditorWrapStyle.needsSeparator(displayCount) || displayLine != lastDisplay) continue

            val y = (layout.getLineBottom(lastDisplay) + padTop).toFloat()
            canvas.drawLine(
                (scrollX + padLeft).toFloat(),
                y,
                (scrollX + viewWidth - padRight).toFloat(),
                y,
                separatorPaint,
            )
        }
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
        return lineEndExclusive(line, length, lineStarts).let { end ->
            if (end > lineStarts[line] && end <= length && line + 1 < lineStarts.size) end - 1 else end
        }
    }

    private fun lineEndExclusive(line: Int, length: Int, lineStarts: IntArray): Int {
        return if (line + 1 < lineStarts.size) lineStarts[line + 1] else length
    }
}
