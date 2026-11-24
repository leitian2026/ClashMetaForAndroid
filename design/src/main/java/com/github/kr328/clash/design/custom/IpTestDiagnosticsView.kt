package com.github.kr328.clash.design.custom

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.model.IpTestExtResult
import com.github.kr328.clash.design.R

private val HEADER_WHITESPACE_REGEX = Regex("""\s+""")

internal class IpTestDiagnosticsView(
    private val env: IpTestEnv,
    @Suppress("UNUSED_PARAMETER") cells: IpTestCells,
    @Suppress("UNUSED_PARAMETER") widths: IpTestColumnWidths,
    @Suppress("UNUSED_PARAMETER") private val requestRedraw: () -> Unit,
) {
    private var diagnosticsExpanded = true
    private val diagnosticsBody = LinearLayout(env.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(env.colorSurface)
            cornerRadius = dp(6).toFloat()
        }
    }
    private var plainText = ""
    private val diagnosticsTitle = TextView(env.context).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(env.colorText)
        setPadding(0, dp(8), 0, dp(6))
        text = "${env.context.getString(R.string.iptest_diag_title)}  ▴"
        setOnClickListener {
            diagnosticsExpanded = !diagnosticsExpanded
            diagnosticsBody.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
            text = "${env.context.getString(R.string.iptest_diag_title)}  ${if (diagnosticsExpanded) "▴" else "▾"}"
        }
    }
    val box = LinearLayout(env.context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.VISIBLE
        addView(diagnosticsTitle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(diagnosticsBody, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val diagnosticsPlain: String get() = plainText

    init {
        diagnosticsBody.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL && r - l > 0 && env.rows.isNotEmpty()) diagnosticsBody.post { updateDiagnostics() }
        }
    }

    private fun dp(v: Int): Int = (v * env.context.resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private class ExtCell(val text: CharSequence, val title: String, val detail: String)
    private class ExtRow(val ip: String, val cells: List<ExtCell>)

        fun updateDiagnostics() {
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

        if (env.rows.isNotEmpty() && (env.target || !env.direct)) {
            // Only show fields that are not already present in the original IP table.
            // IP is retained solely as the row identifier for matching each diagnostic record.
            val headers = if (env.target) {
                listOf(
                    env.context.getString(R.string.iptest_diag_ip), env.context.getString(R.string.iptest_diag_tls), env.context.getString(R.string.iptest_diag_alpn),
                    env.context.getString(R.string.iptest_diag_cert), env.context.getString(R.string.iptest_diag_cert_ip),
                    env.context.getString(R.string.iptest_diag_cf_ray), env.context.getString(R.string.iptest_diag_content_type),
                    env.context.getString(R.string.iptest_diag_location), env.context.getString(R.string.iptest_diag_retry_after),
                    env.context.getString(R.string.iptest_diag_cf_mitigated), env.context.getString(R.string.iptest_diag_page_hint),
                )
            } else {
                listOf(env.context.getString(R.string.iptest_diag_ip), env.context.getString(R.string.iptest_diag_tls), env.context.getString(R.string.iptest_diag_alpn))
            }

            val values = env.rows.map { row ->
                val failed = !row.ok || (env.target && row.targetStatus == 0)
                val dash = env.context.getString(R.string.iptest_diag_dash)
                val none = env.context.getString(R.string.iptest_diag_none)
                val missing = env.context.getString(R.string.iptest_diag_not_obtained)

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

                if (env.target) {
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
                        env.context.getString(
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
                        optional(pageHintText(env.context, row)),
                    )
                } else {
                    listOf(
                        row.ip,
                        required(row.tls),
                        required(row.alpn),
                    )
                }
            }

            copied.append(env.context.getString(R.string.iptest_diag_ip_title) + "\n")
            // Export uses the original full values. Only the on-screen certificate text is shortened.
            appendMarkdown(headers, values)
            if (env.target) {
                val displayValues = values.mapIndexed { index, original ->
                    val row = env.rows[index]
                    val failed = !row.ok || row.targetStatus == 0
                    val dash = env.context.getString(R.string.iptest_diag_dash)
                    val missing = env.context.getString(R.string.iptest_diag_not_obtained)
                    val domain = if (failed) dash else row.targetCertDnsNames.ifBlank { missing }
                    val issuer = if (failed) dash else certificateIssuerName(row.targetCertIssuer.ifBlank { missing })
                    val expiry = if (failed) dash else certificateDate(row.targetCertNotAfter.ifBlank { missing })
                    original.toMutableList().apply {
                        this[3] = env.context.getString(R.string.iptest_diag_cert_value, domain, issuer, expiry)
                    }
                }
                addDetailedDiagnosticsTable(
                    env.context.getString(R.string.iptest_diag_ip_title),
                    headers,
                    displayValues,
                    values,
                )
            } else {
                // The non-env.target TLS / protocol mini-table keeps its existing layout and sizing.
                addDiagnosticsTable(env.context.getString(R.string.iptest_diag_ip_title), headers, values)
            }
        }

        // CUSTOM.md #77: extension diagnostics stay below the existing IP table; never add more main-table columns.
        if (env.direct && env.rows.any { it.ws.tested || it.h3.tested || it.mtu.tested }) {
            val headers = listOf(
                env.context.getString(R.string.iptest_ext_ip),
                env.context.getString(R.string.iptest_ext_ws),
                env.context.getString(R.string.iptest_ext_h3),
                env.context.getString(R.string.iptest_ext_mtu),
            )
            fun resultText(result: IpTestExtResult, mtu: Boolean = false): String {
                if (!result.tested) return env.context.getString(R.string.iptest_diag_dash)
                if (mtu) {
                    val parts = ArrayList<String>()
                    if (result.tcpMss > 0 && result.tcpMtu > 0) {
                        parts += env.context.getString(R.string.iptest_ext_tcp_mtu, result.tcpMss, result.tcpMtu)
                    } else {
                        parts += env.context.getString(R.string.iptest_ext_tcp_none)
                    }
                    if (result.udpMtu > 0) {
                        val format = if (result.detail.contains("UDP upper bound reached")) {
                            R.string.iptest_ext_udp_mtu_at_least
                        } else {
                            R.string.iptest_ext_udp_mtu
                        }
                        parts += env.context.getString(format, result.udpMtu)
                    } else if (result.udpNoResponse) {
                        parts += env.context.getString(R.string.iptest_ext_udp_none)
                    } else {
                        parts += env.context.getString(R.string.iptest_ext_udp_dash)
                    }
                    if (result.reason.isNotBlank()) parts += result.reason
                    return parts.joinToString(" · ").ifBlank { env.context.getString(R.string.iptest_ext_failed) }
                }
                if (result.reason.isNotBlank()) return result.reason
                if (result.status > 0) {
                    val status = result.status.toString()
                    val statusText = if (result.ms > 0) env.context.getString(R.string.iptest_ext_status_ms, status, result.ms) else status
                    val detail = result.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    return statusText + detail
                }
                if (result.ms > 0) return env.context.getString(R.string.iptest_ext_ms, result.ms)
                return result.detail.ifBlank { env.context.getString(if (result.ok) R.string.iptest_ext_ok else R.string.iptest_ext_failed) }
            }
            val values = env.rows.map { row ->
                listOf(row.ip, resultText(row.ws), resultText(row.h3), resultText(row.mtu, mtu = true))
            }
            val title = env.context.getString(R.string.iptest_ext_title)
            copied.append(title).append('\n')
            appendMarkdown(headers, values)
            // CUSTOM.md #156 step 83: the shown table is a compact full-width one (one short line per cell, tap for the
            // full text); the copied text above keeps the full values.
            val extRows = env.rows.mapIndexed { index, row ->
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

        env.dnsReport?.let { report ->
            val dnsRows = ArrayList<List<String>>()
            for (server in report.servers) {
                dnsRows += listOf(
                    server.address.ifBlank { env.context.getString(R.string.iptest_diag_not_obtained) },
                    if (server.ok && server.ms > 0) "${server.ms} ms" else env.context.getString(R.string.iptest_diag_not_obtained),
                )
            }
            if (report.core.asked) {
                dnsRows += listOf(env.context.getString(R.string.iptest_diag_core_dns), if (report.core.ok && report.core.ms > 0) "${report.core.ms} ms" else env.context.getString(R.string.iptest_diag_not_obtained))
            }
            report.kernel?.let { kernel ->
                dnsRows += listOf(env.context.getString(R.string.iptest_diag_kernel_dns), if (kernel.ok && kernel.ms > 0) "${kernel.ms} ms" else env.context.getString(R.string.iptest_diag_not_obtained))
            }
            if (dnsRows.isEmpty()) dnsRows += listOf(env.context.getString(R.string.iptest_diag_no_results), env.context.getString(R.string.iptest_diag_not_obtained))

            val dnsTitle = env.context.getString(R.string.iptest_diag_dns_title, report.domain)
            copied.append(dnsTitle).append('\n')
            appendMarkdown(listOf(env.context.getString(R.string.iptest_diag_dns_item), env.context.getString(R.string.iptest_diag_dns_time)), dnsRows)
            addDiagnosticsTable(dnsTitle, listOf(env.context.getString(R.string.iptest_diag_dns_item), env.context.getString(R.string.iptest_diag_dns_time)), dnsRows, 150, 110)
        }

        plainText = copied.toString().trim()
        if (diagnosticsBody.childCount == 0) {
            val empty = TextView(env.context).apply {
                text = env.context.getString(R.string.iptest_diag_empty)
                textSize = 13f
                setTextColor(env.colorMuted)
            }
            diagnosticsBody.addView(empty, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun addDetailedDiagnosticsTable(
        title: String,
        headers: List<String>,
        values: List<List<String>>,
        copyValues: List<List<String>>,
    ) {
        val heading = TextView(env.context).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(env.colorText)
            setPadding(0, dp(10), 0, dp(6))
        }
        diagnosticsBody.addView(heading, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val availableWidth = (diagnosticsBody.width - diagnosticsBody.paddingLeft - diagnosticsBody.paddingRight)
            .takeIf { it > 0 } ?: (env.context.resources.displayMetrics.widthPixels - dp(48))
        val widths = detailedColumnWidths(headers, values, availableWidth)
        val firstWidth = widths.first()
        val gridWidth = widths.drop(1).sum()

        val left = LinearLayout(env.context).apply { orientation = LinearLayout.VERTICAL }
        val grid = LinearLayout(env.context).apply { orientation = LinearLayout.VERTICAL }

        val leftHeader = detailedHeaderCell(headers.first())
        val rightHeaders = headers.drop(1).map { detailedHeaderCell(it) }
        val headerHeight = maxOf(
            dp(42),
            detailedHeight(leftHeader, firstWidth),
            rightHeaders.mapIndexed { index, cell -> detailedHeight(cell, widths[index + 1]) }.maxOrNull() ?: 0,
        )

        val leftHeaderBg = FrameLayout(env.context).apply {
            setBackgroundColor(env.colorSurface)
            addView(leftHeader, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        left.addView(leftHeaderBg, lp(firstWidth, headerHeight))

        val headRow = LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(env.colorSurface)
        }
        rightHeaders.forEachIndexed { index, cell ->
            headRow.addView(cell, lp(widths[index + 1], headerHeight))
        }
        grid.addView(headRow, lp(gridWidth, headerHeight))

        values.forEachIndexed { rowIndex, row ->
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
            val leftRow = FrameLayout(env.context).apply {
                setBackgroundColor(env.colorSurface)
                addView(first, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
            left.addView(
                env.rowWithDivider(leftRow, env.colorSurface, rowIndex < values.lastIndex),
                lp(firstWidth, height),
            )

            val rightRow = LinearLayout(env.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(env.colorSurface)
            }
            cells.forEachIndexed { index, cell ->
                rightRow.addView(cell, lp(widths[index + 1], height))
            }
            grid.addView(
                env.rowWithDivider(rightRow, env.colorSurface, rowIndex < values.lastIndex),
                lp(gridWidth, height),
            )
        }

        val scroll = HorizontalScrollView(env.context).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(grid, ViewGroup.LayoutParams(gridWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val table = LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, lp(firstWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        diagnosticsBody.addView(table, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun detailedColumnWidths(headers: List<String>, values: List<List<String>>, availableWidth: Int): IntArray {
        val maxRegularWidth = (availableWidth / 2).coerceAtLeast(dp(48))
        val normalPaint = TextView(env.context).apply { textSize = 14f }.paint
        val boldPaint = TextView(env.context).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }.paint

        return IntArray(headers.size) { column ->
            val headerWordWidth = headers[column].split(HEADER_WHITESPACE_REGEX)
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

    private fun detailedHeaderCell(text: String): TextView = TextView(env.context).apply {
        this.text = text
        textSize = 14f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(env.colorMuted)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun detailedValueCell(text: String, copyText: String, bold: Boolean, noWrap: Boolean): TextView = TextView(env.context).apply {
        this.text = text.ifBlank { env.context.getString(R.string.iptest_diag_not_obtained) }
        textSize = 14f
        maxLines = if (noWrap) 1 else 3
        if (noWrap) isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.TOP or Gravity.START
        setTextColor(env.colorText)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(6), dp(5), dp(6), dp(5))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { env.onCopy(copyText.ifBlank { this.text.toString() }) }
    }

    private fun detailedHeight(view: View, width: Int): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight
    }

    private fun addDiagnosticsTable(
        title: String,
        headers: List<String>,
        values: List<List<String>>,
        firstWidthDp: Int = 140,
        otherWidthDp: Int = 118,
    ) {
        val heading = TextView(env.context).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(env.colorText)
            setPadding(0, dp(10), 0, dp(6))
        }
        diagnosticsBody.addView(heading, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val firstWidth = dp(firstWidthDp)
        val otherWidth = dp(otherWidthDp)
        val left = LinearLayout(env.context).apply { orientation = LinearLayout.VERTICAL }
        val grid = LinearLayout(env.context).apply { orientation = LinearLayout.VERTICAL }
        val gridWidth = otherWidth * (headers.size - 1)

        left.addView(diagnosticCell(headers.first(), env.colorSurface, true), lp(firstWidth, dp(42)))

        val headRow = LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(env.colorSurface)
        }
        headers.drop(1).forEach { header ->
            headRow.addView(diagnosticCell(header, env.colorSurface, true), lp(otherWidth, dp(42)))
        }
        grid.addView(headRow, lp(gridWidth, dp(42)))

        values.forEachIndexed { index, row ->
            val stripe = if (index % 2 == 1) env.colorStripe else Color.TRANSPARENT
            val first = diagnosticCell(row.firstOrNull().orEmpty(), stripe, true)
            val cells = row.drop(1).map { diagnosticCell(it, stripe, false) }
            val leftHeight = diagnosticHeight(first, firstWidth)
            val rightHeight = cells.maxOfOrNull { diagnosticHeight(it, otherWidth) } ?: dp(42)
            val height = maxOf(dp(42), leftHeight, rightHeight)

            left.addView(first, lp(firstWidth, height))
            val line = LinearLayout(env.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(stripe)
            }
            cells.forEach { line.addView(it, lp(otherWidth, height)) }
            grid.addView(line, lp(gridWidth, height))
        }

        val scroll = HorizontalScrollView(env.context).apply {
            isHorizontalScrollBarEnabled = true
            addView(grid, ViewGroup.LayoutParams(gridWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val table = LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, lp(firstWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        diagnosticsBody.addView(table, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun extFailureText(result: IpTestExtResult): CharSequence {
        val reason = result.reason.ifBlank { env.context.getString(R.string.iptest_ext_failed) }
        return extText(extShortReason(env.context, reason), env.colorBad)
    }

    private fun extWsSummary(result: IpTestExtResult): CharSequence {
        if (!result.tested) return extText(env.context.getString(R.string.iptest_diag_dash), env.colorMuted)
        if (result.status == 101 || (result.ok && result.status == 0)) {
            return extText(env.context.getString(R.string.iptest_ext_ws_upgraded), env.colorGood)
        }
        if (result.status in 200..399) {
            return extText(env.context.getString(R.string.iptest_ext_no_upgrade, result.status), env.colorMuted)
        }
        if (result.status > 0) return extText(result.status.toString(), env.colorBad)
        return extFailureText(result)
    }

    private fun extH3Summary(result: IpTestExtResult): CharSequence {
        if (!result.tested) return extText(env.context.getString(R.string.iptest_diag_dash), env.colorMuted)
        if (result.status > 0) {
            val text = if (result.ms > 0) env.context.getString(R.string.iptest_ext_status_ms, result.status.toString(), result.ms) else result.status.toString()
            return extText(text, if (result.status in 200..399) env.colorGood else env.colorBad)
        }
        return extFailureText(result)
    }

    private fun extMtuSummary(ip: String, result: IpTestExtResult): CharSequence {
        if (!result.tested) return extText(env.context.getString(R.string.iptest_diag_dash), env.colorMuted)

        val sb = SpannableStringBuilder()

        if (result.tcpMtu > 0) {
            add(sb, env.context.getString(R.string.iptest_ext_tcp_short, result.tcpMtu), env.colorGood)
        } else {
            add(sb, env.context.getString(R.string.iptest_ext_tcp_none), env.colorMuted)
        }

        sb.append('\n')

        val overhead = if (ip.contains(':')) 48 else 28

        when {
            result.udpMtu > 0 -> {
                val format = if (result.detail.contains("UDP upper bound reached")) R.string.iptest_ext_udp_mtu_at_least else R.string.iptest_ext_udp_mtu
                add(sb, env.context.getString(format, result.udpMtu + overhead), env.colorGood)
            }
            result.udpNoResponse -> add(sb, env.context.getString(R.string.iptest_ext_udp_none), env.colorMuted)
            result.reason.isNotBlank() -> add(sb, extShortReason(env.context, result.reason), env.colorBad)
            else -> add(sb, env.context.getString(R.string.iptest_ext_udp_dash), env.colorMuted)
        }

        return sb
    }

    private fun addExtensionTable(title: String, headers: List<String>, rows: List<ExtRow>) {
        val heading = TextView(env.context).apply {
            val sb = SpannableStringBuilder()
            add(sb, title, bold = true)
            add(sb, "  " + env.context.getString(R.string.iptest_ext_tap_hint), env.colorMuted)
            sb.setSpan(android.text.style.AbsoluteSizeSpan(12, true), title.length, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
            textSize = 14f
            setTextColor(env.colorText)
            setPadding(0, dp(10), 0, dp(6))
        }
        diagnosticsBody.addView(heading, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val firstWidth = dp(124)

        fun cell(text: CharSequence, bold: Boolean, textColor: Int, size: Float): TextView = TextView(env.context).apply {
            this.text = text
            textSize = size
            setTextColor(textColor)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(6), dp(5), dp(6), dp(5))
            minHeight = dp(42)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        fun row(background: Int, cells: List<TextView>): LinearLayout = LinearLayout(env.context).apply {
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

        val table = LinearLayout(env.context).apply { orientation = LinearLayout.VERTICAL }

        table.addView(
            row(env.colorSurface, headers.map { cell(it, true, env.colorText, 14f) }),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        rows.forEachIndexed { index, r ->
            val stripe = if (index % 2 == 1) env.colorStripe else Color.TRANSPARENT
            val views = ArrayList<TextView>()

            views += cell(r.ip, true, env.colorText, 14f)

            for (c in r.cells) {
                views += cell(c.text, false, env.colorText, 13f).apply {
                    if (c.detail.isNotBlank()) setOnClickListener { env.onDetail(c.title, c.detail) }
                }
            }

            table.addView(row(stripe, views), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        diagnosticsBody.addView(table, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun diagnosticCell(text: String, backgroundColor: Int, bold: Boolean): TextView {
        return TextView(env.context).apply {
            this.text = text.ifBlank { env.context.getString(R.string.iptest_diag_not_obtained) }
            textSize = 14f
            setTextColor(env.colorText)
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
}
