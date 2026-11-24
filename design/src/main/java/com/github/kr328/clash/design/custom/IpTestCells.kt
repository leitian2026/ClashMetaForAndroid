package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.design.R

internal interface IpTestEnv {
    val context: Context
    val direct: Boolean
    val target: Boolean
    var rows: List<IpTestRow>
    var colos: Map<String, String>
    var coloCountries: Map<String, String>
    val speedIp: String?
    val testing: Boolean
    var activeTestSpeedButton: ProgressMaterialButton?
    val downloadProgressPercent: Int?
    val ownerLookupCells: MutableMap<String, TextView>
    val ownerLookupIp: String?
    val recommendedIps: Set<String>
    val onCopy: (String) -> Unit
    val onDetail: (String, String) -> Unit
    val onSpeed: (String) -> Unit
    val onSpeedCancel: () -> Unit
    val colorText: Int
    val colorMuted: Int
    val colorGood: Int
    val colorWarn: Int
    val colorBad: Int
    val colorLink: Int
    val colorSurface: Int
    val night: Boolean
    val showDirectSpeed: Boolean
    val rowH: Int
    val headH: Int
    val colorStripe: Int
    val colorTableDivider: Int
    val dnsReport: DnsCheckReport?
    fun rowWithDivider(content: View, backgroundColor: Int, showDivider: Boolean): FrameLayout
    val keys: List<IpTestView.Key>
    val savedTable: Boolean
    var savedTableAvailableWidth: Int
    val tableBox: LinearLayout
    fun speedButtonTextRes(): Int
    fun speedButtonMinHeight(): Int
}

internal class IpTestCells(private val env: IpTestEnv) {
    private fun dp(v: Int): Int = (v * env.context.resources.displayMetrics.density + 0.5f).toInt()

    fun plainOf(k: IpTestView.Key, r: IpTestRow): String {
        val tested = r.ok
        val ms = { v: Int -> if (tested) env.context.getString(R.string.iptest_ms_short, v) else "-" }

        return when (k) {
            IpTestView.Key.COLO -> coloLabel(r.colo, env.colos, env.coloCountries)
            IpTestView.Key.ASN -> r.asn.ifEmpty { "-" }
            IpTestView.Key.GEO -> if (tested) CountryName.label(r.geo) else "-"
            IpTestView.Key.LAND -> if (tested) CountryName.label(r.country) else "-"
            IpTestView.Key.PORTS -> portsPlain(r)
            IpTestView.Key.PORT -> "-"
            IpTestView.Key.LOSS -> "${r.loss}%"
            IpTestView.Key.MIN -> ms(r.min)
            IpTestView.Key.MAX -> ms(r.max)
            IpTestView.Key.AVG -> ms(r.avg)
            IpTestView.Key.JITTER -> if (tested) env.context.getString(R.string.iptest_ms_short, r.jitter) else "-"
            IpTestView.Key.PHASE -> phaseText(env.context, r, env.direct)
            IpTestView.Key.EXIT -> if (tested) CountryName.label(r.country).let { if (it == "-") "？" else it } else "-"
            IpTestView.Key.EXIT_IP -> if (tested && r.exitIp.isNotEmpty()) r.exitIp + "\n" + r.exitAsn.ifEmpty { "-" } else "-"
            IpTestView.Key.TARGET -> targetText(env.context, r)
            IpTestView.Key.TCOLO -> coloLabel(r.targetColo, env.colos, env.coloCountries)
            IpTestView.Key.TEST -> ""
            IpTestView.Key.SPEED -> speedText(env.context, r, env.speedIp)
            IpTestView.Key.SPEED_NODE -> "-"
        }
    }

