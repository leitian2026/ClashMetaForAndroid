package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.CheckBox
import android.widget.FrameLayout
import android.text.StaticLayout
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.core.model.IpTestExtResult
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CUSTOM (CUSTOM.md #52, not upstream; replaces the text page of #43): the page of "test other IPs" as a table like
// the other apps have it, with the live status under it. The IP column stays on the left while the rest scrolls
// sideways. Everything here is laid out again from the rows it is given (at most 20); nothing runs on its own, the
// activity gives it one more row after each address and one more line after each event.
// r62 (CUSTOM.md #54): text is 2 sp bigger than before; the columns share the whole width of the page when it is wide
// enough (a tablet), so nothing hides off screen, and long text wraps (the rows are as high as their tallest cell);
// only when even the narrowest columns do not fit (a phone) the table scrolls sideways with the IP column fixed.
// r79 (CUSTOM.md #62): direct mode has the columns IP | 入口机房 | 归属运营商 | 归属地 | 出口国家 | 端口 | 失败率 | 最小 / 最大 / 平均延迟 |
// 下载测速 | 速度. The data centre has its country's flag, its code and, in brackets, the Chinese name and the English city;
// the registered country and the exit country have a flag and the name. When all columns but 速度 fit on the page, they
// fill it and 速度 sits behind the right edge (scroll sideways to see it); the header has room for two lines.
// r85 (CUSTOM.md #68): direct mode keeps 抖动 / 分段耗时 after 平均延迟. Via-node mode puts link metrics first, then exit / target details, then 分段耗时.
// CUSTOM (CUSTOM.md #160): keep two selected values readable in the saved-table header summary.
internal fun savedIpFilterSummary(selected: Collection<String>): String = when {
    selected.isEmpty() -> ""
    selected.size == 1 -> selected.first()
    else -> selected.take(2).joinToString("、") +
        if (selected.size > 2) " +${selected.size - 2}" else ""
}

class IpTestView(
    private val context: Context,
    private val direct: Boolean,
    private val target: Boolean = false,
    private val savedTable: Boolean = false,
    visibleSavedColumns: Set<String>? = null,
    private val showDirectSpeed: Boolean = true,
) {
    var onCopy: (String) -> Unit = {}
    var onSpeed: (String) -> Unit = {} // a tap on "下载测速" of a row
    var onSpeedCancel: () -> Unit = {} // a tap on "取消" of the row that is downloading
    var onDetail: (String, String) -> Unit = { _, _ -> } // a tap on the ports of a row: title, text

    // CUSTOM.md #68: JITTER (抖动) and PHASE (分段耗时) sit right after AVG
    internal enum class Key { COLO, ASN, GEO, LAND, PORTS, LOSS, MIN, MAX, AVG, JITTER, PHASE, EXIT, EXIT_IP, PORT, TEST, SPEED, SPEED_NODE, TARGET, TCOLO }
    private val savedTableState = SavedTableState(context, visibleSavedColumns)

    private var activeTestSpeedButton: ProgressMaterialButton? = null
    private val liveKeys = if (direct) {
        listOf(Key.COLO, Key.ASN, Key.GEO, Key.LAND, Key.PORTS, Key.LOSS, Key.MIN, Key.MAX, Key.AVG, Key.JITTER, Key.PHASE, Key.TEST, Key.SPEED)
    } else if (target) {
        // CUSTOM.md #66: with a target address the tries go to that site: 目标站反应 and 出口机房 (its Cf-Ray) are added
        // r248: keep the node path in order: ingress endpoint and metadata, link metrics, then egress and target.
        listOf(Key.PORT, Key.COLO, Key.ASN, Key.GEO, Key.LOSS, Key.MIN, Key.MAX, Key.AVG, Key.JITTER, Key.EXIT, Key.EXIT_IP, Key.TCOLO, Key.TARGET, Key.PHASE, Key.TEST, Key.SPEED)
    } else {
        // CUSTOM.md #63: through a node too: 机房 | 归属运营商 | 归属地 | 落地 | 端口 | 丢包率 | ...
        // The no-target variant has no target-response / target-colo fields. (CUSTOM.md #43: the 比对照快 column was removed in r309.)
        listOf(Key.PORT, Key.COLO, Key.ASN, Key.GEO, Key.LOSS, Key.MIN, Key.MAX, Key.AVG, Key.JITTER, Key.EXIT, Key.EXIT_IP, Key.PHASE, Key.TEST, Key.SPEED)
    }
    private var keys = if (savedTable) {
        // CUSTOM.md #162/#160: filter only the saved-table instance; the IP column remains fixed outside this list.
        savedTableState.savedTableKeys.filter { (showDirectSpeed || it != Key.SPEED) && (savedTableState.visibleSavedColumns == null || it.name in savedTableState.visibleSavedColumns) }
    } else liveKeys

    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val colorBad = if (night) Color.parseColor("#EF9A9A") else Color.parseColor("#C62828")
    private val colorWarn = if (night) Color.parseColor("#FFB74D") else Color.parseColor("#E65100")
    private val colorGood = if (night) Color.parseColor("#81C784") else Color.parseColor("#2E7D32")
    private val colorLink = themeColor(com.google.android.material.R.attr.colorPrimary, if (night) Color.parseColor("#90CAF9") else Color.parseColor("#1565C0"))
    // App theme colours: keep semantic result colours below, but use the same text and surface roles as native pages.
    private val colorMuted = themeColor(android.R.attr.textColorSecondary, if (night) Color.parseColor("#BDBDBD") else Color.parseColor("#616161"))
    private val colorText = themeColor(android.R.attr.textColorPrimary, if (night) Color.WHITE else Color.BLACK)
    private val colorSurface = themeColor(com.google.android.material.R.attr.colorSurface, if (night) Color.parseColor("#1F2328") else Color.WHITE)
    private val colorHighlight = themeColor(android.R.attr.colorControlHighlight, if (night) Color.WHITE else Color.BLACK)
    private val colorStripe = Color.argb(0x14, Color.red(colorHighlight), Color.green(colorHighlight), Color.blue(colorHighlight))
    // Match SavedIpDesign's operator-group divider: same theme color and 1dp thickness.
    private val colorTableDivider = themeColor(android.R.attr.colorControlHighlight, 0x22000000)

    private fun rowWithDivider(content: View, backgroundColor: Int, showDivider: Boolean): FrameLayout =
        FrameLayout(context).apply {
            setBackgroundColor(backgroundColor)
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            if (showDivider) {
                addView(View(context).apply { setBackgroundColor(colorTableDivider) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.BOTTOM))
            }
        }

    private val rowH = dp(48) // the least height of a row; a row with wrapped text is taller
    private val headH = dp(40)

    private var rows: List<IpTestRow> = emptyList()
    internal var recommendedIps: Set<String> = emptySet() // CUSTOM.md #166: calculated once per result refresh
    private var savedTableAvailableWidth: Int = 0
    private var liveTableScrollX: Int = 0 // live position, retained across table rebuilds
    private var redrawPending = false
    private val redrawAction = Runnable {
        redrawPending = false
        redrawNow()
    }
    private var dnsReport: DnsCheckReport? = null
    internal var colos: Map<String, String> = emptyMap()
    internal var coloCountries: Map<String, String> = emptyMap() // CUSTOM.md #62: data centre code -> country code
    private var speedIp: String? = null
    private var downloadProgressPercent: Int? = null
    private var ownerLookupIp: String? = null
    private val ownerLookupCells = HashMap<String, TextView>()
    private var testing = false

    private val env = object : IpTestEnv {
        override val context: Context get() = this@IpTestView.context
        override val direct: Boolean get() = this@IpTestView.direct
        override val target: Boolean get() = this@IpTestView.target
        override var rows: List<IpTestRow>
            get() = this@IpTestView.rows
            set(value) { this@IpTestView.rows = value }
        override var colos: Map<String, String>
            get() = this@IpTestView.colos
            set(value) { this@IpTestView.colos = value }
        override var coloCountries: Map<String, String>
            get() = this@IpTestView.coloCountries
            set(value) { this@IpTestView.coloCountries = value }
        override val speedIp: String? get() = this@IpTestView.speedIp
        override val testing: Boolean get() = this@IpTestView.testing
        override var activeTestSpeedButton: ProgressMaterialButton?
            get() = this@IpTestView.activeTestSpeedButton
            set(value) { this@IpTestView.activeTestSpeedButton = value }
        override val downloadProgressPercent: Int? get() = this@IpTestView.downloadProgressPercent
        override val ownerLookupCells: MutableMap<String, TextView> get() = this@IpTestView.ownerLookupCells
        override val ownerLookupIp: String? get() = this@IpTestView.ownerLookupIp
        override val recommendedIps: Set<String> get() = this@IpTestView.recommendedIps
        override val onCopy: (String) -> Unit get() = this@IpTestView.onCopy
        override val onDetail: (String, String) -> Unit get() = this@IpTestView.onDetail
        override val onSpeed: (String) -> Unit get() = this@IpTestView.onSpeed
        override val onSpeedCancel: () -> Unit get() = this@IpTestView.onSpeedCancel
        override val colorText: Int get() = this@IpTestView.colorText
        override val colorMuted: Int get() = this@IpTestView.colorMuted
        override val colorGood: Int get() = this@IpTestView.colorGood
        override val colorWarn: Int get() = this@IpTestView.colorWarn
        override val colorBad: Int get() = this@IpTestView.colorBad
        override val colorLink: Int get() = this@IpTestView.colorLink
        override val colorSurface: Int get() = this@IpTestView.colorSurface
        override val night: Boolean get() = this@IpTestView.night
        override val showDirectSpeed: Boolean get() = this@IpTestView.showDirectSpeed
        override val rowH: Int get() = this@IpTestView.rowH
        override val headH: Int get() = this@IpTestView.headH
        override val colorStripe: Int get() = this@IpTestView.colorStripe
        override val colorTableDivider: Int get() = this@IpTestView.colorTableDivider
        override val dnsReport: DnsCheckReport? get() = this@IpTestView.dnsReport
        override fun rowWithDivider(content: View, backgroundColor: Int, showDivider: Boolean): FrameLayout =
            this@IpTestView.rowWithDivider(content, backgroundColor, showDivider)
        override val keys: List<Key> get() = this@IpTestView.keys
        override val savedTable: Boolean get() = this@IpTestView.savedTable
        override var savedTableAvailableWidth: Int
            get() = this@IpTestView.savedTableAvailableWidth
            set(value) { this@IpTestView.savedTableAvailableWidth = value }
        override val tableBox: LinearLayout get() = this@IpTestView.tableBox
        override fun speedButtonTextRes(): Int = columnWidths.speedButtonTextRes()
        override fun speedButtonMinHeight(): Int = columnWidths.speedButtonMinHeight()
    }
    private val cells: IpTestCells = IpTestCells(env)
    private val columnWidths: IpTestColumnWidths = IpTestColumnWidths(env, cells)
    private val diagnostics = IpTestDiagnosticsView(env, cells, columnWidths) { redraw() }
    private val savedIpTable = SavedIpTableView(env, cells, columnWidths, savedTableState)

    private fun plainOf(k: Key, r: IpTestRow): String = cells.plainOf(k, r)
    private fun ownerCellText(r: IpTestRow): String = cells.ownerCellText(r)
    private fun ownerCell(r: IpTestRow): View = cells.ownerCell(r)
    internal fun cellOf(k: Key, r: IpTestRow, w: Int): View = cells.cellOf(k, r, w)
    private fun targetCell(r: IpTestRow): View = cells.targetCell(r)
    private fun coloCell(r: IpTestRow, w: Int, code: String = r.colo): View = cells.coloCell(r, w, code)
    private fun textCell(text: String, color: Int, bold: Boolean = false): TextView = cells.textCell(text, color, bold)
    private fun headCell(text: String, w: Int): TextView = cells.headCell(text, w)
    private fun portsCell(r: IpTestRow): View = cells.portsCell(r)
    private fun testCell(r: IpTestRow): View = cells.testCell(r)
    internal fun ipCell(r: IpTestRow, showRecommendation: Boolean = true, showFailureDetail: Boolean = true): View =
        cells.ipCell(r, showRecommendation, showFailureDetail)

    private var infoText: CharSequence = ""
    private val logLines = ArrayList<String>()

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val infoHead = TextView(context).apply {
        textSize = 15f
        setTextColor(colorText)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val infoProgress = TextView(context).apply {
        textSize = 14f
        setTextColor(colorMuted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private val infoHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(infoHead, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(infoProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val info = TextView(context).apply {
        textSize = 15f
        setTextColor(colorText)
        setPadding(0, 0, 0, dp(8))
    }

    // CUSTOM.md #156 step 75: the long explanation under the target line is collapsible; collapsed by default.
    private var noteExpanded = false
    private val noteBody = TextView(context).apply {
        textSize = 15f
        setTextColor(colorMuted)
        visibility = View.GONE
    }
    private val noteToggle = TextView(context).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(colorText)
        setPadding(0, dp(2), 0, dp(6))
        visibility = View.GONE
        setOnClickListener {
            noteExpanded = !noteExpanded
            applyNoteState()
        }
    }

    private fun applyNoteState() {
        noteBody.visibility = if (noteExpanded) View.VISIBLE else View.GONE
        // the grey paragraph above "详细诊断" folds with the same button
        hint.visibility = if (noteExpanded) View.VISIBLE else View.GONE
        noteToggle.text = "${context.getString(R.string.iptest_note_title)}  ${if (noteExpanded) "▴" else "▾"}"
    }

    private val tableBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val hint = TextView(context).apply {
        setText(R.string.iptest_table_hint)
        textSize = 13f
        setTextColor(colorMuted)
        setPadding(0, dp(6), 0, dp(10))
    }

    private val logTitle = TextView(context).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(colorText)
        setPadding(0, 0, 0, dp(4))
    }

    private var totalIps = 0
    private var testedIps = 0

    private val logView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextColor(colorText)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setText("", TextView.BufferType.EDITABLE)
    }

    private val logScroll = ScrollView(context).apply {
        // the log has its own colour that follows the app theme (light or dark), not a fixed dark one
        background = GradientDrawable().apply {
            setColor(colorSurface)
            cornerRadius = dp(6).toFloat()
        }

        // CUSTOM.md #156 step 75: the log scrolls first; once it is at its top or bottom (or too short to scroll) in the
        // direction the finger is moving, the gesture goes back to the page so the whole page keeps scrolling
        var lastY = 0f
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = ev.y
                    v.parent?.requestDisallowInterceptTouchEvent(v.canScrollVertically(-1) || v.canScrollVertically(1))
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - lastY
                    lastY = ev.y
                    // finger moving down = content moves down = scrolling towards the top
                    val canScroll = if (dy > 0) v.canScrollVertically(-1) else if (dy < 0) v.canScrollVertically(1) else true
                    v.parent?.requestDisallowInterceptTouchEvent(canScroll)
                }
            }

            false
        }

        addView(logView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        // the table is laid out for the width the page gives it: laid out again when that width changes (rotation,
        // split screen), and only then
        tableBox.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL && r - l > 0 && rows.isNotEmpty()) redraw()
        }

        root.addView(infoHeader, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(info, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(noteToggle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(noteBody, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tableBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(hint, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(diagnostics.box, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(logTitle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(logScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        redraw()
    }

    /** The lines above the table: [head] bold, [sub] grey, [warnings] orange (invalid words, skipped domains and cut entries). */
    fun setInfo(head: String, sub: String, warnings: List<String>) {
        val sb = android.text.SpannableStringBuilder()

        // CUSTOM.md #156 step 75: the first line of [sub] stays visible; the explanation after it goes into the collapsible block.
        val cut = sub.indexOf('\n')
        val shownSub = if (cut >= 0) sub.substring(0, cut) else sub
        val note = if (cut >= 0) sub.substring(cut + 1) else ""

        add(sb, head + "\n", bold = true)
        val details = android.text.SpannableStringBuilder()

        if (shownSub.isNotEmpty()) {
            val highlightedSub = highlightTargetUrl(shownSub, colorMuted, colorBad)
            sb.append(highlightedSub).append('\n')
            details.append(highlightedSub).append('\n')
        }

        for (w in warnings) {
            add(sb, w + "\n", colorWarn)
            add(details, w + "\n", colorWarn)
        }

        // no trailing blank line: it made the gap above the note button too big
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        while (details.isNotEmpty() && details[details.length - 1] == '\n') details.delete(details.length - 1, details.length)

        infoText = sb
        infoHead.text = highlightUnicomIpv4(head, colorBad)
        info.text = details
        info.setPadding(0, 0, 0, if (note.isEmpty()) dp(8) else 0)

        noteBody.text = note
        noteToggle.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        if (note.isEmpty()) {
            noteBody.visibility = View.GONE
            hint.visibility = View.VISIBLE
        } else {
            applyNoteState()
        }
    }

    fun setColos(map: Map<String, String>, countries: Map<String, String>) {
        colos = map
        coloCountries = countries
        redraw()
    }

    fun setRows(list: List<IpTestRow>) {
        // Rank the original probe order so exact ties keep their original positions.
        val nextRecommended = IpRecommend.forTest(list)
        val nextRows = sorted(list)
        if (rows == nextRows && recommendedIps == nextRecommended) return
        recommendedIps = nextRecommended
        rows = nextRows
        redraw()
        if (target || !direct) updateDiagnostics()
    }

    private fun updateDiagnostics() = diagnostics.updateDiagnostics()

    fun setDnsReport(report: DnsCheckReport) {
        dnsReport = report
        diagnostics.box.visibility = View.VISIBLE
        updateDiagnostics()
    }



    // Target diagnostics use one table. The IP column is fixed at the left; the remaining ten columns
    // scroll as one grid. Column widths follow the longest value and the longest unbroken header word.














    // The diagnostics table follows the IP table: one IP per vertical row; diagnostic fields are horizontal columns.
    // The IP column stays fixed while the remaining columns scroll horizontally.




    /** A reason cut to something that fits a cell: the timeouts get a word of their own. */










    /** Two short lines: the TCP and the UDP path MTU (the UDP probe measures the payload, so the headers are added back). */








    /** [testing]: the round is running (no download then); [ip]: the row whose download is running. */
    fun setBusy(testing: Boolean, ip: String?) {
        if (speedIp != ip) downloadProgressPercent = null
        this.testing = testing
        speedIp = ip
        activeTestSpeedButton = null
        redraw()
    }

    fun setDownloadProgress(ip: String, direct: Boolean, percent: Int?) {
        if (savedTable) {
            updateSavedSpeedProgress(ip, direct, percent)
            return
        }
        if (speedIp != ip) return
        downloadProgressPercent = percent?.coerceIn(0, 100)
        val button = activeTestSpeedButton ?: return
        button.text = if (downloadProgressPercent == null) context.getString(R.string.check_cancel)
        else context.getString(R.string.check_cancel) + "\n" + downloadProgressPercent + "%"
        button.progressLevel = (downloadProgressPercent ?: 0) * 100
    }

    fun setOwnerLookup(ip: String?) {
        if (ownerLookupIp == ip) return
        val previous = ownerLookupIp
        ownerLookupIp = ip
        val affected = listOfNotNull(previous, ip).distinct()
        val rowsByIp = rows.associateBy { it.ip }
        val canUpdateInPlace = affected.all { address ->
            rowsByIp[address] == null || ownerLookupCells.containsKey(address)
        }
        if (!canUpdateInPlace) {
            redraw()
            return
        }
        affected.forEach { address ->
            val row = rowsByIp[address] ?: return@forEach
            ownerLookupCells[address]?.text = ownerCellText(row)
        }
    }

    /** Progress counts completed IP probes, including failed results; a cancelled in-flight probe is not counted. */
    fun setProgress(total: Int, tested: Int) {
        totalIps = total.coerceAtLeast(0)
        testedIps = tested.coerceIn(0, totalIps)
        val progress = if (totalIps > 0) {
            context.getString(R.string.iptest_log_progress, totalIps, testedIps)
        } else ""
        val title = android.text.SpannableStringBuilder(progress)
        if (progress.isNotEmpty()) {
            // CUSTOM (CUSTOM.md #156 step 23): keep the original two-part count highlighting.
            val testedAt = progress.indexOf("，已测")
            if (testedAt > 0) {
                title.setSpan(
                    android.text.style.ForegroundColorSpan(if (night) Color.parseColor("#81C784") else Color.parseColor("#2E7D32")),
                    0,
                    testedAt,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                title.setSpan(
                    android.text.style.ForegroundColorSpan(if (night) Color.parseColor("#66BB6A") else Color.parseColor("#1B5E20")),
                    testedAt,
                    title.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                title.setSpan(android.text.style.ForegroundColorSpan(colorMuted), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        infoProgress.text = title
        infoProgress.visibility = if (progress.isEmpty()) View.GONE else View.VISIBLE
        logTitle.setText(R.string.iptest_log_title)
    }

    /** One line of the live status, `[HH:MM:SS] text`; at most 200 lines are kept. */
    private var logScrollPending = false

    fun log(text: String) {
        val line = "[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "] " + text
        logLines += line

        val editable = logView.editableText
        if (editable.isNotEmpty()) editable.append('\n')
        editable.append(line)

        while (logLines.size > MAX_LOG) {
            logLines.removeAt(0)
            val firstLineEnd = editable.indexOf("\n")
            if (firstLineEnd >= 0) editable.delete(0, firstLineEnd + 1) else editable.clear()
        }

        // Multiple log events in one frame share one scroll/layout request.
        if (!logScrollPending) {
            logScrollPending = true
            logScroll.postOnAnimation {
                logScrollPending = false
                logScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    fun savedLogs(): List<String> = logLines.toList()

    fun setLogs(lines: List<String>) {
        logLines.clear()
        logLines.addAll(lines.takeLast(MAX_LOG))
        logView.setText(logLines.joinToString("\n"), TextView.BufferType.EDITABLE)
    }

    /**
     * The table and the log as text (what the copy button at the top of the page gets).
     * Use a Markdown table so the columns remain readable when pasted into a chat or issue.
     */
    fun plain(): String {
        val sb = StringBuilder()

        sb.append(infoText).append("\n").append(if (noteBody.text.isNotEmpty()) noteBody.text.toString() + "\n" else "").append("\n\n")
        sb.append("| ").append(markdownCell(context.getString(R.string.iptest_col_ip)))
        for (k in liveKeys) sb.append(" | ").append(markdownCell(title(k)))
        sb.append(" |\n")

        sb.append("| --- ")
        repeat(liveKeys.size) { sb.append("| --- ") }
        sb.append("|\n")

        for (r in rows) {
            sb.append("| ").append(markdownCell(r.ip))
            for (k in liveKeys) {
                // CUSTOM.md #68: keep the three phase timings on one line in the copied table.
                val value = plainOf(k, r).let { if (k == Key.PHASE) it.replace('\n', ' ') else it }
                sb.append(" | ").append(markdownCell(value))
            }
            sb.append(" |\n")
        }

        sb.append("\n").append(context.getString(R.string.iptest_log_title))
        if (totalIps > 0) sb.append(context.getString(R.string.iptest_log_progress, totalIps, testedIps))
        if (diagnostics.diagnosticsPlain.isNotEmpty()) {
            sb.append("\n\n").append(context.getString(R.string.iptest_diag_title)).append("\n").append(diagnostics.diagnosticsPlain)
        }

        sb.append('\n').append(logLines.joinToString("\n"))

        return sb.toString()
    }

    // Escape Markdown table delimiters and line breaks without changing the displayed values.

    // quickest average first, the ones that failed last (in the order they were tested)
    private fun sorted(list: List<IpTestRow>): List<IpTestRow> {
        return list.sortedWith(
            compareBy<IpTestRow> { if (it.ok) 0 else 1 }.thenBy { if (it.ok) it.avg else 0 },
        )
    }

    private fun redraw() {
        if (redrawPending) return
        redrawPending = true
        tableBox.postOnAnimation(redrawAction)
    }

    private fun redrawNow() {
        ownerLookupCells.clear()
        activeTestSpeedButton = null
        tableBox.removeAllViews()

        if (rows.isEmpty()) return

        val w = widths()
        val gridW = w.drop(1).sum()

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // header: as high as its tallest title (a narrow column wraps)
        val heads = ArrayList<View>()
        val headLeft = headCell(context.getString(R.string.iptest_col_ip), w[0])

        keys.forEachIndexed { i, k -> heads += headCell(title(k), w[i + 1]) }

        // CUSTOM.md #62: a little room more than measured, like the rows have: the second line of a title that wraps
        // (机房（城市）, 最小延迟) sat on the lower edge of the grey and looked cut
        val hh = maxOf(headH, heightOf(headLeft, w[0]), heads.mapIndexed { i, v -> heightOf(v, w[i + 1]) }.maxOrNull() ?: 0) + dp(6)

        left.addView(headLeft, lp(w[0], hh))

        // CUSTOM.md #55: the grey of the header is one background on the whole row, not one per title; per title a
        // title that wraps to two lines left a notch in the grey above it
        val headBg = colorSurface
        val headRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(headBg)
        }

        heads.forEach { it.setBackgroundColor(Color.TRANSPARENT) }

        heads.forEachIndexed { i, v -> headRow.addView(v, lp(w[i + 1], hh)) }

        grid.addView(headRow, lp(gridW, hh))

        rows.forEachIndexed { i, r ->
            val ipView = ipCell(r)
            val cells = keys.mapIndexed { n, k -> cellOf(k, r, w[n + 1]) }

            // one height for the whole row, so the fixed IP column and the sideways part stay level
            // CUSTOM.md #55: a little room more than measured, so the last line of a cell (the last port) is not cut
            val h = maxOf(rowH, heightOf(ipView, w[0]), cells.mapIndexed { n, v -> heightOf(v, w[n + 1]) }.maxOrNull() ?: 0) + dp(4)

            ipView.setBackgroundColor(colorSurface)
            left.addView(
                rowWithDivider(ipView, colorSurface, i < rows.lastIndex),
                lp(w[0], h),
            )

            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(colorSurface)
            }

            cells.forEachIndexed { n, v -> line.addView(v, lp(w[n + 1], h)) }

            grid.addView(
                rowWithDivider(line, colorSurface, i < rows.lastIndex),
                lp(gridW, h),
            )
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(grid, ViewGroup.LayoutParams(gridW, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnScrollChangeListener { _, x, _, _, _ -> liveTableScrollX = x }
        }
        val restoreScrollListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if (view.width <= 0) return
                view.removeOnLayoutChangeListener(this)
                view.scrollTo(liveTableScrollX, 0)
            }
        }
        scroll.addOnLayoutChangeListener(restoreScrollListener)

        tableBox.addView(left, LinearLayout.LayoutParams(w[0], ViewGroup.LayoutParams.WRAP_CONTENT))
        tableBox.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }


    // CUSTOM.md #162: saved-IP rows use the live table's header, cell, IP and width factories.


    private fun isSavedTableFilterable(key: Key): Boolean = savedIpTable.isSavedTableFilterable(key)
    internal fun isSavedSpeedColumn(index: Int): Boolean = savedIpTable.isSavedSpeedColumn(index)
    internal fun savedTableColumnOptions(): List<Pair<String, String>> = savedIpTable.savedTableColumnOptions()
    internal fun savedTableColumnName(columnIndex: Int): String = savedIpTable.savedTableColumnName(columnIndex)
    internal fun savedTableFilterOptions(entries: List<SavedIpEntry>, columnName: String): List<String> =
        savedIpTable.savedTableFilterOptions(entries, columnName)
    internal fun savedTableFilterValue(entry: SavedIpEntry, columnName: String): String =
        savedIpTable.savedTableFilterValue(entry, columnName)
    private fun savedHeaderCell(title: String, width: Int, selected: Set<String>, summaryOverride: String? = null, showSpeedUnit: Boolean = false, onClick: (View) -> Unit): View =
        savedIpTable.savedHeaderCell(title, width, selected, summaryOverride, showSpeedUnit, onClick)
    internal fun savedTableView(
        entries: List<SavedIpEntry>,
        widths: IntArray,
        scrollX: Int,
        onScroll: (Int) -> Unit,
        isSelectionMode: () -> Boolean,
        isSelected: (String) -> Boolean,
        onCopy: (String) -> Unit,
        onDetail: (String, String) -> Unit,
        onFailureDetail: (String, String) -> Unit = onDetail,
        onLongPress: (String) -> Boolean,
        selectedFilters: Map<String, Set<String>> = emptyMap(),
        speedSortLabel: String = "",
        speedSortColumnName: String? = null,
        onHeaderClick: (Int, View) -> Unit = { _, _ -> },
        configIpSources: Map<String, Set<String>> = emptyMap(),
        recommendedIps: Set<String> = emptySet(),
        speedAvailable: Boolean = true,
        speedBusy: Boolean = false,
        onSpeedTest: (String, Boolean) -> Unit = { _, _ -> },
    ): View = savedIpTable.savedTableView(
        entries, widths, scrollX, onScroll, isSelectionMode, isSelected, onCopy, onDetail,
        onFailureDetail, onLongPress, selectedFilters, speedSortLabel, speedSortColumnName,
        onHeaderClick, configIpSources, recommendedIps, speedAvailable, speedBusy, onSpeedTest,
    )
    internal fun setSavedSpeedAvailability(available: Boolean, busy: Boolean) =
        savedIpTable.setSavedSpeedAvailability(available, busy)
    internal fun updateSavedSpeed(address: String, direct: Boolean, speed: Double, short: Boolean, available: Boolean, busy: Boolean) =
        savedIpTable.updateSavedSpeed(address, direct, speed, short, available, busy)
    internal fun updateSavedSpeedProgress(address: String, direct: Boolean, percent: Int?) =
        savedIpTable.updateSavedSpeedProgress(address, direct, percent)
    private fun updateSavedRowHeight(address: String) = savedIpTable.updateSavedRowHeight(address)
    internal fun moveSavedRow(address: String, orderedEntries: List<SavedIpEntry>) =
        savedIpTable.moveSavedRow(address, orderedEntries)
    private fun savedSpeedText(label: String, speed: Double, showShort: Boolean, cellWidth: Int): CharSequence =
        savedIpTable.savedSpeedText(label, speed, showShort, cellWidth)
    private fun savedSpeedCell(
        label: String, speed: Double, short: Boolean, available: Boolean, busy: Boolean, cellWidth: Int, onClick: () -> Unit,
    ): SavedSpeedCell = savedIpTable.savedSpeedCell(label, speed, short, available, busy, cellWidth, onClick)

    internal fun savedTableWidths(availableWidth: Int, savedRows: List<IpTestRow>, cityNames: Map<String, String>, countryCodes: Map<String, String>): IntArray =
        columnWidths.savedTableWidths(availableWidth, savedRows, cityNames, countryCodes)
    private fun heightOf(v: View, w: Int): Int = columnWidths.heightOf(v, w)
    private fun contentNeed(k: Key): Int = columnWidths.contentNeed(k)
    private fun widths(): IntArray = columnWidths.widths()
    private fun headerNeed(k: Key): Int = columnWidths.headerNeed(k)
    private fun speedButtonNeed(): Int = columnWidths.speedButtonNeed()
    private fun speedButtonTextRes(): Int = columnWidths.speedButtonTextRes()
    private fun speedButtonMinHeight(): Int = columnWidths.speedButtonMinHeight()
    private fun phaseNeed(): Int = columnWidths.phaseNeed()
    private fun exitIpNeed(): Int = columnWidths.exitIpNeed()
    private fun ipMin(): Int = columnWidths.ipMin()
    private fun savedHeaderTitle(k: Key): String = columnWidths.savedHeaderTitle(k)
    private fun title(k: Key): String = columnWidths.title(k)
    private fun savedSpeedHeaderNeed(k: Key): Int = columnWidths.savedSpeedHeaderNeed(k)
    private fun savedSpeedButtonNeed(k: Key): Int = columnWidths.savedSpeedButtonNeed(k)
    private fun width(k: Key): Int = columnWidths.width(k)

    // CUSTOM (CUSTOM.md #160): filter options use the same semantic mapping as saved-table cells.

    // CUSTOM (CUSTOM.md #164): expose the speed header index for its sort-only click handler.








    /** Draw the download fill inside the button's own rounded outline, without changing its measured size. */








    // Keep ordinary text cells to at most three lines. If a value needs more room,
    // widen its column; the complete grid scrolls when the page cannot fit all required widths.


    // [0] the IP column, then one per key. The narrowest widths that read well; when the page is wider than their sum
    // the rest is shared out (the columns with the longest text get the most), so the table fills the page exactly


    // Measure the longest explicit header line at the same text size, weight and horizontal padding as headCell.


    // Measure the dedicated button label, not iptest_btn_speed which may also be used by non-button text.


    // Use the two-line label only for Chinese; all other locales retain their existing translation.




    // CUSTOM.md #94: the longest line of the three-line time split in the 14 sp of the cell plus its padding. When it
    // wrapped ("447" and "ms" on two lines) the row became two or three lines taller than anything else in it.


    // Keep the public-facing IP and its operator on separate, unwrapped lines.


    // the longest IP in the 15 sp bold of the cell, so it is never cut






    // CUSTOM.md #162: saved speed columns fit their actual three-line header and two-line button,
    // without inheriting the wider live-test speed column's fixed width.






    // the colour of an average: under 150 ms green, under 300 orange, else red


    // CUSTOM.md #68: the colour of the jitter: under 30 ms green, under 100 orange, else red (a guess at what is steady
    // enough; the number itself is what to compare between addresses)


    // CUSTOM.md #68: three lines, one per part; "-" for a part that was not measured (a plain http address has no
    // handshake part), all "-" for an address that failed




    // CUSTOM.md #62: "🇺🇸 LAS（拉斯维加斯 Las Vegas）". The flag is the country of the data centre and the English city
    // is what the network list says; both are missing when that list was not fetched. The Chinese name is built in
    // (ColoNames); a code it does not have keeps the English city, and with neither the bare code stays.






    // CUSTOM.md #99: show a concise download failure reason in the speed cell; full detail remains in the live log.
    // CUSTOM.md #158: saved-IP download actions stay visible even when the original probe failed.













    // the flag, the code and its names on one line when the column is wide enough, else the names under the code
    // (never cut in the middle of the code)
    // CUSTOM.md #66: what the target site answered: "200", "403", "302 → host"; "-" when it gave no answer







    // CUSTOM.md #67: the second line: what the page itself says (moved by the page, or says the region is refused)











    // the ports that work in the normal colour, the ones that do not grey with a cross; a tap shows each one's result




    // "下载测速" of a row that works; "取消" on the row that is downloading; the others are grey while one is


    // the IP (a tap copies it, dashed line under it) and under it, small and grey, what the handshake showed




    // CUSTOM (CUSTOM.md #156 r329): keep saved-list failure rows compact; the original reason remains in onDetail.


    // the core's own wording carries local addresses and ports, so it is shown only when the cause is not recognised




    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun themeColor(attr: Int, fallback: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))

        try {
            return ta.getColor(0, fallback)
        } finally {
            ta.recycle()
        }
    }

    // a TextView with a dashed line under its first line, the "tap to copy" mark


    companion object {
        private const val MAX_LOG = 200
        private val HEADER_WHITESPACE_REGEX = Regex("""\s+""")
    }
}
