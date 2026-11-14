package com.github.kr328.clash.design.custom

import android.content.Context
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
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CUSTOM (CUSTOM.md #52, not upstream; replaces the text page of #43): the page of "test other IPs" as a table like
// the other apps have it, with the live status under it. The IP column stays on the left while the rest scrolls
// sideways. Everything here is laid out again from the rows it is given (at most 20); nothing runs on its own, the
// activity gives it one more row after each address and one more line after each event.
class IpTestView(private val context: Context, private val direct: Boolean) {
    var onCopy: (String) -> Unit = {}
    var onSpeed: (String) -> Unit = {} // a tap on "测速" of a row
    var onSpeedCancel: () -> Unit = {} // a tap on "取消" of the row that is downloading
    var onDetail: (String, String) -> Unit = { _, _ -> } // a tap on the ports of a row: title, text

    private enum class Key { COLO, ASN, PORTS, LOSS, MIN, MAX, AVG, EXIT, PORT, FASTER, TEST, SPEED }

    private val keys = if (direct) {
        listOf(Key.COLO, Key.ASN, Key.PORTS, Key.LOSS, Key.MIN, Key.MAX, Key.AVG, Key.TEST, Key.SPEED)
    } else {
        listOf(Key.EXIT, Key.PORT, Key.LOSS, Key.MIN, Key.MAX, Key.AVG, Key.FASTER, Key.TEST, Key.SPEED)
    }

    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val colorBad = if (night) Color.parseColor("#EF9A9A") else Color.parseColor("#C62828")
    private val colorWarn = if (night) Color.parseColor("#FFB74D") else Color.parseColor("#E65100")
    private val colorGood = if (night) Color.parseColor("#81C784") else Color.parseColor("#2E7D32")
    private val colorLink = if (night) Color.parseColor("#90CAF9") else Color.parseColor("#1565C0")
    private val colorMuted = if (night) Color.parseColor("#BDBDBD") else Color.parseColor("#616161")
    private val colorText = themeColor(android.R.attr.textColorPrimary, if (night) Color.WHITE else Color.BLACK)

    private val rowH = dp(52)
    private val headH = dp(38)
    private val ipW = dp(150)

    private var rows: List<IpTestRow> = emptyList()
    private var colos: Map<String, String> = emptyMap()
    private var speedIp: String? = null
    private var testing = false

    private var infoText: CharSequence = ""
    private val logLines = ArrayList<String>()

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val info = TextView(context).apply {
        textSize = 13f
        setPadding(0, 0, 0, dp(8))
    }

    private val tableBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val hint = TextView(context).apply {
        setText(R.string.iptest_table_hint)
        textSize = 11f
        setTextColor(colorMuted)
        setPadding(0, dp(6), 0, dp(10))
    }

    private val logTitle = TextView(context).apply {
        setText(R.string.iptest_log_title)
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(4))
    }

    private val logView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextColor(colorText)
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private val logScroll = ScrollView(context).apply {
        // the log has its own colour that follows the app theme (light or dark), not a fixed dark one
        background = GradientDrawable().apply {
            setColor(if (night) Color.parseColor("#232323") else Color.parseColor("#F1F1F1"))
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
        root.addView(info, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tableBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(hint, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

    fun setColos(map: Map<String, String>) {
        colos = map
        redraw()
    }

    fun setRows(list: List<IpTestRow>) {
        rows = sorted(list)
        redraw()
    }

    /** [testing]: the round is running (no download then); [ip]: the row whose download is running. */
    fun setBusy(testing: Boolean, ip: String?) {
        this.testing = testing
        speedIp = ip
        redraw()
    }

    /** One line of the live status, `[HH:MM:SS] text`; at most 200 lines are kept. */
    fun log(text: String) {
        logLines += "[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "] " + text

        while (logLines.size > MAX_LOG) logLines.removeAt(0)

        logView.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** The table and the log as text (what the copy button at the top of the page gets). */
    fun plain(): String {
        val sb = StringBuilder()

        sb.append(infoText).append('\n')
        sb.append(context.getString(R.string.iptest_col_ip))

        for (k in keys) sb.append('\t').append(title(k))

        sb.append('\n')

        for (r in rows) {
            sb.append(r.ip)

            for (k in keys) sb.append('\t').append(plainOf(k, r))

            sb.append('\n')
        }

        sb.append('\n').append(context.getString(R.string.iptest_log_title)).append('\n')
        sb.append(logLines.joinToString("\n"))

        return sb.toString()
    }

    // quickest average first, the ones that failed last (in the order they were tested)
    private fun sorted(list: List<IpTestRow>): List<IpTestRow> {
        return list.sortedWith(
            compareBy<IpTestRow> { if (it.ok) 0 else 1 }.thenBy { if (it.ok) it.avg else 0 },
        )
    }

    private fun redraw() {
        tableBox.removeAllViews()

        if (rows.isEmpty()) return

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        left.addView(headCell(context.getString(R.string.iptest_col_ip), ipW), lp(ipW, headH))

        val headRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        for (k in keys) headRow.addView(headCell(title(k), width(k)), lp(width(k), headH))

        grid.addView(headRow, lp(ViewGroup.LayoutParams.WRAP_CONTENT, headH))

        rows.forEachIndexed { i, r ->
            val stripe = if (i % 2 == 1) Color.parseColor("#14808080") else Color.TRANSPARENT

            left.addView(ipCell(r).apply { setBackgroundColor(stripe) }, lp(ipW, rowH))

            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(stripe)
            }

            for (k in keys) line.addView(cellOf(k, r), lp(width(k), rowH))

            grid.addView(line, lp(ViewGroup.LayoutParams.WRAP_CONTENT, rowH))
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        tableBox.addView(left, LinearLayout.LayoutParams(ipW, ViewGroup.LayoutParams.WRAP_CONTENT))
        tableBox.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun title(k: Key): String {
        return context.getString(
            when (k) {
                Key.COLO -> R.string.iptest_col_colo
                Key.ASN -> R.string.iptest_col_asn
                Key.PORTS, Key.PORT -> R.string.iptest_col_port
                Key.LOSS -> R.string.iptest_col_loss
                Key.MIN -> R.string.iptest_col_min
                Key.MAX -> R.string.iptest_col_max
                Key.AVG -> R.string.iptest_col_avg
                Key.EXIT -> R.string.iptest_col_exit
                Key.FASTER -> R.string.iptest_col_faster
                Key.TEST -> R.string.iptest_col_test
                Key.SPEED -> R.string.iptest_col_speed
            },
        )
    }

    private fun width(k: Key): Int {
        return dp(
            when (k) {
                Key.COLO -> 112
                Key.ASN -> 150
                Key.PORTS -> 130
                Key.PORT -> 64
                Key.LOSS -> 62
                Key.MIN, Key.MAX, Key.AVG -> 82
                Key.EXIT -> 60
                Key.FASTER -> 92
                Key.TEST -> 72
                Key.SPEED -> 84
            },
        )
    }

    // the colour of an average: under 150 ms green, under 300 orange, else red
    private fun latencyColor(ms: Int): Int = when {
        ms < 150 -> colorGood
        ms < 300 -> colorWarn
        else -> colorBad
    }

    private fun lossColor(loss: Int): Int = when {
        loss == 0 -> colorGood
        loss < 50 -> colorWarn
        else -> colorBad
    }

    private fun cityOf(code: String): String {
        if (code.isEmpty()) return "-"

        val city = colos[code.uppercase(Locale.US)]

        return if (city.isNullOrEmpty()) code else "$code\n$city"
    }

    private fun speedText(r: IpTestRow): String {
        return when {
            speedIp == r.ip -> "…"
            r.speed > 0 -> context.getString(R.string.iptest_speed_cell, String.format(Locale.US, "%.1f", r.speed))
            r.speedErr.isNotEmpty() -> context.getString(R.string.iptest_speed_failed)
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
            Key.COLO -> cityOf(r.colo).replace('\n', ' ')
            Key.ASN -> r.asn.ifEmpty { "-" }
            Key.PORTS -> portsPlain(r)
            Key.PORT -> r.port.ifEmpty { "-" }
            Key.LOSS -> "${r.loss}%"
            Key.MIN -> ms(r.min)
            Key.MAX -> ms(r.max)
            Key.AVG -> ms(r.avg)
            Key.EXIT -> if (tested) CountryFlag.of(r.country).ifEmpty { "？" } else "-"
            Key.FASTER -> if (r.faster > 0) context.getString(R.string.iptest_faster_cell, r.faster) else "-"
            Key.TEST -> ""
            Key.SPEED -> speedText(r)
        }
    }

    private fun cellOf(k: Key, r: IpTestRow): View {
        return when (k) {
            Key.PORTS -> portsCell(r)
            Key.TEST -> testCell(r)
            else -> {
                val color = when (k) {
                    Key.LOSS -> lossColor(r.loss)
                    Key.AVG -> if (r.ok) latencyColor(r.avg) else colorMuted
                    Key.FASTER -> if (r.faster > 0) colorGood else colorMuted
                    Key.SPEED -> if (r.speed > 0) colorText else if (r.speedErr.isNotEmpty()) colorBad else colorMuted
                    Key.COLO, Key.ASN, Key.PORT -> colorText
                    else -> colorText
                }

                textCell(plainOf(k, r), color, bold = k == Key.AVG)
            }
        }
    }

    private fun textCell(text: String, color: Int, bold: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color)
            setPadding(dp(4), 0, dp(4), 0)

            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun headCell(text: String, w: Int): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorMuted)
            setBackgroundColor(Color.parseColor("#22808080"))
            minWidth = w
            setPadding(dp(4), 0, dp(4), 0)
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

            add(sb, (if (p.ok) "" else "✗") + p.port, if (p.ok) colorGood else colorMuted)
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

    // "测速" of a row that works; "取消" on the row that is downloading; the others are grey while one is
    private fun testCell(r: IpTestRow): View {
        val box = LinearLayout(context).apply { gravity = Gravity.CENTER }

        if (!r.ok) return box

        val running = speedIp == r.ip
        val enabled = !testing && (speedIp == null || running)

        val button = TextView(context).apply {
            text = context.getString(if (running) R.string.check_cancel else R.string.iptest_btn_speed)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(colorLink)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            alpha = if (enabled) 1f else 0.35f
            background = GradientDrawable().apply {
                setStroke(dp(1), colorLink)
                cornerRadius = dp(5).toFloat()
            }

            if (enabled) {
                isClickable = true
                setOnClickListener { if (running) onSpeedCancel() else onSpeed(r.ip) }
            }
        }

        box.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return box
    }

    // the IP (a tap copies it, dashed line under it) and under it, small and grey, what the handshake showed
    private fun ipCell(r: IpTestRow): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }

        val ip = DashedTextView(context).apply {
            text = r.ip
            textSize = 13f
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
                textSize = 10f
                maxLines = 2
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