    fun ownerCell(r: IpTestRow): View {
        return textCell(ownerCellText(r), env.colorText).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            env.ownerLookupCells[r.ip] = this
        }
    }

    fun cellOf(k: IpTestView.Key, r: IpTestRow, w: Int): View {
        return when (k) {
            IpTestView.Key.PORTS -> portsCell(r)
            IpTestView.Key.COLO -> coloCell(r, w)
            IpTestView.Key.TCOLO -> coloCell(r, w, r.targetColo)
            IpTestView.Key.TEST -> testCell(r)
            IpTestView.Key.ASN -> ownerCell(r)
            IpTestView.Key.TARGET -> targetCell(r)
            else -> {
                val color = when (k) {
                    IpTestView.Key.LOSS -> lossColor(r.loss, env.colorGood, env.colorWarn, env.colorBad)
                    IpTestView.Key.AVG -> if (r.ok) latencyColor(r.avg, env.colorGood, env.colorWarn, env.colorBad) else env.colorMuted
                    IpTestView.Key.JITTER -> if (r.ok) jitterColor(r.jitter, env.colorGood, env.colorWarn, env.colorBad) else env.colorMuted
                    IpTestView.Key.TARGET -> targetColor(r, env.colorBad, env.colorMuted, env.colorWarn, env.colorGood)
                    IpTestView.Key.SPEED -> if (r.speed > 0) env.colorText else if (r.speedErr.isNotEmpty()) env.colorBad else env.colorMuted
                    IpTestView.Key.ASN, IpTestView.Key.PORT -> env.colorText
                    else -> env.colorText
                }

                textCell(plainOf(k, r), color, bold = k == IpTestView.Key.AVG)
            }
        }
    }

    fun targetCell(r: IpTestRow): View {
        val full = targetText(env.context, r)
        val display = if (!r.ok && r.reason.length > 40) {
            handshakeFailureText(env.context, r.reason.take(40)) + "…"
        } else full
        return textCell(display, targetColor(r, env.colorBad, env.colorMuted, env.colorWarn, env.colorGood)).apply {
            if (!r.ok) setOnClickListener {
                env.onCopy(handshakeFailureText(env.context, r.reason.ifBlank { r.code.ifBlank { "-" } }))
            }
        }
    }

    fun coloCell(r: IpTestRow, w: Int, code: String = r.colo): View {
        val one = coloLabel(code, env.colos, env.coloCountries)
        val cell = textCell(one, env.colorText)

        if (code.isNotEmpty() && cell.paint.measureText(one) > w - dp(8)) {
            val tail = coloTail(code, env.colos)

            if (tail.isNotEmpty()) cell.text = coloHead(code, env.coloCountries) + "\n" + tail
        }

        return cell
    }

    fun textCell(text: String, color: Int, bold: Boolean = false): TextView {
        return TextView(env.context).apply {
            this.text = text
            textSize = 14f
            // CUSTOM.md #55 (r67): the first line of every cell sits on the same line as the IP (top aligned), not
            // in the middle of the row, so a cell with many lines (the ports) is not pushed down and cut at the bottom
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setTextColor(color)
            setPadding(dp(4), dp(5), dp(4), dp(5))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END

            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    fun headCell(text: String, w: Int): TextView {
        return TextView(env.context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(env.colorMuted)
            setBackgroundColor(env.colorSurface)
            minWidth = w
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
    }

    fun portsCell(r: IpTestRow): View {
        val cell = textCell("", env.colorText)

        if (r.ports.isEmpty()) {
            cell.text = "-"
            cell.setTextColor(env.colorMuted)

            return cell
        }

        val sb = SpannableStringBuilder()

        for (p in r.ports) {
            if (sb.isNotEmpty()) sb.append(' ')

            val from = sb.length

            add(sb, (if (p.ok) "" else "✗") + p.port, if (p.ok) env.colorGood else env.colorMuted)
            CheckMark.apply(env.context, sb, from, sb.length) // CUSTOM.md #57: the ✗ in red
        }

        cell.text = sb
        cell.setOnClickListener { env.onDetail(env.context.getString(R.string.iptest_port_title, r.ip), portDetail(env.context, r)) }

        return cell
    }

    fun testCell(r: IpTestRow): View {
        val box = LinearLayout(env.context).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL // CUSTOM.md #55 (r67)
            setPadding(0, dp(2), 0, 0)
        }

        if (!r.ok) return box

        val running = env.speedIp == r.ip
        val enabled = !env.testing && (env.speedIp == null || running)

        val button = ProgressMaterialButton(env.context, env.colorLink).apply {
            text = if (running && env.downloadProgressPercent != null) {
                env.context.getString(R.string.check_cancel) + "\n" + env.downloadProgressPercent + "%"
            } else env.context.getString(if (running) R.string.check_cancel else env.speedButtonTextRes())
            progressLevel = if (running) (env.downloadProgressPercent ?: 0) * 100 else 0
            textSize = 13f
            isAllCaps = false
            gravity = Gravity.CENTER
            maxLines = 2
            minWidth = 0
            minHeight = env.speedButtonMinHeight()
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(5)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(env.colorLink)
            setTextColor(env.colorLink)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f

            if (enabled) setOnClickListener { if (running) env.onSpeedCancel() else env.onSpeed(r.ip) }
        }

        if (running) env.activeTestSpeedButton = button
        box.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return box
    }

    fun ipCell(
        r: IpTestRow,
        showRecommendation: Boolean = true,
        showFailureDetail: Boolean = true,
    ): View {
        val box = LinearLayout(env.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP // CUSTOM.md #55 (r67): same top padding as the other cells, so the lines match
            setPadding(dp(8), dp(5), dp(4), dp(5))
        }

        val ip = DashedTextView(env.context).apply {
            text = r.ip
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.TOP or Gravity.START
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (r.ok) env.colorText else env.colorBad)
            setOnClickListener { env.onCopy(r.ip) }
        }

        box.addView(ip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (showRecommendation && r.ip in env.recommendedIps) {
            box.addView(
                TextView(env.context).apply {
                    text = "推荐"
                    textSize = 13f
                    setTextColor(env.colorWarn)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        val small = if (!r.ok && !showFailureDetail) "" else ipDetails(r)

        if (small.isNotEmpty()) {
            val t = TextView(env.context).apply {
                text = small
                textSize = 12f
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (r.ok) env.colorMuted else env.colorBad)
            }

            box.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        return box
    }

    fun ipDetails(r: IpTestRow): String {
        if (!r.ok) {
            return if (r.sent > 0 || r.reason.isNotEmpty()) handshakeFailureText(env.context, r.reason.ifBlank { r.code.ifBlank { "-" } }) else ""
        }

        val parts = ArrayList<String>()

        if (env.direct) {
            if (r.tls.isNotEmpty()) parts += env.context.getString(R.string.iptest_tls, r.tls)
            if (r.alpn.isNotEmpty()) parts += r.alpn

            when (r.ech) {
                "accepted" -> parts += env.context.getString(R.string.iptest_ech_accepted)
                "notaccepted" -> parts += env.context.getString(R.string.iptest_ech_notaccepted)
                "rejected" -> parts += env.context.getString(R.string.iptest_ech_rejected)
            }

            if (r.cf) parts += env.context.getString(R.string.iptest_cf)
        }

        if (r.inHosts) parts += env.context.getString(if (env.direct) R.string.iptest_in_config else R.string.iptest_in_hosts)

        return parts.joinToString(" · ")
    }

    fun ownerCellText(r: IpTestRow): String = when {
        r.asn.isNotEmpty() -> r.asn
        r.isDomain || !r.ok -> "-"
        env.ownerLookupIp == r.ip -> "查询中…"
        else -> "-"
    }

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
}
