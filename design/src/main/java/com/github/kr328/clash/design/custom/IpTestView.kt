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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.core.model.IpTestRow
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
// r85 (CUSTOM.md #68): 抖动 and 分段耗时 (three lines: TCP / TLS / 首字节 in direct mode, 拨号 / 握手 / 首字节 through a node) follow 平均延迟.
class IpTestView(private val context: Context, private val direct: Boolean, private val target: Boolean = false) {
    var onCopy: (String) -> Unit = {}
    var onSpeed: (String) -> Unit = {} // a tap on "下载测速" of a row
    var onSpeedCancel: () -> Unit = {} // a tap on "取消" of the row that is downloading
    var onDetail: (String, String) -> Unit = { _, _ -> } // a tap on the ports of a row: title, text

    // CUSTOM.md #68: JITTER (抖动) and PHASE (分段耗时) sit right after AVG
    private enum class Key { COLO, ASN, GEO, LAND, PORTS, LOSS, MIN, MAX, AVG, JITTER, PHASE, EXIT, PORT, FASTER, TEST, SPEED, TARGET, TCOLO }

    private val keys = if (direct) {
        listOf(Key.COLO, Key.ASN, Key.GEO, Key.LAND, Key.PORTS, Key.LOSS, Key.MIN, Key.MAX, Key.AVG, Key.JITTER, Key.PHASE, Key.TEST, Key.SPEED)
    } else if (target) {
        // CUSTOM.md #66: with a target address the tries go to that site: 目标站反应 and 出口机房 (its Cf-Ray) are added, and
        // 比对照快 is dropped (the reference was measured against another address)
        listOf(Key.COLO, Key.ASN, Key.GEO, Key.EXIT, Key.TARGET, Key.TCOLO, Key.PORT, Key.LOSS, Key.MIN, Key.MAX, Key.AVG, Key.JITTER, Key.PHASE, Key.TEST, Key.SPEED)
    } else {
        // CUSTOM.md #63: through a node too: 机房 | 归属运营商 | 归属地 | 落地 | 端口 | 丢包率 | ...
        listOf(Key.COLO, Key.ASN, Key.GEO, Key.EXIT, Key.PORT, Key.LOSS, Key.MIN, Key.MAX, Key.AVG, Key.JITTER, Key.PHASE, Key.FASTER, Key.TEST, Key.SPEED)
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
    private var dnsReport: DnsCheckReport? = null
    private var colos: Map<String, String> = emptyMap()
    private var coloCountries: Map<String, String> = emptyMap() // CUSTOM.md #62: data centre code -> country code
    private var speedIp: String? = null
    private var testing = false

    private var infoText: CharSequence = ""
    private val logLines = ArrayList<String>()

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val info = TextView(context).apply {
        textSize = 15f
        setTextColor(colorText)
        setPadding(0, 0, 0, dp(8))
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
        text = "详细诊断  ▴"
        setOnClickListener {
            diagnosticsExpanded = !diagnosticsExpanded
            diagnosticsBody.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
            text = if (diagnosticsExpanded) "详细诊断  ▴" else "详细诊断  ▾"
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

        // the page scrolls around it, so while a finger is on the log, the log scrolls
        setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)

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

        root.addView(info, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tableBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(hint, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(diagnosticsBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(logTitle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(logScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        redraw()
    }

    /** The lines above the table: [head] bold, [sub] grey, [warnings] orange (not-understood words, skipped domains, cut). */
    fun setInfo(head: String, sub: String, warnings: List<String>) {
        val sb = SpannableStringBuilder()

        add(sb, head + "\n", bold = true)

        if (sub.isNotEmpty()) add(sb, sub + "\n", colorMuted)

        for (w in warnings) add(sb, w + "\n", colorWarn)

        infoText = sb
        info.text = sb
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
                    "IP", "TLS 版本", "协商协议（ALPN）",
                    "证书主题", "证书颁发者", "证书生效时间", "证书到期时间",
                    "证书域名（DNS SAN）", "证书 IP 地址（IP SAN）", "证书验证状态",
                    "服务器标识（Server）", "Cloudflare 请求编号（Cf-Ray）",
                    "响应内容类型（Content-Type）", "重定向地址（Location）",
                    "重试等待时间（Retry-After）", "Cloudflare 防护标识（CF-Mitigated）",
                )
            } else {
                listOf("IP", "TLS 版本", "协商协议（ALPN）")
            }

            val values = rows.map { row ->
                if (target) {
                    listOf(
                        row.ip,
                        row.targetTls.ifBlank { "未获取" },
                        row.targetAlpn.ifBlank { "未获取" },
                        row.targetCertSubject.ifBlank { "未获取" },
                        row.targetCertIssuer.ifBlank { "未获取" },
                        row.targetCertNotBefore.ifBlank { "未获取" },
                        row.targetCertNotAfter.ifBlank { "未获取" },
                        row.targetCertDnsNames.ifBlank { "未获取" },
                        row.targetCertIpAddresses.ifBlank { "未获取" },
                        when (row.targetCertVerification) {
                            "skipped" -> "未验证（跳过证书验证）"
                            else -> row.targetCertVerification.ifBlank { "未获取" }
                        },
                        row.targetServer.ifBlank { "未获取" },
                        row.targetCfRay.ifBlank { "未获取" },
                        row.targetContentType.ifBlank { "未获取" },
                        row.targetNote.ifBlank { "未获取" },
                        row.targetRetryAfter.ifBlank { "未获取" },
                        row.targetCfMitigated.ifBlank { "未获取" },
                    )
                } else {
                    listOf(
                        row.ip,
                        row.tls.ifBlank { "未获取" },
                        row.alpn.ifBlank { "未获取" },
                    )
                }
            }

            copied.append("IP 详细诊断\n")
            appendMarkdown(headers, values)
            addDiagnosticsTable("IP 详细诊断", headers, values)
        }

        dnsReport?.let { report ->
            val dnsRows = ArrayList<List<String>>()
            for (server in report.servers) {
                dnsRows += listOf(
                    server.address.ifBlank { "未获取" },
                    if (server.ok && server.ms > 0) "${server.ms} ms" else "未获取",
                )
            }
            if (report.core.asked) {
                dnsRows += listOf("核心解析", if (report.core.ok && report.core.ms > 0) "${report.core.ms} ms" else "未获取")
            }
            report.kernel?.let { kernel ->
                dnsRows += listOf("内核 DNS", if (kernel.ok && kernel.ms > 0) "${kernel.ms} ms" else "未获取")
            }
            if (dnsRows.isEmpty()) dnsRows += listOf("无可显示结果", "未获取")

            val dnsTitle = "DNS 检查（独立结果：${report.domain}）"
            copied.append(dnsTitle).append('\n')
            appendMarkdown(listOf("DNS 项目", "查询耗时"), dnsRows)
            addDiagnosticsTable(dnsTitle, listOf("DNS 项目", "查询耗时"), dnsRows, 150, 110)
        }

        diagnosticsPlain = copied.toString().trim()
        if (diagnosticsBody.childCount == 0) {
            val empty = TextView(context).apply {
                text = "暂无详细诊断数据"
                textSize = 13f
                setTextColor(colorMuted)
            }
            diagnosticsBody.addView(empty, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
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

    private fun diagnosticCell(text: String, backgroundColor: Int, bold: Boolean): TextView {
        return TextView(context).apply {
            this.text = text.ifBlank { "未获取" }
            textSize = 12f
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

        sb.append(infoText).append("\n\n")
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
            sb.append("\n\n详细诊断\n").append(diagnosticsPlain)
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

        min[0] = ipMin()

        keys.forEachIndexed { i, k -> min[i + 1] = width(k) }

        // CUSTOM.md #94: the time split is as wide as its longest line, so "首字节 447 ms" does not wrap
        val phaseAt = keys.indexOf(Key.PHASE) + 1

        if (phaseAt > 0) min[phaseAt] = maxOf(min[phaseAt], phaseNeed())

        val avail = tableBox.width
        val sum = min.sum()
        val speedAt = keys.indexOf(Key.SPEED) + 1 // 0 when there is no such column

        // how the spare width is shared: the columns with the longest text get the most
        fun weightOf(i: Int): Double {
            val factor = if (i == 0) 1.0 else when (keys[i - 1]) {
                Key.COLO, Key.TCOLO, Key.ASN, Key.PORTS -> 1.8
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

                out[1] += avail - sumNoSpeed - (0 until min.size).filter { it != speedAt }.sumOf { out[it] - min[it] }

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

            return out
        }

        return min
    }

    // CUSTOM.md #94: the longest line of the three-line time split in the 14 sp of the cell plus its padding. When it
    // wrapped ("447" and "ms" on two lines) the row became two or three lines taller than anything else in it.
    private fun phaseNeed(): Int {
        val paint = TextView(context).apply { textSize = 14f }.paint
        val longest = rows.maxOfOrNull { r -> phaseText(r).split('\n').maxOf { paint.measureText(it) } } ?: 0f

        return (longest.toInt() + dp(10)).coerceAtMost(dp(120))
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
                Key.COLO -> if (direct) R.string.iptest_col_colo else R.string.iptest_col_colo_node
                Key.ASN -> R.string.iptest_col_asn
                Key.GEO -> R.string.iptest_col_geo
                Key.LAND, Key.EXIT -> R.string.iptest_col_exit
                Key.PORTS, Key.PORT -> R.string.iptest_col_port
                Key.LOSS -> R.string.iptest_col_loss
                Key.MIN -> R.string.iptest_col_min
                Key.MAX -> R.string.iptest_col_max
                Key.AVG -> R.string.iptest_col_avg
                Key.JITTER -> R.string.iptest_col_jitter
                Key.PHASE -> R.string.iptest_col_phase
                Key.FASTER -> R.string.iptest_col_faster
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
                Key.FASTER -> 70
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
    private fun phaseText(r: IpTestRow): String {
        if (!r.ok) return "-"

        val v = { n: Int -> if (n > 0) context.getString(R.string.iptest_ms_short, n) else "-" }

        return context.getString(
            if (direct) R.string.iptest_phase_cell_direct else R.string.iptest_phase_cell_node,
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
            Key.FASTER -> if (r.faster > 0) context.getString(R.string.iptest_faster_cell, r.faster) else "-"
            Key.TARGET -> targetText(r)
            Key.TCOLO -> coloLabel(r.targetColo)
            Key.TEST -> ""
            Key.SPEED -> speedText(r)
        }
    }

    private fun cellOf(k: Key, r: IpTestRow, w: Int): View {
        return when (k) {
            Key.PORTS -> portsCell(r)
            Key.COLO -> coloCell(r, w)
            Key.TCOLO -> coloCell(r, w, r.targetColo)
            Key.TEST -> testCell(r)
            else -> {
                val color = when (k) {
                    Key.LOSS -> lossColor(r.loss)
                    Key.AVG -> if (r.ok) latencyColor(r.avg) else colorMuted
                    Key.JITTER -> if (r.ok) jitterColor(r.jitter) else colorMuted
                    Key.FASTER -> if (r.faster > 0) colorGood else colorMuted
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
        if (!r.ok || r.targetStatus == 0) return "-"

        val note = if (r.targetNote.isEmpty()) "" else " → " + hostOf(r.targetNote)

        // CUSTOM.md #73: a few words after the code say what it means
        val word = TargetStatusText.of(context, r.targetStatus).let { if (it.isEmpty()) "" else " " + it }

        return r.targetStatus.toString() + word + note + softLine(r)
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
        !r.ok || r.targetStatus == 0 -> colorMuted
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
            text = context.getString(if (running) R.string.check_cancel else R.string.iptest_btn_speed)
            textSize = 13f
            isAllCaps = false
            gravity = Gravity.CENTER
            maxLines = 1
            minWidth = 0
            minHeight = dp(32)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(5)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(colorLink)
            setTextColor(colorLink)
            setPadding(dp(4), 0, dp(4), 0)
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
            return if (r.sent > 0 || r.reason.isNotEmpty()) explain(r.code, r.reason) else ""
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
