package com.github.kr328.clash.design.custom.editor

import kotlin.math.floor

/**
 * Pure layout policy for long logical lines in the editor (CUSTOM #50).
 * A logical line's length excludes its terminating newline.
 */
internal object EditorWrapStyle {
    fun visibleColumns(contentWidthPx: Int, charWidthPx: Float): Int {
        if (contentWidthPx <= 0 || charWidthPx <= 0f) return 0
        return floor(contentWidthPx / charWidthPx).toInt().coerceAtLeast(1)
    }

    fun leadingIndentColumns(line: CharSequence): Int {
        var index = 0
        while (index < line.length && line[index] == ' ') index++
        return index
    }

    fun continuationIndentColumns(line: CharSequence, visibleColumns: Int): Int? {
        if (visibleColumns <= 0 || line.length <= visibleColumns) return null
        return leadingIndentColumns(line) + CONTINUATION_EXTRA_COLUMNS
    }

    fun needsSeparator(displayLineCount: Int): Boolean = displayLineCount >= 2

    private const val CONTINUATION_EXTRA_COLUMNS = 4
}
