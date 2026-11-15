package com.github.kr328.clash.design.custom.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.databinding.Observable
import androidx.core.view.WindowInsetsCompat
import com.github.kr328.clash.core.model.EffectiveConfig
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.custom.EffectiveText
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.resolveThemedResourceId
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.text.method.LinkMovementMethod
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// CUSTOM (CUSTOM.md #46, not upstream): the page of the built-in text editor (opened from "Files" for the text files
// of a profile). A bar with undo, redo, find, check and save; the find bar; the text with line numbers; a row of the
// symbols YAML needs on a phone keyboard; a line saying where the caret is. It is built in code, so there is no
// layout file. Everything here answers a tap or a change of the text, nothing runs on its own.
class EditorDesign(
    context: Context,
    private val fileName: String,
    canCheck: Boolean,
    useSchema: Boolean,
    private val lint: Boolean = false, // CUSTOM.md #50: look for mistakes in the text (YAML files)
    private val readOnly: Boolean = false, // CUSTOM.md #51: a profile that is not a local file cannot be written (the service refuses)
    // CUSTOM.md #56: the config.yaml of a profile the user edits: "save" only keeps a history version, "apply" puts
    // it in use, and the history and the compare against the config in use are in the menu
    private val split: Boolean = false,
) : Design<EditorDesign.Request>(context) {
    // SaveAs, ImportFrom: CUSTOM.md #47; Apply, History, Compare: CUSTOM.md #56
    enum class Request { Save, Check, External, Leave, SaveAs, ImportFrom, Apply, History, Compare }

    private val density = context.resources.displayMetrics.density
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val colorBg = if (night) Color.parseColor("#16191D") else Color.WHITE
    private val colorBar = if (night) Color.parseColor("#1F2328") else Color.parseColor("#F1F3F4")
    private val colorText = if (night) Color.parseColor("#E6E6E6") else Color.parseColor("#1F2328")
    // CUSTOM: plain (uncoloured) YAML text, e.g. the rule lines; kept far from the grey of comments
    private val colorPlain = if (night) Color.parseColor("#C3E88D") else Color.parseColor("#2E7D32") // same green as the quoted strings
    private val colorMuted = if (night) Color.parseColor("#9AA4AE") else Color.parseColor("#6B7480")
    private val colorAccent = if (night) Color.parseColor("#82AAFF") else Color.parseColor("#1565C0")
    private val colorBad = if (night) Color.parseColor("#EF9A9A") else Color.parseColor("#C62828")
    private val colorWarn = if (night) Color.parseColor("#FFB74D") else Color.parseColor("#E65100")
    private val colorNoticeBg = if (night) Color.parseColor("#3E2F00") else Color.parseColor("#FFF4D6")

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val DEFAULT_SIZE = 13 // sp, what the editor had before the size could be changed
        const val MIN_SIZE = 9
        const val MAX_SIZE = 37
        const val STEP = 2
    }

    private val editor = CodeEditText(context).apply {
        setTextColor(colorPlain)
        setHintTextColor(colorMuted)
        setBackgroundColor(colorBg)
    }

    private val titleView = TextView(context).apply {
        textSize = 16f
        setTextColor(colorText)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        text = fileName
    }

    private val undoButton = action("↶", context.getString(R.string.editor_undo)) { editor.undoEdit() }
    private val redoButton = action("↷", context.getString(R.string.editor_redo)) { editor.redoEdit() }
    private val findButton = action(context.getString(R.string.editor_find), null) { toggleFind() }
    private val checkButton = action(context.getString(R.string.editor_check), null) { requests.trySend(Request.Check) }
    private val saveButton = action(
        context.getString(if (split) R.string.editor_save_history_short else R.string.editor_save),
        context.getString(if (split) R.string.editor_save_history else R.string.editor_save),
    ) { requests.trySend(Request.Save) }
    private val applyButton = action(context.getString(R.string.editor_apply), null) { requests.trySend(Request.Apply) }
    private val moreButton = action("⋮", null) { showMore(it) }

    // CUSTOM.md #53: one row at the bottom: where the caret is (and the problem on its line, or which lines of a big
    // text are shown), the problem count, the buttons of a big text's window, the size of the text
    private val statusView = TextView(context).apply {
        textSize = 12f
        setTextColor(colorMuted)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(12), dp(2), dp(4), dp(2))
        if (lint) setOnClickListener { showProblems() }
    }

    // CUSTOM.md #50, #53: the problems button and the buttons that move a big text's window sit in the bottom row
    private val problemButton = action("", null) { showProblems() }
    private val previousWindowButton = action("\u25C0", context.getString(R.string.editor_window_previous)) {
        if (!editor.previousWindow()) refreshStatus()
    }
    private val nextWindowButton = action("\u25B6", context.getString(R.string.editor_window_next)) {
        if (!editor.nextWindow()) refreshStatus()
    }
    private val noticeRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(colorNoticeBg)
        setPadding(dp(14), 0, dp(4), 0)
        visibility = if (readOnly) View.VISIBLE else View.GONE
    }

    // CUSTOM.md #47: the size of the text, 2 sp at a tap, kept for the next time; nothing runs on its own
    private val prefs = context.getSharedPreferences("custom_editor", Context.MODE_PRIVATE)
    private var sizeSp = prefs.getInt("size_sp", DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)

    private val sizeView = TextView(context).apply {
        textSize = 12f
        setTextColor(colorMuted)
        gravity = Gravity.CENTER
        minWidth = dp(28)
    }
    private val smallerButton = action("A−", context.getString(R.string.editor_font_smaller)) { changeSize(-STEP) }
    private val biggerButton = action("A+", context.getString(R.string.editor_font_bigger)) { changeSize(STEP) }

    // find bar
    private var matchCase = false

    private val findInput = EditText(context).apply {
        hint = context.getString(R.string.editor_find)
        setSingleLine()
        textSize = 14f
        setTextColor(colorText)
        setHintTextColor(colorMuted)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private val replaceInput = EditText(context).apply {
        hint = context.getString(R.string.editor_replace_hint)
        setSingleLine()
        textSize = 14f
        setTextColor(colorText)
        setHintTextColor(colorMuted)
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private val countView = TextView(context).apply {
        textSize = 12f
        setTextColor(colorMuted)
        minWidth = dp(48)
        gravity = Gravity.CENTER
    }

    private val caseButton = action("Aa", context.getString(R.string.editor_match_case)) {
        matchCase = !matchCase
        refreshCase()
        findFrom(true, keepPlace = true)
    }

    private val findBar = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colorBar)
        setPadding(dp(8), 0, dp(4), dp(4))
        visibility = View.GONE
    }

    private val symbols = HorizontalScrollView(context).apply {
        setBackgroundColor(colorBar)
        isHorizontalScrollBarEnabled = false
    }

    override val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colorBg)

        // top bar
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(colorBar)
                setPadding(dp(14), 0, dp(4), 0)

                addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (!readOnly) addView(undoButton)
                if (!readOnly) addView(redoButton)
                addView(findButton)

                if (canCheck) addView(checkButton)

                if (!readOnly) addView(saveButton)
                if (split) addView(applyButton)
                addView(moreButton)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)),
        )

        noticeRow.addView(
            TextView(context).apply {
                textSize = 12f
                setTextColor(colorText)
                setPadding(0, dp(6), 0, dp(6))
                text = context.getString(R.string.editor_readonly_notice)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        noticeRow.addView(action("\u2715", null) { noticeRow.visibility = View.GONE })
        addView(noticeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // find bar: the word, matches, previous, next, close; the replacement, replace, replace all
        findBar.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(findInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(countView)
                addView(action("↑", context.getString(R.string.editor_previous)) { findFrom(false) })
                addView(action("↓", context.getString(R.string.editor_next)) { findFrom(true) })
                addView(caseButton)
                addView(action("✕", null) { toggleFind() })
            },
        )
        if (!readOnly) findBar.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(replaceInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(action(context.getString(R.string.editor_replace), null) { replaceOne() })
                addView(action(context.getString(R.string.editor_replace_all), null) { replaceEvery() })
            },
        )

        addView(findBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (!readOnly) addView(symbols, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(colorBar)

                addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                for (b in listOf(problemButton, previousWindowButton, nextWindowButton, smallerButton, biggerButton)) {
                    b.minHeight = dp(32)
                    b.minWidth = dp(36)
                }

                addView(problemButton)
                addView(previousWindowButton)
                addView(nextWindowButton)
                addView(smallerButton)
                addView(sizeView)
                addView(biggerButton)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        // the page is under the system bars: leave their room, and the room of the keyboard (from Android 11 it
        // is told in the insets; before that the window is made smaller by the activity). CUSTOM.md #53: the room of
        // the bars is also taken from the app's own insets (surface), as the other pages do; the larger one counts
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())

            barLeft = bars.left
            barTop = bars.top
            barRight = bars.right
            barBottom = bars.bottom
            imeBottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0

            applyPadding()

            insets
        }
    }

    private var barLeft = 0
    private var barTop = 0
    private var barRight = 0
    private var barBottom = 0
    private var imeBottom = 0

    // CUSTOM.md #53: the room of the keyboard is given to the page when no finger is on the text: a long press that
    // is selecting a word would otherwise find the text moved away under it, and the selection would not be the word
    // that was pressed. The page is made smaller as soon as the finger lifts.
    private var imeApplied = 0

    private fun applyPadding() {
        val own = surface.insets

        if (!editor.touching) imeApplied = imeBottom

        root.setPadding(
            maxOf(barLeft, own.start),
            maxOf(barTop, own.top),
            maxOf(barRight, own.end),
            maxOf(barBottom, own.bottom, imeApplied),
        )
    }

    var dirty: Boolean = false
        private set

    init {
        surface.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                applyPadding()
            }
        })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        row.addView(symbol("⇥", false) { editor.shiftLines(true) })
        row.addView(symbol("⇤", false) { editor.shiftLines(false) })

        for ((shown, what, back) in listOf(
            Triple("-", "- ", 0),
            Triple(":", ": ", 0),
            Triple("#", "# ", 0),
            Triple("\"\"", "\"\"", 1),
            Triple("''", "''", 1),
            Triple("[]", "[]", 1),
            Triple("{}", "{}", 1),
            Triple(",", ", ", 0),
            Triple("|", "|", 0),
            Triple("&", "&", 0),
            Triple("*", "*", 0),
        )) {
            row.addView(symbol(shown, true) { editor.insertText(what, back) })
        }

        symbols.addView(row)

        if (useSchema) {
            // the schema (about 60 KB) is read once, off the main thread; until it is there the words of the text are suggested
            launch(Dispatchers.Default) {
                val schema = try {
                    ClashSchema.parse(context.resources.openRawResource(R.raw.clash_schema).bufferedReader().readText())
                } catch (_: Exception) {
                    null
                }

                withContext(Dispatchers.Main) { editor.schema = schema }
            }
        }

        editor.setSizeSp(sizeSp.toFloat())
        refreshSize()

        // CUSTOM.md #53: two fingers change the size, one size per step, as long as they stay down
        editor.onPinchStep = { step -> changeSize(step) }
        editor.onTouchEnded = { if (imeApplied != imeBottom) applyPadding() }

        editor.readOnly = readOnly
        editor.lintEnabled = lint
        editor.showWhitespace = prefs.getBoolean("show_whitespace", true)
        editor.onProblems = {
            refreshProblems()
            refreshStatus()
        }

        editor.onEdited = {
            setDirty(true)
            refreshStatus()
        }
        editor.onCaretChanged = { refreshStatus() }

        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                findFrom(true, keepPlace = true)
            }
        })
        findInput.setOnEditorActionListener { _, _, _ ->
            findFrom(true)

            true
        }

        refreshCase()
        refreshProblems()
        refreshStatus()
    }

    // ---- what the activity uses ---------------------------------------------------------------

    fun load(text: String) {
        editor.load(text)
        setDirty(false)
        refreshProblems()
        refreshStatus()

        if (editor.isWindowed) launch { toast(context.getString(R.string.editor_big_notice)) }
    }

    // the whole text, also the part of a big text that is not in the text box
    fun currentText(): String = editor.fullText()

    /** CUSTOM.md #47: puts text picked from elsewhere in place of the text (one undo step, the page becomes unsaved). */
    fun replaceText(text: String): Boolean {
        val undoable = editor.replaceWhole(text) // false for a big text: the history of undo is gone (CUSTOM.md #50)

        setDirty(true)
        refreshProblems()
        refreshStatus()

        return undoable
    }

    fun markSaved() {
        setDirty(false)
        editor.clearErrorMark()
        editor.undo.seal()
    }

    fun goToLine(line: Int, mark: Boolean) {
        editor.requestFocus()
        editor.goToLine(line, mark)
    }

    suspend fun toast(text: CharSequence) {
        showToast(text, ToastDuration.Short)
    }

    /** A message with an OK button; returns when it is closed. */
    suspend fun message(title: CharSequence, text: CharSequence) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { c ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener { if (c.isActive) c.resume(Unit) }
                    .show()

                c.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    /** What to do with the changes at leaving: 0 save, 1 discard, 2 keep editing. */
    suspend fun confirmLeave(): Int {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Int> { c ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(if (split) R.string.editor_leave_title_history else R.string.editor_leave_title)
                    .setPositiveButton(if (split) R.string.editor_leave_save_history else R.string.editor_leave_save) { _, _ -> if (c.isActive) c.resume(0) }
                    .setNegativeButton(R.string.editor_leave_discard) { _, _ -> if (c.isActive) c.resume(1) }
                    .setNeutralButton(R.string.editor_leave_cancel) { _, _ -> if (c.isActive) c.resume(2) }
                    .setOnCancelListener { if (c.isActive) c.resume(2) }
                    .show()

                c.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    /** CUSTOM.md #56: a list to pick from; the index of the picked item, or -1 when it was dismissed. */
    suspend fun pick(title: CharSequence, items: List<CharSequence>): Int {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Int> { c ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setItems(items.toTypedArray()) { _, which -> if (c.isActive) c.resume(which) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { if (c.isActive) c.resume(-1) }
                    .show()

                c.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    /** CUSTOM.md #56: a question with two buttons; true when [positive] was tapped. */
    suspend fun ask(title: CharSequence, text: CharSequence, positive: CharSequence): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { c ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setMessage(text)
                    .setPositiveButton(positive) { _, _ -> if (c.isActive) c.resume(true) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { if (c.isActive) c.resume(false) }
                    .show()

                c.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    /**
     * CUSTOM.md #56: the page of what a text changes against the config in use, drawn like the effective config
     * page (see [EffectiveText]). [compute] is called when the page opens and at each tap on "compare again" (never
     * on its own); it answers with the lines, or null when it could not. Returns when the page is closed.
     */
    suspend fun showCompare(title: CharSequence, compute: suspend () -> EffectiveConfig?) {
        withContext(Dispatchers.Main) {
            val body = TextView(context).apply {
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }

            var running = false

            fun run() {
                if (running) return

                running = true
                body.text = context.getString(R.string.editor_compare_running)

                this@EditorDesign.launch(Dispatchers.Main) {
                    val config = try {
                        compute()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }

                    body.text = if (config == null) {
                        context.getString(R.string.editor_compare_failed)
                    } else {
                        EffectiveText(context, config, compare = true).render { }
                    }

                    running = false
                }
            }

            suspendCancellableCoroutine<Unit> { c ->
                val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setView(ScrollView(context).apply { addView(body) })
                    .setPositiveButton(R.string.editor_compare_close, null)
                    .setNeutralButton(R.string.editor_compare_again, null)
                    .setOnDismissListener { if (c.isActive) c.resume(Unit) }
                    .show()

                // the button must not close the page
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { run() }

                c.invokeOnCancellation { dialog.dismiss() }

                run()
            }
        }
    }

    // ---- inside ------------------------------------------------------------------------------------

    private fun setDirty(value: Boolean) {
        dirty = value
        titleView.text = if (value) "● $fileName" else fileName

        undoButton.alpha = if (editor.undo.canUndo) 1f else 0.35f
        redoButton.alpha = if (editor.undo.canRedo) 1f else 0.35f
    }

    private fun refreshStatus() {
        val sb = SpannableStringBuilder(
            context.getString(R.string.editor_status, editor.caretLine, editor.caretColumn, editor.logicalLineCount),
        )

        // CUSTOM.md #50, #53: the problem on the caret's line, or which lines of a big text are shown
        val here = editor.problemsAt(editor.caretLine - 1)

        if (here.isNotEmpty()) {
            val extra = if (here.size > 1) " (+${here.size - 1})" else ""
            val start = sb.length

            sb.append("  ").append(describe(here[0])).append(extra)
            sb.setSpan(
                ForegroundColorSpan(if (here[0].severity == 0) colorBad else colorWarn),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        } else if (editor.isWindowed) {
            sb.append("  ·  ").append(context.getString(R.string.editor_window_status, editor.windowFirstLine, editor.windowLastLine))
        }

        statusView.text = sb

        previousWindowButton.visibility = if (editor.isWindowed) View.VISIBLE else View.GONE
        nextWindowButton.visibility = if (editor.isWindowed) View.VISIBLE else View.GONE
        previousWindowButton.alpha = if (editor.windowFirstLine > 1) 1f else 0.35f
        nextWindowButton.alpha = if (editor.windowLastLine < editor.logicalLineCount) 1f else 0.35f

        undoButton.alpha = if (editor.undo.canUndo) 1f else 0.35f
        redoButton.alpha = if (editor.undo.canRedo) 1f else 0.35f
    }

    // CUSTOM.md #50: the number of problems on the button; the button is only there for YAML files
    private fun refreshProblems() {
        val count = editor.allProblems().size

        // CUSTOM.md #53: only when there is something to show (no "✓" that takes room when all is well)
        problemButton.visibility = if (lint && count > 0) View.VISIBLE else View.GONE
        problemButton.text = "\u26A0 $count"
        problemButton.setTextColor(colorWarn)
    }

    private fun describe(d: Diagnostic): String = when (d.kind) {
        DiagKind.Tab -> context.getString(R.string.editor_problem_tab)
        DiagKind.IndentDeeper -> context.getString(R.string.editor_problem_deeper)
        DiagKind.IndentMisaligned -> context.getString(R.string.editor_problem_misaligned)
        DiagKind.DupKey -> context.getString(R.string.editor_problem_dup_key, d.arg, d.other)
        DiagKind.DupName -> context.getString(R.string.editor_problem_dup_name, d.arg, d.other)
        DiagKind.MissingProxy -> context.getString(R.string.editor_problem_missing_proxy, d.arg)
        DiagKind.MissingPolicy -> context.getString(R.string.editor_problem_missing_policy, d.arg)
        DiagKind.MissingProvider -> context.getString(R.string.editor_problem_missing_provider, d.arg)
        DiagKind.MissingRuleSet -> context.getString(R.string.editor_problem_missing_ruleset, d.arg)
    }

    // a list of the problems; a tap on one goes to its line
    private fun showProblems() {
        val all = editor.allProblems()

        if (all.isEmpty()) {
            launch { toast(context.getString(R.string.editor_problems_none)) }

            return
        }

        val shown = all.take(300)
        val items = shown.map { context.getString(R.string.editor_problem_item, it.line + 1, describe(it)) }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.editor_problems_title, all.size, shown.size))
            .setItems(items) { _, which -> goToLine(shown[which].line + 1, false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askLine() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = context.getString(R.string.editor_goto_hint, editor.logicalLineCount)
            setSingleLine()
        }
        val box = FrameLayout(context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.editor_goto_line)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text?.toString()?.toIntOrNull()?.let { goToLine(it, false) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun changeSize(delta: Int) {
        val next = (sizeSp + delta).coerceIn(MIN_SIZE, MAX_SIZE)

        if (next == sizeSp) return

        sizeSp = next
        prefs.edit().putInt("size_sp", next).apply()
        editor.setSizeSp(next.toFloat())
        refreshSize()
    }

    private fun refreshSize() {
        sizeView.text = sizeSp.toString()
        smallerButton.alpha = if (sizeSp > MIN_SIZE) 1f else 0.35f
        biggerButton.alpha = if (sizeSp < MAX_SIZE) 1f else 0.35f
    }

    private fun refreshCase() {
        caseButton.setTextColor(if (matchCase) colorAccent else colorMuted)
    }

    private fun toggleFind() {
        if (findBar.visibility == View.VISIBLE) {
            findBar.visibility = View.GONE
            editor.clearMatch()
            editor.requestFocus()
        } else {
            findBar.visibility = View.VISIBLE
            findInput.requestFocus()
        }
    }

    // [keepPlace]: look from the start of the current match, so that a match is not skipped while the word is typed
    private fun findFrom(forward: Boolean, keepPlace: Boolean = false) {
        val query = findInput.text?.toString() ?: ""

        if (keepPlace) editor.setSelection(editor.selectionStart.coerceAtLeast(0))

        val found = editor.find(query, forward, matchCase)
        val (index, total) = editor.matchPosition(query, matchCase)

        countView.text = "$index/$total"
        countView.setTextColor(if (query.isNotEmpty() && !found) colorBad else colorMuted)
    }

    private fun replaceOne() {
        val query = findInput.text?.toString() ?: ""

        editor.replaceCurrent(query, replaceInput.text?.toString() ?: "", matchCase)

        val (index, total) = editor.matchPosition(query, matchCase)

        countView.text = "$index/$total"
    }

    private fun replaceEvery() {
        val query = findInput.text?.toString() ?: ""
        val count = editor.replaceAll(query, replaceInput.text?.toString() ?: "", matchCase)

        countView.text = "0/0"

        launch { toast(context.getString(R.string.editor_replaced, count)) }
    }

    private fun showMore(anchor: View) {
        PopupMenu(context, anchor).apply {
            // CUSTOM.md #47: save to / open from anywhere on the phone
            // CUSTOM.md #50: jump to a line, show Tabs and trailing spaces
            // CUSTOM.md #56: the history of the config and the compare against the config in use
            if (split) {
                menu.add(0, 5, 0, context.getString(R.string.editor_history_menu))
                menu.add(0, 6, 0, context.getString(R.string.editor_compare_menu))
            }
            menu.add(0, 3, 0, context.getString(R.string.editor_goto_line))
            menu.add(0, 4, 1, context.getString(R.string.editor_show_whitespace)).apply {
                isCheckable = true
                isChecked = editor.showWhitespace
            }
            menu.add(0, 1, 2, context.getString(R.string.editor_save_as))
            if (!readOnly) menu.add(0, 2, 3, context.getString(R.string.editor_import_from))
            menu.add(0, 0, 4, context.getString(R.string.editor_external))
            setOnMenuItemClickListener {
                when (it.itemId) {
                    3 -> askLine()
                    5 -> requests.trySend(Request.History)
                    6 -> requests.trySend(Request.Compare)
                    4 -> {
                        val on = !editor.showWhitespace

                        editor.showWhitespace = on
                        prefs.edit().putBoolean("show_whitespace", on).apply()
                    }
                    1 -> requests.trySend(Request.SaveAs)
                    2 -> requests.trySend(Request.ImportFrom)
                    else -> requests.trySend(Request.External)
                }

                true
            }
        }.show()
    }

    private fun action(label: String, description: String?, onClick: (View) -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            // CUSTOM.md #56: with the apply button the bar holds one more word, so the buttons are closer
            minWidth = dp(if (split) 38 else 44)
            minHeight = dp(44)
            setPadding(dp(if (split) 5 else 8), 0, dp(if (split) 5 else 8), 0)
            isClickable = true
            contentDescription = description ?: label
            setBackgroundResource(context.resolveThemedResourceId(android.R.attr.selectableItemBackground))
            setOnClickListener { onClick(it) }
        }
    }

    // a key of the symbol row: it must not take the focus, or the keyboard would close at every tap
    private fun symbol(label: String, mono: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(colorText)

            if (mono) typeface = Typeface.MONOSPACE

            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(34)
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundResource(context.resolveThemedResourceId(android.R.attr.selectableItemBackground))
            setOnClickListener { onClick() }
        }
    }
}
