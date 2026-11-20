package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.CheckBox
import android.widget.FrameLayout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
    private val visibleSavedColumns: Set<String>? = null,
) {
    var onCopy: (String) -> Unit = {}
    var onSpeed: (String) -> Unit = {} // a tap on "下载测速" of a row
    var onSpeedCancel: () -> Unit = {} // a tap on "取消" of the row that is downloading
    var onDetail: (String, String) -> Unit = { _, _ -> } // a tap on the ports of a row: title, text

    // CUSTOM.md #68: JITTER (抖动) and PHASE (分段耗时) sit right after AVG
    private enum class Key { COLO, ASN, GEO, LAND, PORTS, LOSS, MIN, MAX, AVG, JITTER, PHASE, EXIT, EXIT_IP, PORT, TEST, SPEED, TARGET, TCOLO }

    private val savedTableKeys = listOf(
        Key.COLO, Key.ASN, Key.GEO, Key.EXIT, Key.PORTS, Key.LOSS, Key.MIN, Key.MAX, Key.AVG,
        Key.JITTER, Key.EXIT_IP, Key.TCOLO, Key.TARGET, Key.PHASE, Key.SPEED,
    )
    private val keys = if (savedTable) {
        // CUSTOM.md #162/#160: filter only the saved-table instance; the IP column remains fixed outside this list.
        savedTableKeys.filter { visibleSavedColumns == null || it.name in visibleSavedColumns }
    } else if (direct) {
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

    private val rowH = dp(48) // the least height of a row; a row with wrapped text is taller
    private val headH = dp(40)

    private var rows: List<IpTestRow> = emptyList()
    private var savedTableAvailableWidth: Int = 0
    private var dnsReport: DnsCheckReport? = null
    private var colos: Map<String, String> = emptyMap()
    private var coloCountries: Map<String, String> = emptyMap() // CUSTOM.md #62: data centre code -> country code
    private var speedIp: String? = null
    private var ownerLookupIp: String? = null
    private var testing = false

    private var infoText: CharSequence = ""
    private val logLines = ArrayList<String>()

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

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

    // CUSTOM.md #156 step 41: collapsible diagnostics below the unchanged IP table; expanded by default.
    private var diagnosticsExpanded = true
    private val diagnosticsBody = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply {
            setColor(colorSurface)
            cornerRadius = dp(6).toFloat()
        }
    }
    private var diagnosticsPlain = ""
    private val diagnosticsTitle = TextView(context).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(colorText)
        setPadding(0, dp(8), 0, dp(6))
        text = "${context.getString(R.string.iptest_diag_title)}  ▴"
        setOnClickListener {
            diagnosticsExpanded = !diagnosticsExpanded
            diagnosticsBody.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
            text = "${context.getString(R.string.iptest_diag_title)}  ${if (diagnosticsExpanded) "▴" else "▾"}"
        }
    }
    private val diagnosticsBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.VISIBLE
        addView(diagnosticsTitle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(diagnosticsBody, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

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
            if (r - l != oldR - oldL && r - l > 0 && rows.isNotEmpty()) tableBox.post { redraw() }
        }
        diagnosticsBody.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL && r - l > 0 && rows.isNotEmpty()) diagnosticsBody.post { updateDiagnostics() }
        }

        root.addView(info, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(noteToggle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(noteBody, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tableBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(hint, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(diagnosticsBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(logTitle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(logScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        redraw()
    }

    /** The lines above the table: [head] bold, [sub] grey, [warnings] orange (invalid words, skipped domains and cut entries). */
    fun setInfo(head: String, sub: String, warnings: List<String>) {
        val sb = SpannableStringBuilder()

        // CUSTOM.md #156 step 75: the first line of [sub] stays visible; the explanation after it goes into the collapsible block.
        val cut = sub.indexOf('\n')
        val shownSub = if (cut >= 0) sub.substring(0, cut) else sub
        val note = if (cut >= 0) sub.substring(cut + 1) else ""

        add(sb, head + "\n", bold = true)

        if (shownSub.isNotEmpty()) add(sb, shownSub + "\n", colorMuted)

        for (w in warnings) add(sb, w + "\n", colorWarn)

        // no trailing blank line: it made the gap above the note button too big
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)

        infoText = sb
        info.text = sb
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
        rows = sorted(list)
        redraw()
        updateDiagnostics()
    }

    fun setDnsReport(report: DnsCheckReport) {
        dnsReport = report
        diagnosticsBox.visibility = View.VISIBLE
        updateDiagnostics()
    }

    private fun updateDiagnostics() {
        diagnosticsBody.removeAllViews()
        val copied = StringBuilder()

        fun appendMarkdown(headers: List<String>, values: List<List<String>>) {
            copied.append("| ").append(headers.joinToString(" | ") { markdownCell(it) }).append(" |\n")
            copied.append("| ").append(headers.joinToString(" | ") { "---" }).append(" |\n")
            values.forEach { row ->
                copied.append("| ").append(row.joinToString(" | ") { markdownCell(it) }).append(" |\n")
            }
            copied.append('\n')
        }

        if (rows.isNotEmpty() && (target || !direct)) {
            // Only show fields that are not already present in the original IP table.
            // IP is retained solely as the row identifier for matching each diagnostic record.
            val headers = if (target) {
                listOf(
                    context.getString(R.string.iptest_diag_ip), context.getString(R.string.iptest_diag_tls), context.getString(R.string.iptest_diag_alpn),
                    context.getString(R.string.iptest_diag_cert), context.getString(R.string.iptest_diag_cert_ip),
                    context.getString(R.string.iptest_diag_cf_ray), context.getString(R.string.iptest_diag_content_type),
                    context.getString(R.string.iptest_diag_location), context.getString(R.string.iptest_diag_retry_after),
                    context.getString(R.string.iptest_diag_cf_mitigated), context.getString(R.string.iptest_diag_page_hint),
                )
            } else {
                listOf(context.getString(R.string.iptest_diag_ip), context.getString(R.string.iptest_diag_tls), context.getString(R.string.iptest_diag_alpn))
            }

            val values = rows.map { row ->
                val failed = !row.ok || (target && row.targetStatus == 0)
                val dash = context.getString(R.string.iptest_diag_dash)
                val none = context.getString(R.string.iptest_diag_none)
                val missing = context.getString(R.string.iptest_diag_not_obtained)

                fun required(value: String): String = when {
                    failed -> dash
                    value.isNotBlank() -> value
                    else -> missing
                }
                fun optional(value: String): String = when {
                    failed -> dash
                    value.isNotBlank() -> value
                    else -> none
                }

                if (target) {
                    listOf(
                        row.ip,
                        required(row.targetTls),
                        required(
                            row.targetAlpn.ifBlank {
                                when (row.targetHttpProtoMajor) {
                                    2 -> "h2"
                                    1 -> "http/1.1"
                                    else -> ""
                                }
                            },
                        ),
                        context.getString(
                            R.string.iptest_diag_cert_value,
                            required(row.targetCertDnsNames),
                            required(row.targetCertIssuer),
                            required(row.targetCertNotAfter),
                        ),
                        optional(row.targetCertIpAddresses),
                        required(row.targetCfRay),
                        required(row.targetContentType),
                        optional(row.targetNote),
                        optional(row.targetRetryAfter),
                        optional(row.targetCfMitigated),
                        optional(pageHintText(row)),
                    )
                } else {
                    listOf(
                        row.ip,
                        required(row.tls),
                        required(row.alpn),
                    )
                }
            }

            copied.append(context.getString(R.string.iptest_diag_ip_title) + "\n")
            // Export uses the original full values. Only the on-screen certificate text is shortened.
            appendMarkdown(headers, values)
            if (target) {
                val displayValues = values.mapIndexed { index, original ->
                    val row = rows[index]
                    val failed = !row.ok || row.targetStatus == 0
                    val dash = context.getString(R.string.iptest_diag_dash)
                    val missing = context.getString(R.string.iptest_diag_not_obtained)
                    val domain = if (failed) dash else row.targetCertDnsNames.ifBlank { missing }
                    val issuer = if (failed) dash else certificateIssuerName(row.targetCertIssuer.ifBlank { missing })
                    val expiry = if (failed) dash else certificateDate(row.targetCertNotAfter.ifBlank { missing })
                    original.toMutableList().apply {
                        this[3] = context.getString(R.string.iptest_diag_cert_value, domain, issuer, expiry)
                    }
                }
                addDetailedDiagnosticsTable(
                    context.getString(R.string.iptest_diag_ip_title),
                    headers,
                    displayValues,
                    values,
                )
            } else {
                // The non-target TLS / protocol mini-table keeps its existing layout and sizing.
                addDiagnosticsTable(context.getString(R.string.iptest_diag_ip_title), headers, values)
            }
        }

        // CUSTOM.md #77: extension diagnostics stay below the existing IP table; never add more main-table columns.
        if (direct && rows.any { it.ws.tested || it.h3.tested || it.mtu.tested }) {
            val headers = listOf(
                context.getString(R.string.iptest_ext_ip),
                context.getString(R.string.iptest_ext_ws),
                context.getString(R.string.iptest_ext_h3),
                context.getString(R.string.iptest_ext_mtu),
            )
            fun resultText(result: IpTestExtResult, mtu: Boolean = false): String {
                if (!result.tested) return context.getString(R.string.iptest_diag_dash)
                if (mtu) {
                    val parts = ArrayList<String>()
                    if (result.tcpMss > 0 && result.tcpMtu > 0) {
                        parts += context.getString(R.string.iptest_ext_tcp_mtu, result.tcpMss, result.tcpMtu)
                    } else {
                        parts += context.getString(R.string.iptest_ext_tcp_none)
                    }
                    if (result.udpMtu > 0) {
                        val format = if (result.detail.contains("UDP upper bound reached")) {
                            R.string.iptest_ext_udp_mtu_at_least
                        } else {
                            R.string.iptest_ext_udp_mtu
                        }
                        parts += context.getString(format, result.udpMtu)
                    } else if (result.udpNoResponse) {
                        parts += context.getString(R.string.iptest_ext_udp_none)
                    } else {
                        parts += context.getString(R.string.iptest_ext_udp_dash)
                    }
                    if (result.reason.isNotBlank()) parts += result.reason
                    return parts.joinToString(" · ").ifBlank { context.getString(R.string.iptest_ext_failed) }
                }
                if (result.reason.isNotBlank()) return result.reason
                if (result.status > 0) {
                    val status = result.status.toString()
                    val statusText = if (result.ms > 0) context.getString(R.string.iptest_ext_status_ms, status, result.ms) else status
                    val detail = result.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    return statusText + detail
                }
                if (result.ms > 0) return context.getString(R.string.iptest_ext_ms, result.ms)
                return result.detail.ifBlank { context.getString(if (result.ok) R.string.iptest_ext_ok else R.string.iptest_ext_failed) }
            }
            val values = rows.map { row ->
                listOf(row.ip, resultText(row.ws), resultText(row.h3), resultText(row.mtu, mtu = true))
            }
            val title = context.getString(R.string.iptest_ext_title)
            copied.append(title).append('\n')
            appendMarkdown(headers, values)
            // CUSTOM.md #156 step 83: the shown table is a compact full-width one (one short line per cell, tap for the
            // full text); the copied text above keeps the full values.
            val extRows = rows.mapIndexed { index, row ->
                ExtRow(
                    row.ip,
                    listOf(
                        ExtCell(extWsSummary(row.ws), "${headers[1]} · ${row.ip}", values[index][1]),
                        ExtCell(extH3Summary(row.h3), "${headers[2]} · ${row.ip}", values[index][2]),
                        ExtCell(
                            extMtuSummary(row.ip, row.mtu),
                            "${headers[3]} · ${row.ip}",
                            listOf(values[index][3], row.mtu.detail).filter { it.isNotBlank() }.joinToString("\n\n"),
                        ),
                    ),
                )
            }
            addExtensionTable(title, headers, extRows)
        }

        dnsReport?.let { report ->
            val dnsRows = ArrayList<List<String>>()
            for (server in report.servers) {
                dnsRows += listOf(
                    server.address.ifBlank { context.getString(R.string.iptest_diag_not_obtained) },
                    if (server.ok && server.ms > 0) "${server.ms} ms" else context.getString(R.string.iptest_diag_not_obtained),
                )
            }
            if (report.core.asked) {
                dnsRows += listOf(context.getString(R.string.iptest_diag_core_dns), if (report.core.ok && report.core.ms > 0) "${report.core.ms} ms" else context.getString(R.string.iptest_diag_not_obtained))
            }
            report.kernel?.let { kernel ->
                dnsRows += listOf(context.getString(R.string.iptest_diag_kernel_dns), if (kernel.ok && kernel.ms > 0) "${kernel.ms} ms" else context.getString(R.string.iptest_diag_not_obtained))
            }
            if (dnsRows.isEmpty()) dnsRows += listOf(context.getString(R.string.iptest_diag_no_results), context.getString(R.string.iptest_diag_not_obtained))

            val dnsTitle = context.getString(R.string.iptest_diag_dns_title, report.domain)
            copied.append(dnsTitle).append('\n')
            appendMarkdown(listOf(context.getString(R.string.iptest_diag_dns_item), context.getString(R.string.iptest_diag_dns_time)), dnsRows)
            addDiagnosticsTable(dnsTitle, listOf(context.getString(R.string.iptest_diag_dns_item), context.getString(R.string.iptest_diag_dns_time)), dnsRows, 150, 110)
        }

        diagnosticsPlain = copied.toString().trim()
        if (diagnosticsBody.childCount == 0) {
            val empty = TextView(context).apply {
                text = context.getString(R.string.iptest_diag_empty)
                textSize = 13f
                setTextColor(colorMuted)
            }
            diagnosticsBody.addView(empty, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // Target diagnostics use one table. The IP column is fixed at the left; the remaining ten columns
    // scroll as one grid. Column widths follow the longest value and the longest unbroken header word.
    private fun addDetailedDiagnosticsTable(
        title: String,
        headers: List<String>,
        values: List<List<String>>,
        copyValues: List<List<String>>,
    ) {
        val heading = TextView(context).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            setPadding(0, dp(10), 0, dp(6))
        }
        diagnosticsBody.addView(heading, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val availableWidth = (diagnosticsBody.width - diagnosticsBody.paddingLeft - diagnosticsBody.paddingRight)
            .takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels - dp(48))
        val widths = detailedColumnWidths(headers, values, availableWidth)
        val firstWidth = widths.first()
        val gridWidth = widths.drop(1).sum()

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val leftHeader = detailedHeaderCell(headers.first())
        val rightHeaders = headers.drop(1).map { detailedHeaderCell(it) }
        val headerHeight = maxOf(
            dp(42),
            detailedHeight(leftHeader, firstWidth),
            rightHeaders.mapIndexed { index, cell -> detailedHeight(cell, widths[index + 1]) }.maxOrNull() ?: 0,
        )

        val leftHeaderBg = FrameLayout(context).apply {
            setBackgroundColor(colorSurface)
            addView(leftHeader, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        left.addView(leftHeaderBg, lp(firstWidth, headerHeight))

        val headRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorSurface)
        }
        rightHeaders.forEachIndexed { index, cell ->
            headRow.addView(cell, lp(widths[index + 1], headerHeight))
        }
        grid.addView(headRow, lp(gridWidth, headerHeight))

        values.forEachIndexed { rowIndex, row ->
            val stripe = if (rowIndex % 2 == 1) colorStripe else Color.TRANSPARENT
            val fullRow = copyValues.getOrNull(rowIndex).orEmpty()
            val first = detailedValueCell(row.firstOrNull().orEmpty(), fullRow.firstOrNull().orEmpty(), true, true)
            val cells = row.drop(1).mapIndexed { index, value ->
                val columnIndex = index + 1
                detailedValueCell(value, fullRow.getOrNull(columnIndex).orEmpty(), false, columnIndex == 5)
            }
            val height = maxOf(
                dp(42),
                detailedHeight(first, firstWidth),
                cells.mapIndexed { index, cell -> detailedHeight(cell, widths[index + 1]) }.maxOrNull() ?: 0,
            )

            // Fixed and scrolling portions use the same stripe and the same measured row height.
            val leftRow = FrameLayout(context).apply {
                setBackgroundColor(stripe)
                addView(first, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
            left.addView(leftRow, lp(firstWidth, height))

            val rightRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(stripe)
            }
            cells.forEachIndexed { index, cell ->
                rightRow.addView(cell, lp(widths[index + 1], height))
            }
            grid.addView(rightRow, lp(gridWidth, height))
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(grid, ViewGroup.LayoutParams(gridWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val table = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, lp(firstWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        diagnosticsBody.addView(table, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun detailedColumnWidths(headers: List<String>, values: List<List<String>>, availableWidth: Int): IntArray {
        val maxRegularWidth = (availableWidth / 2).coerceAtLeast(dp(48))
        val normalPaint = TextView(context).apply { textSize = 14f }.paint
        val boldPaint = TextView(context).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }.paint

        return IntArray(headers.size) { column ->
            val headerWordWidth = headers[column].split(Regex("""\s+"""))
                .maxOfOrNull { boldPaint.measureText(it) } ?: 0f
            val valuePaint = if (column == 0) boldPaint else normalPaint
            val valueWidth = values.maxOfOrNull { row ->
                valuePaint.measureText(row.getOrNull(column).orEmpty().replace('\n', ' '))
            } ?: 0f
            val natural = maxOf(headerWordWidth, valueWidth).toInt() + dp(16)

            when (column) {
                0, 5 -> natural.coerceAtMost(availableWidth.coerceAtLeast(dp(48)))
                else -> natural.coerceAtLeast(dp(44)).coerceAtMost(maxRegularWidth)
            }
        }
    }

    private fun detailedHeaderCell(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(colorMuted)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun detailedValueCell(text: String, copyText: String, bold: Boolean, noWrap: Boolean): TextView = TextView(context).apply {
        this.text = text.ifBlank { context.getString(R.string.iptest_diag_not_obtained) }
        textSize = 14f
        maxLines = if (noWrap) 1 else 3
        if (noWrap) isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.TOP or Gravity.START
        setTextColor(colorText)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(6), dp(5), dp(6), dp(5))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { onCopy(copyText.ifBlank { this.text.toString() }) }
    }

    private fun detailedHeight(view: View, width: Int): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight
    }

    private fun certificateIssuerName(value: String): String {
        val parts = value.split(',').map { it.trim() }
        val commonName = parts.firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substringAfter('=')
        val organization = parts.firstOrNull { it.startsWith("O=", ignoreCase = true) }?.substringAfter('=')
        return when {
            !commonName.isNullOrBlank() && !organization.isNullOrBlank() -> "$commonName ($organization)"
            !commonName.isNullOrBlank() -> commonName
            !organization.isNullOrBlank() -> organization
            else -> value
        }
    }

    private fun certificateDate(value: String): String {
        return Regex("""\d{4}-\d{2}-\d{2}""").find(value)?.value ?: value
    }

    // The diagnostics table follows the IP table: one IP per vertical row; diagnostic fields are horizontal columns.
    // The IP column stays fixed while the remaining columns scroll horizontally.
    private fun addDiagnosticsTable(
        title: String,
        headers: List<String>,
        values: List<List<String>>,
        firstWidthDp: Int = 140,
        otherWidthDp: Int = 118,
    ) {
        val heading = TextView(context).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            setPadding(0, dp(10), 0, dp(6))
        }
        diagnosticsBody.addView(heading, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val firstWidth = dp(firstWidthDp)
        val otherWidth = dp(otherWidthDp)
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val gridWidth = otherWidth * (headers.size - 1)

        left.addView(diagnosticCell(headers.first(), colorSurface, true), lp(firstWidth, dp(42)))

        val headRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorSurface)
        }
        headers.drop(1).forEach { header ->
            headRow.addView(diagnosticCell(header, colorSurface, true), lp(otherWidth, dp(42)))
        }
        grid.addView(headRow, lp(gridWidth, dp(42)))

        values.forEachIndexed { index, row ->
            val stripe = if (index % 2 == 1) colorStripe else Color.TRANSPARENT
            val first = diagnosticCell(row.firstOrNull().orEmpty(), stripe, true)
            val cells = row.drop(1).map { diagnosticCell(it, stripe, false) }
            val leftHeight = diagnosticHeight(first, firstWidth)
            val rightHeight = cells.maxOfOrNull { diagnosticHeight(it, otherWidth) } ?: dp(42)
            val height = maxOf(dp(42), leftHeight, rightHeight)

            left.addView(first, lp(firstWidth, height))
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(stripe)
            }
            cells.forEach { line.addView(it, lp(otherWidth, height)) }
            grid.addView(line, lp(gridWidth, height))
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(grid, ViewGroup.LayoutParams(gridWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val table = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, lp(firstWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        diagnosticsBody.addView(table, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private class ExtCell(val text: CharSequence, val title: String, val detail: String)

    private class ExtRow(val ip: String, val cells: List<ExtCell>)

    /** A reason cut to something that fits a cell: the timeouts get a word of their own. */
    private fun extShortReason(reason: String): String {
        val lower = reason.lowercase()
        if (lower.contains("deadline exceeded") || lower.contains("timeout") || lower.contains("timed out")) {
            return context.getString(R.string.iptest_ext_timeout)
        }
        return if (reason.length > 16) reason.take(16) + "…" else reason
    }

    private fun extText(text: String, color: Int): CharSequence {
        val sb = SpannableStringBuilder()
        add(sb, text, color)
        return sb
    }

    private fun extFailureText(result: IpTestExtResult): CharSequence {
        val reason = result.reason.ifBlank { context.getString(R.string.iptest_ext_failed) }
        return extText(extShortReason(reason), colorBad)
    }

    private fun extWsSummary(result: IpTestExtResult): CharSequence {
        if (!result.tested) return extText(context.getString(R.string.iptest_diag_dash), colorMuted)
        if (result.status == 101 || (result.ok && result.status == 0)) {
            return extText(context.getString(R.string.iptest_ext_ws_upgraded), colorGood)
        }
        if (result.status in 200..399) {
            return extText(context.getString(R.string.iptest_ext_no_upgrade, result.status), colorMuted)
        }
        if (result.status > 0) return extText(result.status.toString(), colorBad)
        return extFailureText(result)
    }

    private fun extH3Summary(result: IpTestExtResult): CharSequence {
        if (!result.tested) return extText(context.getString(R.string.iptest_diag_dash), colorMuted)
        if (result.status > 0) {
            val text = if (result.ms > 0) context.getString(R.string.iptest_ext_status_ms, result.status.toString(), result.ms) else result.status.toString()
            return extText(text, if (result.status in 200..399) colorGood else colorBad)
        }
        return extFailureText(result)
    }

    /** Two short lines: the TCP and the UDP path MTU (the UDP probe measures the payload, so the headers are added back). */
    private fun extMtuSummary(ip: String, result: IpTestExtResult): CharSequence {
        if (!result.tested) return extText(context.getString(R.string.iptest_diag_dash), colorMuted)

        val sb = SpannableStringBuilder()

        if (result.tcpMtu > 0) {
            add(sb, context.getString(R.string.iptest_ext_tcp_short, result.tcpMtu), colorGood)
        } else {
            add(sb, context.getString(R.string.iptest_ext_tcp_none), colorMuted)
        }

        sb.append('\n')

        val overhead = if (ip.contains(':')) 48 else 28

        when {
            result.udpMtu > 0 -> {
                val format = if (result.detail.contains("UDP upper bound reached")) R.string.iptest_ext_udp_mtu_at_least else R.string.iptest_ext_udp_mtu
                add(sb, context.getString(format, result.udpMtu + overhead), colorGood)
            }
            result.udpNoResponse -> add(sb, context.getString(R.string.iptest_ext_udp_none), colorMuted)
            result.reason.isNotBlank() -> add(sb, extShortReason(result.reason), colorBad)
            else -> add(sb, context.getString(R.string.iptest_ext_udp_dash), colorMuted)
        }

        return sb
    }

    private fun addExtensionTable(title: String, headers: List<String>, rows: List<ExtRow>) {
        val heading = TextView(context).apply {
            val sb = SpannableStringBuilder()
            add(sb, title, bold = true)
            add(sb, "  " + context.getString(R.string.iptest_ext_tap_hint), colorMuted)
            sb.setSpan(android.text.style.AbsoluteSizeSpan(12, true), title.length, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
            textSize = 14f
            setTextColor(colorText)
            setPadding(0, dp(10), 0, dp(6))
        }
        diagnosticsBody.addView(heading, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val firstWidth = dp(124)

        fun cell(text: CharSequence, bold: Boolean, textColor: Int, size: Float): TextView = TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(textColor)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(6), dp(5), dp(6), dp(5))
            minHeight = dp(42)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        fun row(background: Int, cells: List<TextView>): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(background)
            cells.forEachIndexed { i, view ->
                if (i == 0) {
                    addView(view, LinearLayout.LayoutParams(firstWidth, ViewGroup.LayoutParams.MATCH_PARENT))
                } else {
                    addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                }
            }
        }

        val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        table.addView(
            row(colorSurface, headers.map { cell(it, true, colorText, 14f) }),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        rows.forEachIndexed { index, r ->
            val stripe = if (index % 2 == 1) colorStripe else Color.TRANSPARENT
            val views = ArrayList<TextView>()

            views += cell(r.ip, true, colorText, 14f)

            for (c in r.cells) {
                views += cell(c.text, false, colorText, 13f).apply {
                    if (c.detail.isNotBlank()) setOnClickListener { onDetail(c.title, c.detail) }
                }
            }

            table.addView(row(stripe, views), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        diagnosticsBody.addView(table, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun diagnosticCell(text: String, backgroundColor: Int, bold: Boolean): TextView {
        return TextView(context).apply {
            this.text = text.ifBlank { context.getString(R.string.iptest_diag_not_obtained) }
            textSize = 14f
            setTextColor(colorText)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(backgroundColor)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun diagnosticHeight(view: View, width: Int): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight
    }

    /** [testing]: the round is running (no download then); [ip]: the row whose download is running. */
    fun setBusy(testing: Boolean, ip: String?) {
        this.testing = testing
        speedIp = ip
        redraw()
    }

    fun setOwnerLookup(ip: String?) {
        ownerLookupIp = ip
        redraw()
    }

    /** Progress counts completed IP probes, including failed results; a cancelled in-flight probe is not counted. */
    fun setProgress(total: Int, tested: Int) {
        totalIps = total.coerceAtLeast(0)
        testedIps = tested.coerceIn(0, totalIps)
        val title = SpannableStringBuilder(context.getString(R.string.iptest_log_title))
        if (totalIps > 0) {
            // CUSTOM (CUSTOM.md #156 step 23): highlight the total and completed counts separately.
            val progress = context.getString(R.string.iptest_log_progress, totalIps, testedIps)
            val start = title.length
            title.append(progress)
            val testedAt = progress.indexOf("，已测")
            if (testedAt > 0) {
                title.setSpan(
                    ForegroundColorSpan(if (night) Color.parseColor("#90CAF9") else Color.parseColor("#64B5F6")),
                    start,
                    start + testedAt,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                title.setSpan(
                    ForegroundColorSpan(if (night) Color.parseColor("#64B5F6") else Color.parseColor("#2196F3")),
                    start + testedAt,
                    title.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                title.setSpan(ForegroundColorSpan(colorMuted), start, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        logTitle.text = title
    }

    /** One line of the live status, `[HH:MM:SS] text`; at most 200 lines are kept. */
    fun log(text: String) {
        logLines += "[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "] " + text

        while (logLines.size > MAX_LOG) logLines.removeAt(0)

        logView.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun savedLogs(): List<String> = logLines.toList()

    fun setLogs(lines: List<String>) {
        logLines.clear()
        logLines.addAll(lines.takeLast(MAX_LOG))
        logView.text = logLines.joinToString("\n")
    }

    /**
     * The table and the log as text (what the copy button at the top of the page gets).
     * Use a Markdown table so the columns remain readable when pasted into a chat or issue.
     */
    fun plain(): String {
        val sb = StringBuilder()

        sb.append(infoText).append("\n").append(if (noteBody.text.isNotEmpty()) noteBody.text.toString() + "\n" else "").append("\n\n")
        sb.append("| ").append(markdownCell(context.getString(R.string.iptest_col_ip)))
        for (k in keys) sb.append(" | ").append(markdownCell(title(k)))
        sb.append(" |\n")

        sb.append("| --- ")
        repeat(keys.size) { sb.append("| --- ") }
        sb.append("|\n")

        for (r in rows) {
            sb.append("| ").append(markdownCell(r.ip))
            for (k in keys) {
                // CUSTOM.md #68: keep the three phase timings on one line in the copied table.
                val value = plainOf(k, r).let { if (k == Key.PHASE) it.replace('\n', ' ') else it }
                sb.append(" | ").append(markdownCell(value))
            }
            sb.append(" |\n")
        }

        sb.append("\n").append(context.getString(R.string.iptest_log_title))
        if (totalIps > 0) sb.append(context.getString(R.string.iptest_log_progress, totalIps, testedIps))
        if (diagnosticsPlain.isNotEmpty()) {
            sb.append("\n\n").append(context.getString(R.string.iptest_diag_title)).append("\n").append(diagnosticsPlain)
        }

        sb.append('\n').append(logLines.joinToString("\n"))

        return sb.toString()
    }

    // Escape Markdown table delimiters and line breaks without changing the displayed values.
    private fun markdownCell(value: String): String =
        value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    // quickest average first, the ones that failed last (in the order they were tested)
    private fun sorted(list: List<IpTestRow>): List<IpTestRow> {
        return list.sortedWith(
            compareBy<IpTestRow> { if (it.ok) 0 else 1 }.thenBy { if (it.ok) it.avg else 0 },
        )
    }

    private fun redraw() {
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
            val stripe = if (i % 2 == 1) colorStripe else Color.TRANSPARENT

            val ipView = ipCell(r)
            val cells = keys.mapIndexed { n, k -> cellOf(k, r, w[n + 1]) }

            // one height for the whole row, so the fixed IP column and the sideways part stay level
            // CUSTOM.md #55: a little room more than measured, so the last line of a cell (the last port) is not cut
            val h = maxOf(rowH, heightOf(ipView, w[0]), cells.mapIndexed { n, v -> heightOf(v, w[n + 1]) }.maxOrNull() ?: 0) + dp(4)

            left.addView(ipView.apply { setBackgroundColor(stripe) }, lp(w[0], h))

            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(stripe)
            }

            cells.forEachIndexed { n, v -> line.addView(v, lp(w[n + 1], h)) }

            grid.addView(line, lp(gridW, h))
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(grid, ViewGroup.LayoutParams(gridW, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        tableBox.addView(left, LinearLayout.LayoutParams(w[0], ViewGroup.LayoutParams.WRAP_CONTENT))
        tableBox.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }


    // CUSTOM.md #162: saved-IP rows use the live table's header, cell, IP and width factories.
    internal fun savedTableWidths(availableWidth: Int, savedRows: List<IpTestRow>, cityNames: Map<String, String>, countryCodes: Map<String, String>): IntArray {
        rows = savedRows
        colos = cityNames
        coloCountries = countryCodes
        savedTableAvailableWidth = availableWidth
        return widths()
    }

    // CUSTOM (CUSTOM.md #160): filter options use the same semantic mapping as saved-table cells.
    private fun isSavedTableFilterable(key: Key): Boolean = key in setOf(Key.COLO, Key.ASN, Key.GEO, Key.EXIT, Key.PORTS, Key.EXIT_IP, Key.TCOLO, Key.TARGET)

    // CUSTOM (CUSTOM.md #164): expose the speed header index for its sort-only click handler.
    internal fun savedSpeedColumnIndex(): Int = keys.indexOf(Key.SPEED)

    internal fun savedTableColumnOptions(): List<Pair<String, String>> =
        savedTableKeys.map { it.name to title(it) }

    internal fun savedTableColumnName(columnIndex: Int): String = keys.getOrNull(columnIndex)?.name.orEmpty()

    internal fun savedTableFilterOptions(entries: List<SavedIpEntry>, columnName: String): List<String> =
        entries.asSequence().map { savedTableFilterValue(it, columnName) }.filter { it.isNotBlank() && it != "-" }.distinct().sorted().toList()

    internal fun savedTableFilterValue(entry: SavedIpEntry, columnName: String): String {
        val key = keys.firstOrNull { it.name == columnName } ?: return ""
        if (!isSavedTableFilterable(key)) return ""
        val row = entry.result ?: return ""
        val mapped = when (key) {
            Key.COLO -> Key.COLO
            Key.ASN -> Key.ASN
            Key.GEO -> Key.GEO
            Key.EXIT -> if (entry.testDirect) Key.LAND else Key.EXIT
            Key.PORTS -> if (entry.testDirect) Key.PORTS else Key.PORT
            Key.EXIT_IP -> if (entry.testDirect) null else Key.EXIT_IP
            Key.TCOLO -> if (entry.testTarget) Key.TCOLO else null
            Key.TARGET -> if (entry.testTarget) Key.TARGET else null
            else -> null
        } ?: return ""
        if (key == Key.EXIT && row.country.isEmpty()) return ""
        return when (mapped) {
            Key.COLO -> coloLabel(row.colo).takeIf { it != "-" }.orEmpty()
            Key.ASN -> row.asn
            Key.GEO -> if (row.ok) CountryName.label(row.geo).takeIf { it != "-" }.orEmpty() else ""
            Key.LAND -> if (row.ok) CountryName.label(row.country).takeIf { it != "-" }.orEmpty() else ""
            Key.EXIT -> if (row.ok) CountryName.label(row.country).takeIf { it != "-" && it != "？" }.orEmpty() else ""
            Key.PORT -> row.port
            Key.PORTS -> portsPlain(row).takeIf { it != "-" }.orEmpty()
            Key.EXIT_IP -> if (row.ok && row.exitIp.isNotBlank()) row.exitIp + "\n" + row.exitAsn.ifEmpty { "-" } else ""
            Key.TCOLO -> coloLabel(row.targetColo).takeIf { it != "-" }.orEmpty()
            Key.TARGET -> (if (!row.ok && row.reason.length > 40) handshakeFailureText(row.reason.take(40)) + "…" else targetText(row)).takeIf { it != "-" }.orEmpty()
            else -> ""
        }.trim()
    }

    private fun savedHeaderCell(title: String, width: Int, selected: Set<String>, summaryOverride: String? = null, onClick: (View) -> Unit): View {
        val titleView = TextView(context).apply {
            text = title; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorMuted); setPadding(dp(4), dp(4), dp(4), dp(1))
        }
        val selectedView = TextView(context).apply {
            // CUSTOM (CUSTOM.md #160): a readable one-line summary; show up to two selected values.
            text = summaryOverride ?: savedIpFilterSummary(selected)
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(colorLink); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; setPadding(dp(3), 0, dp(3), dp(3))
            visibility = if (summaryOverride.isNullOrEmpty() && selected.isEmpty()) View.GONE else View.VISIBLE
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val selectable = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true) && selectable.resourceId != 0) {
                setBackgroundResource(selectable.resourceId)
            }
            addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(selectedView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tag = selectedView
            isClickable = true; minimumWidth = width; setOnClickListener { if (isEnabled) onClick(this) }
        }
    }
    internal fun savedTableView(
        entries: List<SavedIpEntry>,
        widths: IntArray,
        scrollX: Int,
        onScroll: (Int) -> Unit,
        isSelectionMode: () -> Boolean,
        isSelected: (String) -> Boolean,
        onCopy: (String) -> Unit,
        onDetail: (String, String) -> Unit,
        onLongPress: (String) -> Unit,
        selectedFilters: Map<String, Set<String>> = emptyMap(),
        speedSortLabel: String = "",
        onHeaderClick: (Int, View) -> Unit = { _, _ -> },
        hostsIps: Set<String> = emptySet(),
    ): View {
        val gridWidth = widths.drop(1).sum()
        val selectionMode = isSelectionMode()
        val checkboxWidth = if (selectionMode) dp(40) else 0
        val ipColumnWidth = widths[0] + checkboxWidth
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val leftHeader = headCell(context.getString(R.string.iptest_col_ip), ipColumnWidth)
        val headers = keys.mapIndexed { index, key ->
            savedHeaderCell(
                title(key),
                widths[index + 1],
                selectedFilters[key.name].orEmpty(),
                if (key == Key.SPEED) speedSortLabel else null,
            ) { anchor ->
                if (isSavedTableFilterable(key) || key == Key.SPEED) onHeaderClick(index, anchor)
            }.apply { isEnabled = isSavedTableFilterable(key) || key == Key.SPEED }
        }
        val headerHeight = maxOf(
            headH,
            heightOf(leftHeader, ipColumnWidth),
            headers.mapIndexed { index, cell -> heightOf(cell, widths[index + 1]) }.maxOrNull() ?: 0,
        ) + dp(6)
        left.addView(leftHeader, lp(ipColumnWidth, headerHeight))
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colorSurface)
        }
        headers.forEachIndexed { index, cell ->
            cell.setBackgroundColor(Color.TRANSPARENT)
            headerRow.addView(cell, lp(widths[index + 1], headerHeight))
        }
        grid.addView(headerRow, lp(gridWidth, headerHeight))

        val now = System.currentTimeMillis()
        entries.forEachIndexed { rowIndex, entry ->
            val result = entry.result
            val row = result ?: IpTestRow(ip = entry.address, ok = true)
            val renderer = IpTestView(context, entry.testDirect, entry.testTarget)
            renderer.colos = colos
            renderer.coloCountries = coloCountries
            renderer.onCopy = { address ->
                if (isSelectionMode()) onLongPress(address) else onCopy(address)
            }
            renderer.onDetail = onDetail
            val ipView = renderer.ipCell(row)
            // CUSTOM (CUSTOM.md #156): how long ago this IP was tested, right under the address.
            val timeText = SavedIpTimeText.text(context, entry.lastTestedAt, now)
            if (timeText.isNotEmpty() || entry.failStreak >= 3 || entry.address in hostsIps) {
                val label = SpannableStringBuilder(timeText)
                if (entry.failStreak >= 3) {
                    if (label.isNotEmpty()) label.append("　")
                    val start = label.length
                    label.append("连续不通 ${entry.failStreak} 次")
                    label.setSpan(
                        ForegroundColorSpan(colorBad),
                        start,
                        label.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (entry.address in hostsIps) {
                    if (label.isNotEmpty()) label.append("　")
                    val start = label.length
                    label.append("已在 hosts")
                    label.setSpan(
                        ForegroundColorSpan(colorLink),
                        start,
                        label.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                (ipView as? LinearLayout)?.addView(
                    TextView(context).apply {
                        text = label; textSize = 12f; maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(if (now - entry.lastTestedAt > 7L * 24L * 60L * 60L * 1000L) colorWarn else colorMuted)
                    },
                    1,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            val stripe = if (rowIndex % 2 == 1) colorStripe else Color.TRANSPARENT
            val selected = isSelected(entry.address)
            val rowBackground = if (selected) Color.argb(
                36, Color.red(colorLink), Color.green(colorLink), Color.blue(colorLink),
            ) else stripe
            val leftRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(rowBackground)
                if (selectionMode) {
                    addView(CheckBox(context).apply {
                        isChecked = selected
                        contentDescription = entry.address
                        setOnClickListener {
                            onLongPress(entry.address)
                            post { isChecked = isSelected(entry.address) }
                        }
                    }, LinearLayout.LayoutParams(checkboxWidth, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                addView(ipView, LinearLayout.LayoutParams(widths[0], ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val cells = keys.mapIndexed { index, key ->
                val directMode = entry.testDirect
                val targetMode = entry.testTarget
                val mapped = when (key) {
                    Key.COLO -> Key.COLO
                    Key.ASN -> Key.ASN
                    Key.GEO -> Key.GEO
                    Key.EXIT -> if (directMode) Key.LAND else Key.EXIT
                    Key.PORTS -> if (directMode) Key.PORTS else Key.PORT
                    Key.LOSS -> Key.LOSS
                    Key.MIN -> Key.MIN
                    Key.MAX -> Key.MAX
                    Key.AVG -> Key.AVG
                    Key.JITTER -> Key.JITTER
                    Key.EXIT_IP -> if (directMode) null else Key.EXIT_IP
                    Key.TCOLO -> if (targetMode) Key.TCOLO else null
                    Key.TARGET -> if (targetMode) Key.TARGET else null
                    Key.PHASE -> Key.PHASE
                    Key.SPEED -> Key.SPEED
                    else -> null
                }
                val missingExit = key == Key.EXIT && row.country.isEmpty()
                when {
                    result == null || mapped == null || missingExit -> textCell("-", colorMuted)
                    mapped == Key.PHASE -> textCell(renderer.phaseText(row, directMode), colorText)
                    mapped == Key.PORT -> textCell(row.port.ifEmpty { "-" }, if (row.port.isEmpty()) colorMuted else colorText)
                    mapped == Key.LOSS && row.sent <= 0 -> textCell("-", colorMuted)
                    mapped in listOf(Key.MIN, Key.MAX, Key.AVG) && when (mapped) {
                        Key.MIN -> row.min <= 0
                        Key.MAX -> row.max <= 0
                        else -> row.avg <= 0
                    } -> textCell("-", colorMuted)
                    mapped == Key.JITTER && row.sent <= 0 -> textCell("-", colorMuted)
                    else -> renderer.cellOf(mapped, row, widths[index + 1])
                }
            }
            val height = maxOf(
                rowH,
                heightOf(ipView, widths[0]),
                cells.mapIndexed { index, cell -> heightOf(cell, widths[index + 1]) }.maxOrNull() ?: 0,
            ) + dp(4)
            left.addView(leftRow, lp(ipColumnWidth, height))
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(rowBackground)
            }
            cells.forEachIndexed { index, cell -> line.addView(cell, lp(widths[index + 1], height)) }
            grid.addView(line, lp(gridWidth, height))

            // CUSTOM.md #163: while selecting, every part of the row toggles selection; replace cell
            // click handlers so a tap cannot open a port / detail dialog or copy the IP.
            if (selectionMode) {
                fun attachSelectionClick(view: View) {
                    if (view !is CheckBox) {
                        view.setOnClickListener { onLongPress(entry.address) }
                    }
                    if (view is ViewGroup) {
                        for (childIndex in 0 until view.childCount) attachSelectionClick(view.getChildAt(childIndex))
                    }
                }
                attachSelectionClick(leftRow)
                attachSelectionClick(line)
            }

            fun attachLongPress(view: View) {
                view.setOnLongClickListener { onLongPress(entry.address); true }
                if (view is ViewGroup) for (childIndex in 0 until view.childCount) attachLongPress(view.getChildAt(childIndex))
            }
            attachLongPress(leftRow)
            attachLongPress(line)
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(grid, ViewGroup.LayoutParams(gridWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnScrollChangeListener { _, x, _, _, _ -> onScroll(x) }
        }
        // CUSTOM (CUSTOM.md #165): restore the horizontal position before the first frame is drawn,
        // so a rebuilt table never shows at the far left for one frame and then jumps back.
        scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val observer = scroll.viewTreeObserver
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (scroll.scrollX == scrollX) return true
                scroll.scrollTo(scrollX, 0)
                return false
            }
        })
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(ipColumnWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun heightOf(v: View, w: Int): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        return v.measuredHeight
    }

    // [0] the IP column, then one per key. The narrowest widths that read well; when the page is wider than their sum
    // the rest is shared out (the columns with the longest text get the most), so the table fills the page exactly
    private fun widths(): IntArray {
        val min = IntArray(keys.size + 1)
        val floor = IntArray(keys.size + 1)

        min[0] = ipMin()
        floor[0] = min[0]

        keys.forEachIndexed { i, k ->
            val header = headerNeed(k)
            val button = if (k == Key.TEST) speedButtonNeed() else 0
            floor[i + 1] = maxOf(header, button)
            min[i + 1] = maxOf(width(k), floor[i + 1])
        }

        // CUSTOM.md #94: the time split is as wide as its longest line, so "首字节 447 ms" does not wrap
        val phaseAt = keys.indexOf(Key.PHASE) + 1

        if (phaseAt > 0) {
            val need = phaseNeed()
            min[phaseAt] = maxOf(min[phaseAt], need)
            floor[phaseAt] = maxOf(floor[phaseAt], need)
        }

        // Keep the public-facing IP and operator on their own two lines, without wrapping either value.
        val exitIpAt = keys.indexOf(Key.EXIT_IP) + 1

        if (exitIpAt > 0) {
            val need = exitIpNeed()
            min[exitIpAt] = maxOf(min[exitIpAt], need)
            floor[exitIpAt] = maxOf(floor[exitIpAt], need)
        }

        val avail = if (savedTable && savedTableAvailableWidth > 0) savedTableAvailableWidth else tableBox.width
        val sum = min.sum()
        val speedAt = keys.indexOf(Key.SPEED) + 1 // 0 when there is no such column

        // how the spare width is shared: the columns with the longest text get the most
        fun weightOf(i: Int): Double {
            val factor = if (i == 0) 1.0 else when (keys[i - 1]) {
                Key.COLO, Key.TCOLO, Key.ASN, Key.EXIT_IP, Key.PORTS -> 1.8
                Key.GEO, Key.LAND -> 1.3
                else -> 1.0
            }

            return min[i] * factor
        }

        if (avail >= sum) {
            val weight = DoubleArray(min.size) { weightOf(it) }
            val total = weight.sum()
            val extra = avail - sum
            val out = IntArray(min.size) { i -> min[i] + (extra * weight[i] / total).toInt() }

            out[out.size - 1] += avail - out.sum()

            return out
        }

        // CUSTOM.md #62: everything but 速度 fits: those columns keep their natural widths and fill the page, and 速度
        // stays behind the right edge (the table scrolls sideways to it)
        if (speedAt > 0) {
            val sumNoSpeed = sum - min[speedAt]

            if (avail >= sumNoSpeed) {
                val weight = DoubleArray(min.size) { if (it == speedAt) 0.0 else weightOf(it) }
                val total = weight.sum()
                val extra = avail - sumNoSpeed
                val out = IntArray(min.size) { i -> min[i] + (extra * weight[i] / total).toInt() }

                val firstColumnAt = keys.indexOf(if (direct || savedTable) Key.COLO else Key.PORT) + 1
                out[firstColumnAt] += avail - sumNoSpeed - (0 until min.size).filter { it != speedAt }.sumOf { out[it] - min[it] }

                return out
            }
        }

        // CUSTOM.md #55 (r67): a little too wide (a long IP in the first column): the other columns give up to 15%
        // (their text just wraps one more time) so that the last column is not pushed off the screen; only when that
        // is not enough the table scrolls sideways as before
        val rest = sum - min[0]
        val need = sum - avail

        if (rest > 0 && need <= rest * 0.15) {
            val out = IntArray(min.size)

            out[0] = min[0]

            for (i in 1 until min.size) out[i] = (min[i] * (rest - need).toDouble() / rest).toInt()

            out[out.size - 1] += avail - out.sum()

            // Keep every header/button's longest explicit line intact. If the remaining columns cannot
            // absorb the shrink without breaking that minimum, retain the natural widths and scroll.
            for (i in out.indices) out[i] = maxOf(out[i], floor[i])

            if (out.sum() <= avail) return out
        }

        return min
    }

    // Measure the longest explicit header line at the same text size, weight and horizontal padding as headCell.
    private fun headerNeed(k: Key): Int {
        val sample = TextView(context).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        val longest = title(k).split('\n').maxOf { sample.paint.measureText(it) }

        return longest.toInt() + 1 + dp(8)
    }

    // Measure the dedicated button label, not iptest_btn_speed which may also be used by non-button text.
    private fun speedButtonNeed(): Int {
        val sample = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 13f
            isAllCaps = false
            setPadding(dp(4), 0, dp(4), 0)
        }
        val label = context.getString(speedButtonTextRes())
        val longest = label.split('\n').maxOf { sample.paint.measureText(it) }

        return longest.toInt() + 1 + dp(8)
    }

    // Use the two-line label only for Chinese; all other locales retain their existing translation.
    private fun speedButtonTextRes(): Int {
        val language = context.resources.configuration.locale?.language ?: Locale.getDefault().language

        return if (language == "zh") R.string.iptest_btn_speed_button else R.string.iptest_btn_speed
    }

    private fun speedButtonMinHeight(): Int {
        val sample = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 13f
            isAllCaps = false
        }
        val line = sample.paint.fontMetricsInt.let { it.bottom - it.top }

        return maxOf(dp(32), line * 2 + dp(4))
    }

    // CUSTOM.md #94: the longest line of the three-line time split in the 14 sp of the cell plus its padding. When it
    // wrapped ("447" and "ms" on two lines) the row became two or three lines taller than anything else in it.
    private fun phaseNeed(): Int {
        val paint = TextView(context).apply { textSize = 14f }.paint
        val longest = rows.maxOfOrNull { r ->
            val texts = if (savedTable) listOf(phaseText(r, true), phaseText(r, false)) else listOf(phaseText(r))
            texts.maxOf { text -> text.split('\n').maxOf { paint.measureText(it) } }
        } ?: 0f

        return (longest.toInt() + dp(10)).coerceAtMost(dp(120))
    }

    // Keep the public-facing IP and its operator on separate, unwrapped lines.
    private fun exitIpNeed(): Int {
        val paint = TextView(context).apply { textSize = 14f }.paint
        val longest = rows.maxOfOrNull { r -> maxOf(paint.measureText(r.exitIp), paint.measureText(r.exitAsn)) } ?: 0f

        return (longest.toInt() + dp(16)).coerceAtLeast(dp(116))
    }

    // the longest IP in the 15 sp bold of the cell, so it is never cut
    private fun ipMin(): Int {
        val paint = TextView(context).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }.paint

        val longest = rows.maxOfOrNull { paint.measureText(it.ip) } ?: 0f

        return (longest.toInt() + dp(16)).coerceIn(dp(112), dp(150))
    }

    private fun title(k: Key): String {
        return context.getString(
            when (k) {
                Key.COLO -> if (direct || savedTable) R.string.iptest_col_colo else R.string.iptest_col_colo_node
                Key.ASN -> R.string.iptest_col_asn
                Key.GEO -> R.string.iptest_col_geo
                Key.LAND, Key.EXIT -> R.string.iptest_col_exit
                Key.EXIT_IP -> R.string.iptest_col_exit_ip
                Key.PORTS, Key.PORT -> R.string.iptest_col_port
                Key.LOSS -> R.string.iptest_col_loss
                Key.MIN -> R.string.iptest_col_min
                Key.MAX -> R.string.iptest_col_max
                Key.AVG -> R.string.iptest_col_avg
                Key.JITTER -> R.string.iptest_col_jitter
                Key.PHASE -> R.string.iptest_col_phase
                Key.TARGET -> R.string.iptest_col_target
                Key.TCOLO -> R.string.iptest_col_tcolo
                Key.TEST -> R.string.iptest_col_test
                Key.SPEED -> R.string.iptest_col_speed
            },
        )
    }

    private fun width(k: Key): Int {
        return dp(
            when (k) {
                Key.COLO -> 84
                Key.ASN -> 84
                Key.GEO, Key.LAND -> 76
                Key.PORTS -> 100 // CUSTOM.md #57: room for the replacement span's same-colour dot
                Key.PORT -> 56
                Key.LOSS -> 52
                Key.MIN, Key.MAX, Key.AVG -> 54
                Key.JITTER -> 54
                Key.PHASE -> 84
                Key.EXIT -> 76
                Key.EXIT_IP -> 116
                Key.TARGET -> 84
                Key.TCOLO -> 84
                Key.TEST -> 52
                Key.SPEED -> 76
            },
        )
    }

    // the colour of an average: under 150 ms green, under 300 orange, else red
    private fun latencyColor(ms: Int): Int = when {
        ms < 150 -> colorGood
        ms < 300 -> colorWarn
        else -> colorBad
    }

    // CUSTOM.md #68: the colour of the jitter: under 30 ms green, under 100 orange, else red (a guess at what is steady
    // enough; the number itself is what to compare between addresses)
    private fun jitterColor(ms: Int): Int = when {
        ms < 30 -> colorGood
        ms < 100 -> colorWarn
        else -> colorBad
    }

    // CUSTOM.md #68: three lines, one per part; "-" for a part that was not measured (a plain http address has no
    // handshake part), all "-" for an address that failed
    private fun phaseText(r: IpTestRow, directMode: Boolean = direct): String {
        if (!r.ok) return "-"

        val v = { n: Int -> if (n > 0) context.getString(R.string.iptest_ms_short, n) else "-" }

        return context.getString(
            if (directMode) R.string.iptest_phase_cell_direct else R.string.iptest_phase_cell_node,
            v(r.dial),
            v(r.shake),
            v(r.first),
        )
    }

    private fun lossColor(loss: Int): Int = when {
        loss == 0 -> colorGood
        loss < 50 -> colorWarn
        else -> colorBad
    }

    // CUSTOM.md #62: "🇺🇸 LAS（拉斯维加斯 Las Vegas）". The flag is the country of the data centre and the English city
    // is what the network list says; both are missing when that list was not fetched. The Chinese name is built in
    // (ColoNames); a code it does not have keeps the English city, and with neither the bare code stays.
    private fun coloHead(code: String): String {
        val flag = CountryFlag.of(coloCountries[code.uppercase(Locale.US)])

        return if (flag.isEmpty()) code else "$flag $code"
    }

    private fun coloTail(code: String): String {
        val up = code.uppercase(Locale.US)
        val names = listOfNotNull(ColoNames.cn(up), colos[up]).filter { it.isNotEmpty() }.distinct()

        return if (names.isEmpty()) "" else "（" + names.joinToString(" ") + "）"
    }

    private fun coloLabel(code: String): String {
        if (code.isEmpty()) return "-"

        return coloHead(code) + coloTail(code)
    }

    // CUSTOM.md #99: show a concise download failure reason in the speed cell; full detail remains in the live log.
    private fun speedText(r: IpTestRow): String {
        return when {
            speedIp == r.ip -> "…"
            r.speed > 0 -> context.getString(R.string.iptest_speed_cell, String.format(Locale.US, "%.1f", r.speed))
            r.speedErr.isNotEmpty() -> {
                val status = Regex("\\bHTTP(?:/\\d(?:\\.\\d)?)?\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
                    .find(r.speedErr)?.groupValues?.get(1)
                val lower = r.speedErr.lowercase(Locale.US)
                val reason = when {
                    status != null -> "HTTP $status"
                    lower.contains("timeout") || lower.contains("deadline exceeded") -> context.getString(R.string.iptest_speed_timeout)
                    lower.contains("no data") -> context.getString(R.string.iptest_speed_no_data)
                    else -> r.speedErr.trim().take(20)
                }
                context.getString(R.string.iptest_speed_failed) + "\n" + reason
            }
            else -> "-"
        }
    }

    private fun portsPlain(r: IpTestRow): String {
        if (r.ports.isEmpty()) return "-"

        return r.ports.joinToString(" ") { (if (it.ok) "" else "✗") + it.port }
    }

    private fun plainOf(k: Key, r: IpTestRow): String {
        val tested = r.ok
        val ms = { v: Int -> if (tested) context.getString(R.string.iptest_ms_short, v) else "-" }

        return when (k) {
            Key.COLO -> coloLabel(r.colo)
            Key.ASN -> r.asn.ifEmpty { "-" }
            Key.GEO -> if (tested) CountryName.label(r.geo) else "-"
            Key.LAND -> if (tested) CountryName.label(r.country) else "-"
            Key.PORTS -> portsPlain(r)
            Key.PORT -> "-"
            Key.LOSS -> "${r.loss}%"
            Key.MIN -> ms(r.min)
            Key.MAX -> ms(r.max)
            Key.AVG -> ms(r.avg)
            Key.JITTER -> if (tested) context.getString(R.string.iptest_ms_short, r.jitter) else "-"
            Key.PHASE -> phaseText(r)
            Key.EXIT -> if (tested) CountryName.label(r.country).let { if (it == "-") "？" else it } else "-"
            Key.EXIT_IP -> if (tested && r.exitIp.isNotEmpty()) r.exitIp + "\n" + r.exitAsn.ifEmpty { "-" } else "-"
            Key.TARGET -> targetText(r)
            Key.TCOLO -> coloLabel(r.targetColo)
            Key.TEST -> ""
            Key.SPEED -> speedText(r)
        }
    }

    private fun ownerCell(r: IpTestRow): View {
        val value = when {
            r.asn.isNotEmpty() -> r.asn
            r.isDomain || !r.ok -> "-"
            ownerLookupIp == r.ip -> "查询中…"
            else -> "-"
        }
        return textCell(value, colorText)
    }
    private fun cellOf(k: Key, r: IpTestRow, w: Int): View {
        return when (k) {
            Key.PORTS -> portsCell(r)
            Key.COLO -> coloCell(r, w)
            Key.TCOLO -> coloCell(r, w, r.targetColo)
            Key.TEST -> testCell(r)
            Key.ASN -> ownerCell(r)
            Key.TARGET -> targetCell(r)
            else -> {
                val color = when (k) {
                    Key.LOSS -> lossColor(r.loss)
                    Key.AVG -> if (r.ok) latencyColor(r.avg) else colorMuted
                    Key.JITTER -> if (r.ok) jitterColor(r.jitter) else colorMuted
                    Key.TARGET -> targetColor(r)
                    Key.SPEED -> if (r.speed > 0) colorText else if (r.speedErr.isNotEmpty()) colorBad else colorMuted
                    Key.ASN, Key.PORT -> colorText
                    else -> colorText
                }

                textCell(plainOf(k, r), color, bold = k == Key.AVG)
            }
        }
    }

    // the flag, the code and its names on one line when the column is wide enough, else the names under the code
    // (never cut in the middle of the code)
    // CUSTOM.md #66: what the target site answered: "200", "403", "302 → host"; "-" when it gave no answer
    private fun targetText(r: IpTestRow): String {
        if (!r.ok) return handshakeFailureText(r.reason.ifBlank { r.code.ifBlank { "-" } })
        if (r.targetStatus == 0) return "-"

        val note = if (r.targetNote.isEmpty()) "" else " → " + hostOf(r.targetNote)

        // CUSTOM.md #73: a few words after the code say what it means
        val word = TargetStatusText.of(context, r.targetStatus).let { if (it.isEmpty()) "" else " " + it }

        return r.targetStatus.toString() + word + note + softLine(r)
    }

    private fun targetCell(r: IpTestRow): View {
        val full = targetText(r)
        val display = if (!r.ok && r.reason.length > 40) {
            handshakeFailureText(r.reason.take(40)) + "…"
        } else full
        return textCell(display, targetColor(r)).apply {
            if (!r.ok) setOnClickListener {
                onCopy(handshakeFailureText(r.reason.ifBlank { r.code.ifBlank { "-" } }))
            }
        }
    }

    private fun handshakeFailureText(reason: String): String =
        context.getString(R.string.iptest_handshake_failed) + reason

    private fun pageHintText(row: IpTestRow): String {
        val existing = row.targetSoftNote.ifBlank { row.targetSoft }
        if (existing.isNotBlank()) return existing
        if (row.targetPageHint.isBlank()) return ""

        val title = row.targetPageHint.substringBefore('\n')
        val body = row.targetPageHint.substringAfter('\n', "").ifBlank { "-" }
        return if (title.isNotBlank()) {
            context.getString(R.string.iptest_page_hint, title, body)
        } else {
            context.getString(R.string.iptest_page_hint_no_title, body)
        }
    }

    // CUSTOM.md #67: the second line: what the page itself says (moved by the page, or says the region is refused)
    private fun softLine(r: IpTestRow): String = when (r.targetSoft) {
        "refresh", "script" -> "\n" + context.getString(R.string.iptest_soft_redirect, hostOf(r.targetSoftNote))
        "region" -> "\n" + context.getString(R.string.iptest_soft_region)
        else -> ""
    }

    private fun hostOf(location: String): String {
        val host = try {
            java.net.URI(location).host
        } catch (_: Exception) {
            null
        }

        return host ?: location.take(30)
    }

    private fun targetColor(r: IpTestRow): Int = when {
        !r.ok -> colorBad
        r.targetStatus == 0 -> colorMuted
        r.targetSoft == "region" -> colorBad
        r.targetSoft == "refresh" || r.targetSoft == "script" -> colorWarn
        r.targetStatus in 200..299 -> colorGood
        r.targetStatus in 300..399 -> colorWarn
        else -> colorBad
    }

    private fun coloCell(r: IpTestRow, w: Int, code: String = r.colo): View {
        val one = coloLabel(code)
        val cell = textCell(one, colorText)

        if (code.isNotEmpty() && cell.paint.measureText(one) > w - dp(8)) {
            val tail = coloTail(code)

            if (tail.isNotEmpty()) cell.text = coloHead(code) + "\n" + tail
        }

        return cell
    }

    private fun textCell(text: String, color: Int, bold: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            // CUSTOM.md #55 (r67): the first line of every cell sits on the same line as the IP (top aligned), not
            // in the middle of the row, so a cell with many lines (the ports) is not pushed down and cut at the bottom
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setTextColor(color)
            setPadding(dp(4), dp(5), dp(4), dp(5))

            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun headCell(text: String, w: Int): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorMuted)
            setBackgroundColor(colorSurface)
            minWidth = w
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
    }

    // the ports that work in the normal colour, the ones that do not grey with a cross; a tap shows each one's result
    private fun portsCell(r: IpTestRow): View {
        val cell = textCell("", colorText)

        if (r.ports.isEmpty()) {
            cell.text = "-"
            cell.setTextColor(colorMuted)

            return cell
        }

        val sb = SpannableStringBuilder()

        for (p in r.ports) {
            if (sb.isNotEmpty()) sb.append(' ')

            val from = sb.length

            add(sb, (if (p.ok) "" else "✗") + p.port, if (p.ok) colorGood else colorMuted)
            CheckMark.apply(context, sb, from, sb.length) // CUSTOM.md #57: the ✗ in red
        }

        cell.text = sb
        cell.setOnClickListener { onDetail(context.getString(R.string.iptest_port_title, r.ip), portDetail(r)) }

        return cell
    }

    private fun portDetail(r: IpTestRow): String {
        val sb = StringBuilder()

        for (p in r.ports) {
            sb.append(
                if (p.ok) {
                    context.getString(R.string.iptest_port_ok, p.port, p.ms)
                } else {
                    context.getString(R.string.iptest_port_fail, p.port, explain(p.code, p.reason))
                },
            ).append('\n')
        }

        if (r.ports.isEmpty()) sb.append(context.getString(R.string.iptest_port_none)).append('\n')

        sb.append('\n').append(context.getString(R.string.iptest_port_note))

        return sb.toString()
    }

    // "下载测速" of a row that works; "取消" on the row that is downloading; the others are grey while one is
    private fun testCell(r: IpTestRow): View {
        val box = LinearLayout(context).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL // CUSTOM.md #55 (r67)
            setPadding(0, dp(2), 0, 0)
        }

        if (!r.ok) return box

        val running = speedIp == r.ip
        val enabled = !testing && (speedIp == null || running)

        val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(if (running) R.string.check_cancel else speedButtonTextRes())
            textSize = 13f
            isAllCaps = false
            gravity = Gravity.CENTER
            maxLines = 2
            minWidth = 0
            minHeight = speedButtonMinHeight()
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(5)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(colorLink)
            setTextColor(colorLink)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f

            if (enabled) setOnClickListener { if (running) onSpeedCancel() else onSpeed(r.ip) }
        }

        box.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return box
    }

    // the IP (a tap copies it, dashed line under it) and under it, small and grey, what the handshake showed
    private fun ipCell(r: IpTestRow): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP // CUSTOM.md #55 (r67): same top padding as the other cells, so the lines match
            setPadding(dp(8), dp(5), dp(4), dp(5))
        }

        val ip = DashedTextView(context).apply {
            text = r.ip
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.TOP or Gravity.START
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (r.ok) colorText else colorBad)
            setOnClickListener { onCopy(r.ip) }
        }

        box.addView(ip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val small = ipDetails(r)

        if (small.isNotEmpty()) {
            val t = TextView(context).apply {
                text = small
                textSize = 12f
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (r.ok) colorMuted else colorBad)
            }

            box.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        return box
    }

    private fun ipDetails(r: IpTestRow): String {
        if (!r.ok) {
            return if (r.sent > 0 || r.reason.isNotEmpty()) handshakeFailureText(r.reason.ifBlank { r.code.ifBlank { "-" } }) else ""
        }

        val parts = ArrayList<String>()

        if (direct) {
            if (r.tls.isNotEmpty()) parts += context.getString(R.string.iptest_tls, r.tls)
            if (r.alpn.isNotEmpty()) parts += r.alpn

            when (r.ech) {
                "accepted" -> parts += context.getString(R.string.iptest_ech_accepted)
                "notaccepted" -> parts += context.getString(R.string.iptest_ech_notaccepted)
                "rejected" -> parts += context.getString(R.string.iptest_ech_rejected)
            }

            if (r.cf) parts += context.getString(R.string.iptest_cf)
        }

        if (r.inHosts) parts += context.getString(if (direct) R.string.iptest_in_config else R.string.iptest_in_hosts)

        return parts.joinToString(" · ")
    }

    // the core's own wording carries local addresses and ports, so it is shown only when the cause is not recognised
    private fun explain(code: String, reason: String): String {
        val text = context.getString(
            when (code) {
                "timeout" -> R.string.check_code_timeout
                "refused" -> R.string.check_code_refused
                "reset" -> R.string.check_code_reset
                "eof" -> R.string.check_code_eof
                "dns" -> R.string.check_code_dns
                "network" -> R.string.check_code_network
                "tls" -> R.string.check_code_tls
                "auth" -> R.string.check_code_auth
                "reject" -> R.string.check_code_reject
                "echrejected" -> R.string.check_code_echrejected
                else -> return context.getString(
                    R.string.check_failed_raw,
                    ErrorsText.explain(context, code),
                    reason.ifEmpty { "-" },
                )
            },
        )

        return context.getString(R.string.check_failed, text)
    }

    private fun add(sb: SpannableStringBuilder, text: String, color: Int? = null, bold: Boolean = false) {
        val start = sb.length

        sb.append(text)

        if (color != null) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

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
    private class DashedTextView(context: Context) : TextView(context) {
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * context.resources.displayMetrics.density
            pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val l = layout ?: return

            if (l.lineCount == 0) return

            dash.color = currentTextColor

            val y = totalPaddingTop + l.getLineBottom(0) - dash.strokeWidth
            val x0 = totalPaddingLeft + l.getLineLeft(0)
            val x1 = totalPaddingLeft + l.getLineRight(0)

            canvas.drawLine(x0, y, x1, y, dash)
        }
    }

    companion object {
        private const val MAX_LOG = 200
    }
}
