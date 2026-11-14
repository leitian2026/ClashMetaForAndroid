package com.github.kr328.clash.design.custom.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EdgeEffect
import android.widget.ListView
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText

// CUSTOM (CUSTOM.md #46, not upstream): the text field of the built-in editor. It keeps the normal EditText (keyboard,
// selection, cursor) and adds: line numbers, YAML colours for the lines on the screen, undo / redo, find and
// replace, indent keeping on a new line, the line of an error marked, and a list of suggestions at the caret
// (only when a schema is set). Nothing runs on its own: every step is a reaction to typing or a tap. The colours
// (CUSTOM.md #48) are put on the whole text once when it is opened and then only on the lines an edit touches, so
// scrolling does no colouring work; a very big text is coloured only around the screen, as before.
// CUSTOM.md #50 adds: a text that does not fit in 3000 lines is shown a window at a time (the rest is kept as plain
// strings), indent guides, the bracket or quote that goes with the one at the caret (red when it has no partner),
// Tabs and trailing spaces made visible, and a dot at the line number of every problem a background check of the whole
// text found (it runs once after the typing stops, not on a timer).
class CodeEditText(context: Context) : AppCompatEditText(context) {
    private val density = context.resources.displayMetrics.density
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val colorKey = if (night) Color.parseColor("#82AAFF") else Color.parseColor("#1565C0")
    private val colorStr = if (night) Color.parseColor("#C3E88D") else Color.parseColor("#2E7D32")
    private val colorNum = if (night) Color.parseColor("#F78C6C") else Color.parseColor("#E65100")
    private val colorWord = if (night) Color.parseColor("#C792EA") else Color.parseColor("#7B1FA2")
    private val colorAnchor = if (night) Color.parseColor("#FFCB6B") else Color.parseColor("#8D6E00")
    private val colorComment = if (night) Color.parseColor("#7F8C98") else Color.parseColor("#757575")
    private val colorPunct = if (night) Color.parseColor("#89DDFF") else Color.parseColor("#00838F")
    private val colorGutterText = if (night) Color.parseColor("#7F8C98") else Color.parseColor("#9E9E9E")
    private val colorGutterBg = if (night) Color.parseColor("#1F2328") else Color.parseColor("#F1F3F4")
    private val colorErrorBand = if (night) Color.parseColor("#55E57373") else Color.parseColor("#33D32F2F")
    private val colorMatch = if (night) Color.parseColor("#88FFB300") else Color.parseColor("#88FFEB3B")
    private val colorGuide = if (night) Color.parseColor("#2EFFFFFF") else Color.parseColor("#26000000")
    private val colorTrailing = if (night) Color.parseColor("#66FF8A65") else Color.parseColor("#55FF7043")
    private val colorTabBg = if (night) Color.parseColor("#44FFCA28") else Color.parseColor("#55FFB300")
    private val colorPair = Color.parseColor("#664FC3F7")
    private val colorPairBad = Color.parseColor("#88F44336")
    private val colorDiagError = Color.parseColor("#E53935")
    private val colorDiagWarn = Color.parseColor("#FB8C00")

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
        color = colorGutterText
    }
    private val gutterPaint = Paint().apply { color = colorGutterBg }
    private val bandPaint = Paint().apply { color = colorErrorBand }
    private val fillPaint = Paint()
    private val guidePaint = Paint().apply {
        color = colorGuide
        strokeWidth = maxOf(1f, density)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = colorGutterText
    }

    private class TokSpan(color: Int) : ForegroundColorSpan(color)

    private class MatchSpan(color: Int) : BackgroundColorSpan(color)

    val undo = UndoStack()

    /** The schema to suggest from; null: only words of the text are suggested. */
    var schema: ClashSchema? = null
        set(value) {
            field = value
            completion = value?.let { ClashCompletion(it) }
        }

    /** Called after every change of the text that is not the loading of it. */
    var onEdited: (() -> Unit)? = null

    /** Called when the caret moved or the text changed (for the line and column shown). */
    var onCaretChanged: (() -> Unit)? = null

    /** CUSTOM.md #50: Tabs and spaces at the end of a line are drawn (set by the page, kept as a choice of the user). */
    var showWhitespace: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var completion: ClashCompletion? = null

    private var lineStarts = intArrayOf(0)
    private var gutterWidth = 0
    private var gutterDigits = 0

    private var ready = false // the super class calls onSelectionChanged while it is being built, before our fields exist
    private var loading = false
    private var replaying = false // an undo or redo is being applied: not recorded
    private var quiet = false // an edit of the editor itself (a chosen suggestion, indent): no suggestions, no indent
    private var autoIndenting = false
    private var editing = false

    // CUSTOM.md #50: a big text is shown a window at a time. `before` is the text of the lines above the window (it ends
    // with a new line), `after` the text of the lines below it (it starts with a new line); the editable text is only the
    // window. A text that fits in one window has both empty. Every line number that is shown counts the whole text.
    private var before = ""
    private var after = ""
    private var lineOffset = 0 // the number of lines in `before`
    private var afterLines = 0 // the number of new lines in `after`
    private var fullCache: String? = null
    private var editVersion = 0 // goes up at every change of the text, so a check that finishes late can be dropped

    private var charW = 0f // the width of one character (the font is monospace)

    // the bracket or quote at the caret and its partner (offsets in the window; -1 for none); red when it has none
    private var pairA = -1
    private var pairB = -1
    private var pairBad = false

    private var highlightedFrom = -1
    private var highlightedTo = -1
    private var highlightStale = true

    private var errorLine = -1 // 0-based line of an error shown, -1 for none
    private var matchSpan: MatchSpan? = null

    private var popup: PopupWindow? = null
    private var shownCompletion: Completion? = null

    private val highlightRunnable = Runnable { highlightNow() }
    private val completionRunnable = Runnable { showCompletion() }

    init {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        gravity = Gravity.TOP or Gravity.START
        background = null
        isVerticalScrollBarEnabled = true
        setHorizontallyScrolling(false)
        setLineSpacing(0f, 1.1f)

        // no suggestions or corrections of the keyboard: the text is code
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN

        numberPaint.textSize = textSize * 0.9f
        tabPaint.textSize = textSize * 0.9f
        charW = paint.measureText("M")

        updateGutter(1)

        addTextChangedListener(object : TextWatcher {
            private var removed = ""
            private var start = 0
            private var inserted = ""

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                editing = true
                removed = if (count > 0) s.subSequence(start, start + count).toString() else ""
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                this.start = start
                inserted = if (count > 0) s.subSequence(start, start + count).toString() else ""
            }

            override fun afterTextChanged(s: Editable) {
                // the work below may change the text again: keep what this change was
                val atStart = start
                val wasRemoved = removed
                val wasInserted = inserted

                rebuildLineStarts(s)

                fullCache = null
                editVersion++

                if (loading) {
                    editing = false

                    return
                }

                errorLine = -1

                if (!replaying) undo.record(UndoStack.Edit(atStart, wasRemoved, wasInserted), autoIndenting)

                updateGutter(totalLines())

                // CUSTOM.md #48: in whole-document mode only the lines this edit touched are coloured again, right
                // here; otherwise (a big file) the screen and its surroundings are coloured after the edit stops
                if (wholeDone && wholeMode() && recolour(s, atStart, wasInserted.length)) {
                    removeCallbacks(highlightRunnable)
                } else {
                    highlightStale = true
                    removeCallbacks(highlightRunnable)
                    postDelayed(highlightRunnable, 100)
                }

                val typedOne = !replaying && !quiet && !autoIndenting && wasRemoved.isEmpty()

                if (typedOne && wasInserted == "\n") keepIndent(s, atStart)

                removeCallbacks(completionRunnable)

                if (typedOne && wasInserted.length == 1 && wasInserted != "\n" && completion != null) {
                    postDelayed(completionRunnable, 150)
                } else {
                    dismissCompletion()
                }

                editing = false

                scheduleLint(700)
                updatePairs()

                onEdited?.invoke()
                onCaretChanged?.invoke()
            }
        })

        ready = true
    }

    // ---- the text ---------------------------------------------------------------------------------

    /** Puts [text] in, forgets undo history and marks. A text over 3000 lines is shown a window at a time (CUSTOM.md #50). */
    fun load(text: String) {
        loadFull(text, 0)

        errorLine = -1

        clearProblems()
        scheduleLint(50)
    }

    /** CUSTOM.md #50: the whole text, also the part that is not in the window. */
    fun fullText(): String {
        fullCache?.let { return it }

        val window = text?.toString() ?: ""
        val full = if (before.isEmpty() && after.isEmpty()) window else before + window + after

        fullCache = full

        return full
    }

    /** True while only a part of the text is in the text box. */
    val isWindowed: Boolean get() = before.isNotEmpty() || after.isNotEmpty()

    /** The first and last line (1-based, of the whole text) that are in the text box. */
    val windowFirstLine: Int get() = lineOffset + 1
    val windowLastLine: Int get() = lineOffset + lineStarts.size

    private fun totalLines(): Int = lineOffset + lineStarts.size + afterLines

    private fun countNewlines(s: String): Int {
        var n = 0

        for (c in s) if (c == '\n') n++

        return n
    }

    // puts [full] in: as one window when it fits, otherwise the window that has the line [aroundLine]
    private fun loadFull(full: String, aroundLine: Int) {
        if (full.length <= WINDOW_CHARS && countNewlines(full) < WINDOW_LINES) {
            installWindow("", full, "", 0)

            return
        }

        val line = aroundLine.coerceAtLeast(0)
        val s = slice(full, line - 300, line)

        installWindow(s.before, s.window, s.after, s.start)
    }

    private class Slice(val before: String, val window: String, val after: String, val start: Int)

    // cuts [full] into the lines above a window, the window (from the line [wantStart], at most 3000 lines and about
    // 150000 characters) and the lines below it; the window always reaches the line [mustInclude]
    private fun slice(full: String, wantStart: Int, mustInclude: Int): Slice {
        var n = 1

        for (c in full) if (c == '\n') n++

        val starts = IntArray(n)
        var k = 1

        for (i in full.indices) if (full[i] == '\n') starts[k++] = i + 1

        fun lineLength(i: Int): Int = (if (i + 1 < n) starts[i + 1] - 1 else full.length) - starts[i]

        fun endFor(from: Int): Int {
            var to = from
            var chars = 0

            while (to < n && to - from < WINDOW_LINES && (to == from || chars < WINDOW_CHARS)) {
                chars += lineLength(to) + 1
                to++
            }

            return to
        }

        var ws = wantStart.coerceIn(0, n - 1)
        var we = endFor(ws)

        if (mustInclude in 0 until n && mustInclude >= we) {
            ws = mustInclude
            we = endFor(ws)
        }

        val windowEnd = if (we < n) starts[we] - 1 else full.length

        return Slice(
            full.substring(0, starts[ws]),
            full.substring(starts[ws], windowEnd),
            if (we < n) full.substring(windowEnd) else "",
            ws,
        )
    }

    // makes [w] the text of the text box, with [b] above it and [a] below it; the history of undo is gone
    private fun installWindow(b: String, w: String, a: String, offset: Int) {
        loading = true
        setText(w)
        loading = false

        before = b
        after = a
        lineOffset = offset
        afterLines = countNewlines(a)
        fullCache = null
        matchSpan = null // setText took every span off
        pairA = -1
        pairB = -1
        pairBad = false

        undo.clear()
        highlightedFrom = -1
        highlightedTo = -1
        highlightStale = true
        wholeDone = false

        this.text?.let { rebuildLineStarts(it) }

        flinger.abortAnimation()
        updateGutter(totalLines())
        setSelection(0)
        scrollTo(0, 0)
        removeCallbacks(highlightRunnable)
        postDelayed(highlightRunnable, 30)
        onCaretChanged?.invoke()
    }

    // the line [local] of the window, [fraction] of the way down the screen
    private fun placeLine(local: Int, fraction: Float) {
        val l = layout ?: return
        val index = local.coerceIn(0, lineStarts.size - 1)

        setSelection(lineStarts[index])

        val y = l.getLineTop(l.getLineForOffset(lineStarts[index].coerceAtMost(text?.length ?: 0))) + totalPaddingTop - (height * fraction).toInt()

        scrollTo(0, y.coerceIn(0, maxScrollY()))
    }

    /** The next window: it starts a little above where this one ends, so that the lines before the cut can still be seen. */
    fun nextWindow(): Boolean {
        if (after.isEmpty()) return false

        val lastLine = lineOffset + lineStarts.size - 1
        val overlap = minOf(100, lineStarts.size / 2)
        val s = slice(fullText(), lastLine + 1 - overlap, lastLine + 1)

        installWindow(s.before, s.window, s.after, s.start)
        placeLine(lastLine - s.start, 0.05f)

        return true
    }

    /** The window above this one. */
    fun previousWindow(): Boolean {
        if (lineOffset == 0) return false

        val firstLine = lineOffset
        val overlap = minOf(100, lineStarts.size / 2)
        val s = slice(fullText(), firstLine + overlap - WINDOW_LINES, firstLine - 1)

        installWindow(s.before, s.window, s.after, s.start)
        placeLine(firstLine - s.start, 0.9f)

        return true
    }

    /** CUSTOM.md #47: the size of the text in sp; the line numbers follow it. Only called at a tap of the user. */
    fun setSizeSp(sp: Float) {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
        numberPaint.textSize = textSize * 0.9f
        tabPaint.textSize = textSize * 0.9f
        charW = paint.measureText("M")
        gutterDigits = 0 // the gutter is as wide as its digits: measure it again
        updateGutter(totalLines())
        invalidate()
    }

    /**
     * CUSTOM.md #47: replaces the whole text by [replacement] as one undo step (only the part that differs is replaced).
     * CUSTOM.md #50: when the old or the new text is shown a window at a time, the history of undo is gone instead; false then.
     */
    fun replaceWhole(replacement: String): Boolean {
        val t = text ?: return true

        if (isWindowed || replacement.length > WINDOW_CHARS || countNewlines(replacement) >= WINDOW_LINES) {
            if (fullText() == replacement) return true

            clearMatch()
            loadFull(replacement, 0)
            scheduleLint(100)

            return false
        }

        val old = t.toString()

        if (old == replacement) return true

        var head = 0

        while (head < old.length && head < replacement.length && old[head] == replacement[head]) head++

        var tail = 0

        while (tail < old.length - head && tail < replacement.length - head &&
            old[old.length - 1 - tail] == replacement[replacement.length - 1 - tail]
        ) {
            tail++
        }

        clearMatch()

        quiet = true
        t.replace(head, old.length - tail, replacement.substring(head, replacement.length - tail))
        quiet = false

        undo.seal()
        setSelection(0)
        scrollTo(0, 0)

        return true
    }

    val logicalLineCount: Int get() = totalLines()

    /** 0-based index of the line that has the character at [offset]. */
    fun lineIndexAt(offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1

        while (low < high) {
            val mid = (low + high + 1) ushr 1

            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }

        return low
    }

    val caretLine: Int get() = lineIndexAt(selectionStart.coerceAtLeast(0)) + 1 + lineOffset

    val caretColumn: Int
        get() {
            val offset = selectionStart.coerceAtLeast(0)

            return offset - lineStarts[lineIndexAt(offset)] + 1
        }

    private fun lineEnd(line: Int, length: Int): Int {
        return if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else length
    }

    private fun rebuildLineStarts(s: CharSequence) {
        var count = 1

        for (i in s.indices) if (s[i] == '\n') count++

        val starts = IntArray(count)
        var n = 1

        for (i in s.indices) if (s[i] == '\n') starts[n++] = i + 1

        lineStarts = starts
    }

    private fun updateGutter(lines: Int) {
        val digits = maxOf(2, lines.toString().length)

        if (digits == gutterDigits) return

        gutterDigits = digits
        gutterWidth = (numberPaint.measureText("0") * digits + 22 * density).toInt()

        setPadding(gutterWidth + (8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
    }

    // ---- drawing ---------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val l = layout
        val t = text

        if (l != null && t != null) {
            val top = scrollY
            val bottom = scrollY + height
            val first = l.getLineForVertical((top - totalPaddingTop).coerceAtLeast(0))
            val last = l.getLineForVertical((bottom - totalPaddingTop).coerceAtLeast(0))
            val padTop = totalPaddingTop
            val padLeft = totalPaddingLeft

            val errorLocal = errorLine - lineOffset

            if (errorLocal in lineStarts.indices) {
                val from = l.getLineForOffset(lineStarts[errorLocal].coerceAtMost(t.length))
                val to = l.getLineForOffset(lineEnd(errorLocal, t.length).coerceAtMost(t.length))

                canvas.drawRect(
                    scrollX.toFloat(),
                    (l.getLineTop(from) + padTop).toFloat(),
                    (scrollX + width).toFloat(),
                    (l.getLineBottom(to) + padTop).toFloat(),
                    bandPaint,
                )
            }

            drawPair(canvas, l, t, padLeft, padTop)

            // CUSTOM.md #50: indent guides and visible Tabs / trailing spaces, only for the lines on the screen
            for (i in first..last) {
                val start = l.getLineStart(i)

                if (start == 0 || t[start - 1] == '\n') drawGuides(canvas, l, t, i, start, padLeft, padTop)

                if (showWhitespace) drawWhitespace(canvas, l, t, i, start, padLeft, padTop)
            }

            canvas.drawRect(scrollX.toFloat(), top.toFloat(), (scrollX + gutterWidth).toFloat(), bottom.toFloat(), gutterPaint)

            // the dots only count while the text still has the lines the check saw
            val showDots = diagTotalLines == totalLines()

            for (i in first..last) {
                val start = l.getLineStart(i)

                if (start == 0 || t[start - 1] == '\n') {
                    val index = lineIndexAt(start)
                    val baseline = (l.getLineBaseline(i) + padTop).toFloat()

                    canvas.drawText(
                        (index + 1 + lineOffset).toString(),
                        (scrollX + gutterWidth - 6 * density),
                        baseline,
                        numberPaint,
                    )

                    if (showDots) {
                        val severity = diagMap[index + lineOffset]

                        if (severity != null) {
                            dotPaint.color = if (severity == 0) colorDiagError else colorDiagWarn

                            canvas.drawCircle(scrollX + 7 * density, baseline - numberPaint.textSize * 0.35f, 3 * density, dotPaint)
                        }
                    }
                }
            }
        }

        super.onDraw(canvas)
    }

    // ---- guides, whitespace, pairs (CUSTOM.md #50) -----------------------------------------------------

    // a thin vertical line at every second column inside the indent of the line that starts at [start]
    private fun drawGuides(canvas: Canvas, l: Layout, t: Editable, line: Int, start: Int, padLeft: Int, padTop: Int) {
        val lineEnd = l.getLineEnd(line)
        var indent = 0

        while (start + indent < lineEnd && t[start + indent] == ' ') indent++

        // an empty line takes the depth of the lines around it, so that the guides run through it
        if (start + indent >= lineEnd || t[start + indent] == '\n' || t[start + indent] == '\r') indent = blankDepth(start)

        if (indent < 3) return

        val top = (l.getLineTop(line) + padTop).toFloat()
        val bottom = (l.getLineBottom(line) + padTop).toFloat()
        var k = 2

        while (k < indent) {
            val x = padLeft + k * charW

            canvas.drawLine(x, top, x, bottom, guidePaint)

            k += 2
        }
    }

    // the indent of the nearest lines above and below an empty line (the smaller one)
    private fun blankDepth(start: Int): Int {
        val t = text ?: return 0
        val index = lineIndexAt(start)
        var above = 0
        var i = index - 1
        var steps = 0

        while (i >= 0 && steps < 60) {
            val d = indentAt(t, lineStarts[i], lineEnd(i, t.length))

            if (d >= 0) {
                above = d

                break
            }

            i--
            steps++
        }

        var below = 0

        i = index + 1
        steps = 0

        while (i < lineStarts.size && steps < 60) {
            val d = indentAt(t, lineStarts[i], lineEnd(i, t.length))

            if (d >= 0) {
                below = d

                break
            }

            i++
            steps++
        }

        return minOf(above, below)
    }

    // the number of spaces the line [s, e) starts with; -1 when it has nothing else
    private fun indentAt(t: CharSequence, s: Int, e: Int): Int {
        var k = s

        while (k < e && t[k] == ' ') k++

        return if (k >= e || t[k] == '\r') -1 else k - s
    }

    // Tabs get a tinted box with an arrow; spaces and Tabs at the end of a line get a tinted box
    private fun drawWhitespace(canvas: Canvas, l: Layout, t: Editable, line: Int, start: Int, padLeft: Int, padTop: Int) {
        val le = l.getLineEnd(line).coerceAtMost(t.length)

        if (le <= start) return

        val top = (l.getLineTop(line) + padTop).toFloat()
        val bottom = (l.getLineBottom(line) + padTop).toFloat()
        val endsLogical = le >= t.length || t[le - 1] == '\n'
        val textEnd = if (t[le - 1] == '\n') le - 1 else le

        for (k in start until textEnd) {
            if (t[k] != '\t') continue

            val x1 = padLeft + l.getPrimaryHorizontal(k)
            val x2 = padLeft + (if (k + 1 < le) l.getPrimaryHorizontal(k + 1) else l.getLineRight(line))

            fillPaint.color = colorTabBg
            canvas.drawRect(x1, top, x2, bottom, fillPaint)
            canvas.drawText("\u2192", (x1 + x2) / 2f, (l.getLineBaseline(line) + padTop).toFloat(), tabPaint)
        }

        if (!endsLogical) return

        var end = textEnd

        if (end > start && t[end - 1] == '\r') end--

        var from = end

        while (from > start && (t[from - 1] == ' ' || t[from - 1] == '\t')) from--

        if (from == end) return

        // while the caret is in it the line is still being typed
        if (selectionStart == selectionEnd && selectionStart in from..end) return

        val x1 = padLeft + l.getPrimaryHorizontal(from)
        val x2 = padLeft + l.getPrimaryHorizontal(end)

        fillPaint.color = colorTrailing
        canvas.drawRect(x1, top, x2, bottom, fillPaint)
    }

    private fun drawPair(canvas: Canvas, l: Layout, t: Editable, padLeft: Int, padTop: Int) {
        for (o in intArrayOf(pairA, pairB)) {
            if (o < 0 || o >= t.length) continue

            val line = l.getLineForOffset(o)
            val x = padLeft + l.getPrimaryHorizontal(o)

            fillPaint.color = if (pairBad) colorPairBad else colorPair
            canvas.drawRect(x, (l.getLineTop(line) + padTop).toFloat(), x + charW, (l.getLineBottom(line) + padTop).toFloat(), fillPaint)
        }
    }

    // looks for the partner of the bracket or quote next to the caret; only called when the caret moved or the text changed
    private fun updatePairs() {
        val oldA = pairA
        val oldB = pairB
        val oldBad = pairBad

        pairA = -1
        pairB = -1
        pairBad = false

        val t = text

        if (t != null && selectionStart == selectionEnd && selectionStart >= 0) {
            findPair(t, selectionStart)
        }

        if (oldA != pairA || oldB != pairB || oldBad != pairBad) invalidate()
    }

    private fun findPair(t: Editable, caret: Int) {
        val candidates = intArrayOf(caret - 1, caret)

        for (at in candidates) {
            if (at < 0 || at >= t.length) continue

            val c = t[at]

            if (c == '(' || c == '[' || c == '{' || c == ')' || c == ']' || c == '}') {
                val m = YamlPairs.matchBracket(t, at)

                if (m >= 0) {
                    pairA = at
                    pairB = m
                } else if (m == YamlPairs.UNMATCHED) {
                    pairA = at
                    pairBad = true
                }

                return
            }
        }

        for (at in candidates) {
            if (at < 0 || at >= t.length) continue

            val c = t[at]

            if (c != '"' && c != '\'') continue

            val line = lineIndexAt(at)
            val s = lineStarts[line]
            val e = lineEnd(line, t.length)

            if (e - s > 2000) return

            val column = at - s

            for (pair in YamlPairs.quotes(TextUtils.substring(t, s, e))) {
                if (pair[0] == column || pair[1] == column) {
                    pairA = s + pair[0]

                    if (pair[1] >= 0) pairB = s + pair[1] else pairBad = true

                    return
                }
            }
        }
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)

        // CUSTOM.md #48: when the whole document is coloured, scrolling does no colouring work at all
        if (ready && !(wholeDone && wholeMode()) && !visibleIsHighlighted()) {
            removeCallbacks(highlightRunnable)
            postDelayed(highlightRunnable, 30)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (wholeDone && wholeMode()) return // CUSTOM.md #48: the colours do not depend on the size

        removeCallbacks(highlightRunnable)
        postDelayed(highlightRunnable, 30)
    }

    // ---- fling (CUSTOM.md #49) ---------------------------------------------------------------------
    // An EditText only follows the finger: when it lifts, the text stops, there is no gliding on. This adds it: the
    // speed of the finger is read by a GestureDetector at the lift, an OverScroller glides on from it, one step per
    // screen frame, and a touch stops it. Nothing runs before a fling or after it ends.

    private val flinger = OverScroller(context)

    private val flingDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val max = maxScrollY()

                if (max <= 0) return false

                flinger.fling(scrollX, scrollY, 0, -velocityY.toInt(), 0, 0, 0, max)
                postInvalidateOnAnimation()

                return true
            }
        },
    )

    // the lowest scroll position: the same limit the text box itself keeps when it follows the finger
    private fun maxScrollY(): Int {
        val l = layout ?: return 0

        return (l.height - (height - totalPaddingTop - totalPaddingBottom)).coerceAtLeast(0)
    }

    // the glow at the ends, as a RecyclerView draws it (the connections page): pulled when the finger drags past an
    // end, and given the speed of a fling that runs into an end
    private val edgeTop = EdgeEffect(context)
    private val edgeBottom = EdgeEffect(context)

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var stoppedGlide = false // this touch began while the text was gliding: it stops it, it is not a tap
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragged = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stoppedGlide = !flinger.isFinished

                if (stoppedGlide) flinger.abortAnimation()

                downX = event.x
                downY = event.y
                lastY = event.y
                dragged = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY

                lastY = event.y

                if (!dragged && Math.abs(event.x - downX) + Math.abs(event.y - downY) > touchSlop) dragged = true

                if (height > 0 && width > 0) {
                    if (dy > 0 && scrollY <= 0) {
                        edgeTop.onPull(dy / height, event.x / width)
                        postInvalidateOnAnimation()
                    } else if (dy < 0 && scrollY >= maxScrollY()) {
                        edgeBottom.onPull(-dy / height, 1f - event.x / width)
                        postInvalidateOnAnimation()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                edgeTop.onRelease()
                edgeBottom.onRelease()
                postInvalidateOnAnimation()
            }
        }

        flingDetector.onTouchEvent(event)

        // a touch that only stopped the gliding, or that dragged the text, is not a tap: the text box must not put the
        // caret there or open the keyboard (it does so at every lift of the finger while it has the focus). Not when
        // text is selected: that lift belongs to the selection.
        if ((stoppedGlide || dragged) && !hasSelection() && event.actionMasked == MotionEvent.ACTION_UP) {
            stoppedGlide = false
            dragged = false

            val cancel = MotionEvent.obtain(event)

            cancel.action = MotionEvent.ACTION_CANCEL

            val handled = super.onTouchEvent(cancel)

            cancel.recycle()

            return handled
        }

        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        super.computeScroll()

        if (flinger.computeScrollOffset()) {
            val y = flinger.currY

            scrollTo(scrollX, y)

            if (y <= 0 || y >= maxScrollY()) {
                // it reached an end: the glow takes the speed that is left, and the gliding is over
                val speed = flinger.currVelocity.toInt()

                if (y <= 0) edgeTop.onAbsorb(speed) else edgeBottom.onAbsorb(speed)

                flinger.abortAnimation()
            }

            postInvalidateOnAnimation()
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // the same way ScrollView draws its glow
        if (!edgeTop.isFinished) {
            val save = canvas.save()

            canvas.translate(scrollX.toFloat(), minOf(0, scrollY).toFloat())
            edgeTop.setSize(width, height)

            if (edgeTop.draw(canvas)) postInvalidateOnAnimation()

            canvas.restoreToCount(save)
        }

        if (!edgeBottom.isFinished) {
            val save = canvas.save()

            canvas.translate((scrollX - width).toFloat(), (maxOf(maxScrollY(), scrollY) + height).toFloat())
            canvas.rotate(180f, width.toFloat(), 0f)
            edgeBottom.setSize(width, height)

            if (edgeBottom.draw(canvas)) postInvalidateOnAnimation()

            canvas.restoreToCount(save)
        }
    }

    // while gliding the text box must not pull the text back to the caret
    override fun bringPointIntoView(offset: Int): Boolean {
        return if (flinger.isFinished) super.bringPointIntoView(offset) else false
    }

    override fun onDetachedFromWindow() {
        flinger.abortAnimation()
        removeCallbacks(highlightRunnable)
        removeCallbacks(completionRunnable)
        removeCallbacks(lintRunnable)
        lintGeneration++
        dismissCompletion()

        super.onDetachedFromWindow()
    }

    // ---- colours ---------------------------------------------------------------------------------

    private fun visibleRange(): IntArray? {
        val l = layout ?: return null
        val t = text ?: return null

        if (t.isEmpty()) return null

        val firstLine = l.getLineForVertical((scrollY - totalPaddingTop).coerceAtLeast(0))
        val lastLine = l.getLineForVertical((scrollY + height - totalPaddingTop).coerceAtLeast(0))

        return intArrayOf(l.getLineStart(firstLine), l.getLineEnd(lastLine).coerceAtMost(t.length))
    }

    private fun visibleIsHighlighted(): Boolean {
        val range = visibleRange() ?: return true

        return !highlightStale && highlightedFrom >= 0 && range[0] >= highlightedFrom && range[1] <= highlightedTo
    }

    // CUSTOM.md #48: a document of up to this size is coloured as a whole, once, and then only the lines an edit
    // touches are coloured again; scrolling then does no work for the colours (it only draws what is there). A bigger
    // one keeps the old way: the screen and 40 lines around it are coloured, again when the screen leaves them.
    private fun wholeMode(): Boolean = lineStarts.size <= WHOLE_MAX_LINES && (text?.length ?: 0) <= WHOLE_MAX_CHARS

    // true while every line of the text has its colours
    private var wholeDone = false

    private fun colorOf(kind: Tok): Int = when (kind) {
        Tok.Key -> colorKey
        Tok.Str -> colorStr
        Tok.Num -> colorNum
        Tok.Word -> colorWord
        Tok.Anchor -> colorAnchor
        Tok.Comment -> colorComment
        Tok.Punct -> colorPunct
    }

    // adds the colours of the lines [firstLine, lastLine]; the old ones of these lines must be gone
    private fun colourLines(editable: Editable, firstLine: Int, lastLine: Int) {
        for (line in firstLine..lastLine) {
            val start = lineStarts[line]
            val end = lineEnd(line, editable.length)

            if (end <= start || end - start > 2000) continue // a line that long (a minified file) is left plain

            // not subSequence: that copies the spans of the range
            val text = TextUtils.substring(editable, start, end).trimEnd('\r')

            YamlHighlighter.line(text) { a, b, kind ->
                editable.setSpan(TokSpan(colorOf(kind)), start + a, start + b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // after an edit that put [insertedLength] characters at [atStart]: the lines it touched get their colours again.
    // false when it touched too many lines (a big paste): the caller colours the whole text later
    private fun recolour(t: Editable, atStart: Int, insertedLength: Int): Boolean {
        val first = lineIndexAt(atStart.coerceIn(0, t.length))
        val last = lineIndexAt((atStart + insertedLength).coerceIn(0, t.length))

        if (last - first > 500) {
            wholeDone = false

            return false
        }

        for (span in t.getSpans(lineStarts[first], lineEnd(last, t.length), TokSpan::class.java)) t.removeSpan(span)

        colourLines(t, first, last)

        return true
    }

    private fun highlightNow() {
        val t = text ?: return
        val editable: Editable = t

        for (span in editable.getSpans(0, editable.length, TokSpan::class.java)) editable.removeSpan(span)

        if (wholeMode()) {
            if (editable.isNotEmpty()) colourLines(editable, 0, lineStarts.size - 1)

            wholeDone = true
            highlightedFrom = 0
            highlightedTo = editable.length
            highlightStale = false

            return
        }

        wholeDone = false

        val range = visibleRange()

        if (range == null) {
            highlightedFrom = -1
            highlightedTo = -1

            return
        }

        // Colours the screen and 40 lines around it; the older colours are taken off first, so there are never more
        // than a few hundred spans, however long the file is.
        val firstLine = (lineIndexAt(range[0]) - 40).coerceAtLeast(0)
        val lastLine = (lineIndexAt(range[1]) + 40).coerceAtMost(lineStarts.size - 1)

        colourLines(editable, firstLine, lastLine)

        highlightedFrom = lineStarts[firstLine]
        highlightedTo = lineEnd(lastLine, editable.length)
        highlightStale = false
    }

    // ---- new line, indent -----------------------------------------------------------------------

    // after a typed new line: the next line starts where the one above has its content; after a key that opens a
    // block two columns further in; after a list item of plain text a new "- "
    private fun keepIndent(s: Editable, newlinePos: Int) {
        val line = lineIndexAt(newlinePos)
        val previous = s.subSequence(lineStarts[line], newlinePos).toString().trimEnd('\r')
        val parsed = YamlLine.parse(previous)

        val text = when {
            parsed == null -> " ".repeat(YamlLine.indentOf(previous))
            parsed.isItem && parsed.key == null && parsed.content.isNotEmpty() -> " ".repeat(parsed.indent) + "- "
            else -> " ".repeat(parsed.contentColumn + (if (parsed.content.trimEnd().endsWith(":")) 2 else 0))
        }

        if (text.isEmpty()) return

        autoIndenting = true
        s.insert(newlinePos + 1, text)
        autoIndenting = false

        setSelection(newlinePos + 1 + text.length)
    }

    /** Moves the lines of the selection (the caret's line without a selection) by two columns. */
    fun shiftLines(more: Boolean) {
        val t = text ?: return
        val a = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val b = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val first = lineIndexAt(a)
        var last = lineIndexAt(b)

        if (b > a && b == lineStarts[last] && last > first) last--

        val from = lineStarts[first]
        val to = lineEnd(last, t.length)
        val block = t.subSequence(from, to).toString().split('\n')

        val shifted = block.joinToString("\n") { line ->
            if (more) {
                if (line.isEmpty()) line else "  $line"
            } else {
                line.substring(minOf(2, YamlLine.indentOf(line)))
            }
        }

        if (shifted == block.joinToString("\n")) return

        quiet = true
        t.replace(from, to, shifted)
        quiet = false

        setSelection(from, from + shifted.length)
    }

    // ---- undo, redo ---------------------------------------------------------------------------

    fun undoEdit(): Boolean {
        val edit = undo.popUndo() ?: return false

        applyEdit(edit.start, edit.inserted.length, edit.removed)

        return true
    }

    fun redoEdit(): Boolean {
        val edit = undo.popRedo() ?: return false

        applyEdit(edit.start, edit.removed.length, edit.inserted)

        return true
    }

    private fun applyEdit(start: Int, length: Int, replacement: String) {
        val t = text ?: return

        replaying = true
        t.replace(start, start + length, replacement)
        replaying = false

        setSelection((start + replacement.length).coerceIn(0, t.length))
        scrollToOffset(start + replacement.length)
    }

    // ---- insert, jump -----------------------------------------------------------------------------

    /** Replaces the selection by [what]; the caret ends [caretBack] characters before the end of it. */
    fun insertText(what: String, caretBack: Int = 0) {
        val t = text ?: return
        val a = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val b = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)

        quiet = true
        t.replace(a, b, what)
        quiet = false

        setSelection(a + what.length - caretBack)
    }

    fun scrollToOffset(offset: Int) {
        val l = layout ?: return
        val line = l.getLineForOffset(offset.coerceIn(0, text?.length ?: 0))
        val y = l.getLineTop(line) + totalPaddingTop
        val max = (l.height + totalPaddingTop + totalPaddingBottom - height).coerceAtLeast(0)

        if (y < scrollY + 20 * density || y > scrollY + height - 60 * density) {
            scrollTo(0, (y - height / 3).coerceIn(0, max))
        }
    }

    /**
     * Puts the caret at the start of the 1-based [line] (of the whole text) and marks it as the place of an error when
     * [mark]. CUSTOM.md #50: the window moves when the line is not in it.
     */
    fun goToLine(line: Int, mark: Boolean) {
        val absolute = (line - 1).coerceIn(0, totalLines() - 1)

        if (absolute < lineOffset || absolute >= lineOffset + lineStarts.size) {
            val s = slice(fullText(), absolute - 300, absolute)

            installWindow(s.before, s.window, s.after, s.start)
        }

        val local = (absolute - lineOffset).coerceIn(0, lineStarts.size - 1)

        setSelection(lineStarts[local])
        scrollToOffset(lineStarts[local])

        errorLine = if (mark) absolute else -1

        invalidate()
    }

    fun clearErrorMark() {
        if (errorLine >= 0) {
            errorLine = -1
            invalidate()
        }
    }

    // ---- find and replace ---------------------------------------------------------------------------

    /**
     * Finds the next (or previous) place of [query] from the caret, wrapping round; marks it. False when there is none.
     * CUSTOM.md #50: it looks in the whole text, and the window moves to the place when it is not in it.
     */
    fun find(query: String, forward: Boolean, matchCase: Boolean): Boolean {
        val t = text ?: return false

        if (query.isEmpty()) {
            clearMatch()

            return false
        }

        val s = fullText()
        val base = before.length
        var at: Int

        if (forward) {
            at = s.indexOf(query, base + selectionEnd.coerceIn(0, t.length), !matchCase)

            if (at < 0) at = s.indexOf(query, 0, !matchCase)
        } else {
            val from = base + selectionStart.coerceAtLeast(0)

            at = if (from <= 0) -1 else s.lastIndexOf(query, from - 1, !matchCase)

            if (at < 0) at = s.lastIndexOf(query, s.length, !matchCase)
        }

        if (at < 0) {
            clearMatch()

            return false
        }

        showMatch(s, at, query.length)

        return true
    }

    // marks and shows the match at [at] (an offset in the whole text), moving the window to it when it is outside
    private fun showMatch(full: String, at: Int, length: Int) {
        if (at < before.length || at + length > before.length + (text?.length ?: 0)) {
            var line = 0

            for (i in 0 until at) if (full[i] == '\n') line++

            val s = slice(full, line - 300, line)

            installWindow(s.before, s.window, s.after, s.start)
        }

        val t = text ?: return
        val from = (at - before.length).coerceIn(0, t.length)
        val to = (from + length).coerceAtMost(t.length)

        markMatch(from, to)
        setSelection(from, to)
        scrollToOffset(from)
    }

    /** "n of m" for the match at the caret: (index, total), index 0 when the caret is not on one. */
    fun matchPosition(query: String, matchCase: Boolean): Pair<Int, Int> {
        if (text == null) return Pair(0, 0)

        if (query.isEmpty()) return Pair(0, 0)

        val s = fullText()
        val from = before.length + selectionStart
        val to = before.length + selectionEnd
        var count = 0
        var index = 0
        var at = s.indexOf(query, 0, !matchCase)

        while (at >= 0 && count < 99999) {
            count++

            if (at == from && at + query.length == to) index = count

            at = s.indexOf(query, at + query.length, !matchCase)
        }

        return Pair(index, count)
    }

    fun replaceCurrent(query: String, with: String, matchCase: Boolean): Boolean {
        val t = text ?: return false

        if (query.isNotEmpty()) {
            val a = selectionStart
            val b = selectionEnd

            if (a >= 0 && b - a == query.length && t.substring(a, b).equals(query, ignoreCase = !matchCase)) {
                quiet = true
                t.replace(a, b, with)
                quiet = false

                setSelection(a + with.length)
            }
        }

        return find(query, true, matchCase)
    }

    /** Replaces every place of [query]; returns how many. One undo step: only the part of the text that changes is replaced. */
    fun replaceAll(query: String, with: String, matchCase: Boolean): Int {
        val t = text ?: return 0

        if (query.isEmpty()) return 0

        // CUSTOM.md #50: the text is not all in the text box: replace in the whole text, the history of undo is gone
        if (isWindowed) return replaceAllWindowed(query, with, matchCase)

        val old = t.toString()
        val result = StringBuilder(old.length)
        var count = 0
        var from = 0
        var at = old.indexOf(query, 0, !matchCase)

        while (at >= 0) {
            result.append(old, from, at).append(with)
            count++
            from = at + query.length
            at = old.indexOf(query, from, !matchCase)
        }

        if (count == 0) return 0

        result.append(old, from, old.length)

        val new = result.toString()
        var head = 0

        while (head < old.length && head < new.length && old[head] == new[head]) head++

        var tail = 0

        while (tail < old.length - head && tail < new.length - head &&
            old[old.length - 1 - tail] == new[new.length - 1 - tail]
        ) {
            tail++
        }

        clearMatch()

        quiet = true
        t.replace(head, old.length - tail, new.substring(head, new.length - tail))
        quiet = false

        setSelection(head)

        return count
    }

    private fun replaceAllWindowed(query: String, with: String, matchCase: Boolean): Int {
        val old = fullText()
        val result = StringBuilder(old.length)
        var count = 0
        var from = 0
        var at = old.indexOf(query, 0, !matchCase)
        val firstAt = at

        while (at >= 0) {
            result.append(old, from, at).append(with)
            count++
            from = at + query.length
            at = old.indexOf(query, from, !matchCase)
        }

        if (count == 0) return 0

        result.append(old, from, old.length)

        var line = 0

        for (i in 0 until firstAt) if (old[i] == '\n') line++

        clearMatch()
        loadFull(result.toString(), line)
        scheduleLint(100)
        onEdited?.invoke()

        return count
    }

    private fun markMatch(from: Int, to: Int) {
        val t = text ?: return

        clearMatch()

        val span = MatchSpan(colorMatch)

        matchSpan = span
        t.setSpan(span, from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun clearMatch() {
        matchSpan?.let { text?.removeSpan(it) }
        matchSpan = null
    }

    // ---- suggestions ----------------------------------------------------------------------------

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)

        if (ready && !editing) {
            dismissCompletion()
            undo.seal()
            updatePairs()
            onCaretChanged?.invoke()
        }
    }

    private fun showCompletion() {
        val engine = completion ?: return
        val t = text ?: return

        // CUSTOM.md #50: the keys above the window are not in the text box, so the level of the caret cannot be read
        if (selectionStart != selectionEnd || selectionStart < 0 || t.length > 600_000 || isWindowed) return

        val offset = selectionStart
        val index = lineIndexAt(offset)
        val lines = t.toString().split('\n').map { it.trimEnd('\r') }
        val column = offset - lineStarts[index]

        val request = CompletionRequest(lines, index, column) { wordsOf(t, offset) }
        val result = try {
            engine.complete(request)
        } catch (_: Exception) {
            null // a line the reading did not expect: no suggestions, nothing else
        }

        if (result == null || result.suggestions.isEmpty()) {
            dismissCompletion()

            return
        }

        presentCompletion(result, offset)
    }

    // the words of the text, not the one being typed (up to 3000 different ones)
    private fun wordsOf(t: Editable, caret: Int): List<String> {
        val out = LinkedHashSet<String>()
        val pattern = Regex("[A-Za-z0-9_.-]{3,}")
        var from = caret

        while (from > 0 && (t[from - 1].isLetterOrDigit() || t[from - 1] == '_' || t[from - 1] == '-' || t[from - 1] == '.')) from--

        for (m in pattern.findAll(t)) {
            if (m.range.first == from) continue

            out.add(m.value)

            if (out.size >= 3000) break
        }

        return out.toList()
    }

    private fun presentCompletion(result: Completion, offset: Int) {
        val l = layout ?: return
        val line = l.getLineForOffset(offset)
        val rowHeight = (40 * density).toInt()
        val rows = minOf(result.suggestions.size, 5)
        val popupHeight = rows * rowHeight + (4 * density).toInt()
        val popupWidth = minOf((resources.displayMetrics.widthPixels * 0.8f).toInt(), (320 * density).toInt())

        val list = ListView(context).apply {
            divider = null
            adapter = object : BaseAdapter() {
                override fun getCount() = result.suggestions.size

                override fun getItem(position: Int) = result.suggestions[position]

                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = (convertView as? TextView) ?: TextView(context).apply {
                        typeface = Typeface.MONOSPACE
                        textSize = 13f
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
                    }
                    val item = getItem(position)
                    val textColor = if (night) Color.WHITE else Color.BLACK
                    val shown = android.text.SpannableStringBuilder(item.label)

                    shown.setSpan(ForegroundColorSpan(textColor), 0, shown.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                    if (item.detail.isNotEmpty()) {
                        val from = shown.length

                        shown.append("   ").append(item.detail)
                        shown.setSpan(ForegroundColorSpan(colorComment), from, shown.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    view.text = shown

                    return view
                }
            }
            setOnItemClickListener { _, _, position, _ -> choose(result, result.suggestions[position]) }
        }

        dismissCompletion()

        val window = PopupWindow(list, popupWidth, popupHeight, false).apply {
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = 8 * density
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(if (night) Color.parseColor("#2B2F36") else Color.WHITE)
                    setStroke(density.toInt().coerceAtLeast(1), if (night) Color.parseColor("#444B55") else Color.parseColor("#CFD4D9"))
                    cornerRadius = 8 * density
                },
            )
        }

        val where = IntArray(2)

        getLocationOnScreen(where)

        val frame = Rect()

        getWindowVisibleDisplayFrame(frame)

        val x = (where[0] + totalPaddingLeft + l.getPrimaryHorizontal(offset) - scrollX).toInt()
            .coerceIn(where[0], (where[0] + width - popupWidth).coerceAtLeast(where[0]))
        val below = where[1] + l.getLineBottom(line) + totalPaddingTop - scrollY
        val above = where[1] + l.getLineTop(line) + totalPaddingTop - scrollY - popupHeight
        val y = if (below + popupHeight <= frame.bottom) below else above.coerceAtLeast(frame.top)

        try {
            window.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
        } catch (_: Exception) {
            return // the window is going away
        }

        popup = window
        shownCompletion = result
    }

    private fun choose(result: Completion, suggestion: Suggestion) {
        val t = text ?: return
        val caret = selectionStart
        val from = (caret - result.prefix.length).coerceAtLeast(0)
        val what = suggestion.insert ?: suggestion.label

        dismissCompletion()

        quiet = true
        t.replace(from, caret, what)
        quiet = false

        setSelection((from + what.length - suggestion.cursorBack).coerceIn(0, t.length))
        undo.seal()
    }

    fun dismissCompletion() {
        popup?.dismiss()
        popup = null
        shownCompletion = null
    }

    // ---- problems of the whole text (CUSTOM.md #50) -----------------------------------------------------

    /** Set by the page: look for mistakes in the text (only for YAML files). */
    var lintEnabled = false

    /** Called when the list of problems was renewed. */
    var onProblems: (() -> Unit)? = null

    private var problems: List<Diagnostic> = emptyList()
    private val diagMap = HashMap<Int, Int>() // 0-based line of the whole text -> the worst severity on it
    private val diagByLine = HashMap<Int, ArrayList<Diagnostic>>()
    private var diagTotalLines = -1 // how many lines the text had when it was checked
    private var lintGeneration = 0
    private val lintRunnable = Runnable { startLint() }

    fun allProblems(): List<Diagnostic> = problems

    /** The problems of the 0-based [line] (of the whole text); none while the text has a different number of lines than when it was checked. */
    fun problemsAt(line: Int): List<Diagnostic> {
        if (diagTotalLines != totalLines()) return emptyList()

        return diagByLine[line] ?: emptyList()
    }

    // the check starts once the typing has stopped for [delay] (never on a timer that keeps running)
    private fun scheduleLint(delay: Long) {
        if (!lintEnabled) return

        removeCallbacks(lintRunnable)
        postDelayed(lintRunnable, delay)
    }

    private fun clearProblems() {
        problems = emptyList()
        diagMap.clear()
        diagByLine.clear()
        diagTotalLines = -1
        onProblems?.invoke()
        invalidate()
    }

    // reads the whole text on a thread of its own; an answer that comes after the text changed again is dropped
    private fun startLint() {
        if (!lintEnabled) return

        val snapshot = fullText()
        val total = totalLines()
        val version = editVersion
        val generation = ++lintGeneration

        Thread {
            val found = try {
                YamlLint.run(snapshot)
            } catch (_: Throwable) {
                null
            }

            if (found != null) {
                post {
                    if (generation == lintGeneration && version == editVersion && isAttachedToWindow) applyProblems(found, total)
                }
            }
        }.start()
    }

    private fun applyProblems(found: List<Diagnostic>, total: Int) {
        problems = found
        diagMap.clear()
        diagByLine.clear()

        for (d in found) {
            val old = diagMap[d.line]

            if (old == null || d.severity < old) diagMap[d.line] = d.severity

            diagByLine.getOrPut(d.line) { ArrayList() }.add(d)
        }

        diagTotalLines = total
        onProblems?.invoke()
        invalidate()
    }

    // ---- keys ---------------------------------------------------------------------------------------

    // a keyboard with a Tab key: two spaces (Shift+Tab takes them away)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            if (selectionStart != selectionEnd || event.isShiftPressed) shiftLines(!event.isShiftPressed) else insertText("  ")

            return true
        }

        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> {
                    if (event.isShiftPressed) redoEdit() else undoEdit()

                    return true
                }
                KeyEvent.KEYCODE_Y -> {
                    redoEdit()

                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        // CUSTOM.md #50: the most that is in the text box at a time (a window of a bigger text)
        const val WINDOW_LINES = 3000
        const val WINDOW_CHARS = 150_000

        // CUSTOM.md #48: up to this size the whole text is coloured (about 5 spans a line); above it only the screen.
        // A window is never bigger than this unless a single line is.
        const val WHOLE_MAX_LINES = WINDOW_LINES
        const val WHOLE_MAX_CHARS = WINDOW_CHARS
    }
}
