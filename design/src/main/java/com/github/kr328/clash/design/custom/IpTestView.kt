package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #43, not upstream): the page of "test other IPs". Quickest address first, the ones that failed
// last. A tap on a line copies that address; the last line copies every address that works. Nothing here runs on
// its own: it only lays out the report that one tap fetched. plain() is the same text without the copy line, it is
// what the copy button at the top gets.
class IpTestView(
    private val context: Context,
    private val report: IpTestReport,
    private val onCopy: (String) -> Unit,
    private val onLookup: ((String) -> Unit)? = null, // a tap on "look up" of a row (CUSTOM.md #43)
) {
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val colorBad = if (night) Color.parseColor("#EF9A9A") else Color.parseColor("#C62828")
    private val colorWarn = if (night) Color.parseColor("#FFB74D") else Color.parseColor("#E65100")
    private val colorGood = if (night) Color.parseColor("#81C784") else Color.parseColor("#2E7D32")
    private val colorLink = if (night) Color.parseColor("#90CAF9") else Color.parseColor("#1565C0")

    // the small grey lines (ECH source, TLS / operator details, hints, time): dark enough to read on a light page
    private val colorMuted = if (night) Color.parseColor("#BDBDBD") else Color.parseColor("#616161")

    private val direct = report.mode == "direct"

    fun render(): CharSequence {
        return compose(true)
    }

    fun plain(): String {
        return compose(false).toString()
    }

    private fun compose(links: Boolean): SpannableStringBuilder {
        val sb = SpannableStringBuilder()

        if (direct) {
            add(sb, context.getString(R.string.iptest_head_direct, report.domain) + "\n", bold = true)

            if (report.echSource.isNotEmpty()) {
                add(sb, context.getString(R.string.iptest_ech_source, report.echSource) + "\n", colorMuted)
            } else {
                add(sb, context.getString(R.string.iptest_ech_none) + "\n", colorMuted)
            }
        } else {
            add(sb, context.getString(R.string.iptest_head_node, report.node) + "\n", bold = true)

            val ref = report.ref

            if (ref != null) {
                add(
                    sb,
                    context.getString(R.string.iptest_ref, ref.ip, ref.ms, AgeText.testedAgo(context, ref.at)) + "\n",
                    colorMuted,
                )
            } else {
                add(sb, context.getString(R.string.iptest_ref_none) + "\n", colorMuted)
            }
        }

        if (report.cancelled) add(sb, context.getString(R.string.iptest_cancelled) + "\n", colorWarn)

        if (report.skipped.isNotEmpty()) {
            add(sb, context.getString(R.string.iptest_skipped, report.skipped.joinToString("、")) + "\n", colorWarn)
        }

        if (report.invalid.isNotEmpty()) {
            add(sb, context.getString(R.string.iptest_invalid, report.invalid.joinToString("、")) + "\n", colorWarn)
        }

        if (report.cut > 0) add(sb, context.getString(R.string.iptest_cut, report.cut) + "\n", colorWarn)

        sb.append('\n')

        if (report.rows.isEmpty()) return sb

        if (links) add(sb, context.getString(R.string.iptest_tap_hint) + "\n\n", colorMuted)

        for (r in report.rows) row(sb, r, links)

        sb.append('\n')

        val works = report.rows.filter { it.ok }.map { it.ip }

        if (works.isEmpty()) {
            add(sb, context.getString(R.string.iptest_none_ok) + "\n", colorBad)
        } else if (links) {
            val start = sb.length

            add(sb, context.getString(R.string.iptest_copy_all, works.size) + "\n", bold = true)
            sb.setSpan(click { onCopy(works.joinToString("\n")) }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (report.at > 0) {
            add(sb, "\n" + context.getString(R.string.iptest_tested_at, AgeText.testedAgo(context, report.at)) + "\n", colorMuted)
        }

        return sb
    }

    private fun row(sb: SpannableStringBuilder, r: IpTestRow, links: Boolean) {
        val start = sb.length

        add(sb, if (r.ok) "✓ " else "✗ ", if (r.ok) colorGood else colorBad)

        if (!direct && r.ok) add(sb, CountryFlag.of(r.country).ifEmpty { "？" } + " ")

        add(sb, r.ip, bold = true)

        if (r.ok) {
            add(sb, " · " + context.getString(R.string.iptest_ms, r.ms))

            if (r.faster > 0) add(sb, " · " + context.getString(R.string.iptest_faster, r.faster), colorGood)
        } else {
            add(sb, " · " + failure(r), colorBad)
        }

        if (r.inHosts) {
            add(sb, " · " + context.getString(if (direct) R.string.iptest_in_config else R.string.iptest_in_hosts), colorMuted)
        }

        sb.append('\n')

        if (links) sb.setSpan(click { onCopy(r.ip) }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        if (direct && r.ok) {
            val parts = ArrayList<String>()

            if (r.tls.isNotEmpty()) parts += context.getString(R.string.iptest_tls, r.tls)
            if (r.alpn.isNotEmpty()) parts += r.alpn

            when (r.ech) {
                "accepted" -> parts += context.getString(R.string.iptest_ech_accepted)
                "notaccepted" -> parts += context.getString(R.string.iptest_ech_notaccepted)
                "rejected" -> parts += context.getString(R.string.iptest_ech_rejected)
            }

            if (r.cf) parts += context.getString(R.string.iptest_cf)
            if (r.colo.isNotEmpty()) parts += context.getString(R.string.iptest_colo, r.colo)
            if (r.asn.isNotEmpty()) parts += r.asn

            // the operator is asked only when this is tapped (it needs the network, and the public services limit it)
            val lookup = links && r.asn.isEmpty() && onLookup != null

            if (parts.isNotEmpty() || lookup) {
                add(sb, "    " + parts.joinToString(" · "), colorMuted)

                if (lookup) {
                    if (parts.isNotEmpty()) add(sb, " · ", colorMuted)

                    val from = sb.length

                    add(sb, context.getString(R.string.iptest_lookup), colorLink)
                    sb.setSpan(click { onLookup?.invoke(r.ip) }, from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                sb.append('\n')
            }
        }
    }

    // the core's own wording carries local addresses and ports, so it is shown only when the cause is not recognised
    private fun failure(r: IpTestRow): String {
        val explain = context.getString(
            when (r.code) {
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
                    ErrorsText.explain(context, r.code),
                    r.reason.ifEmpty { "-" },
                )
            },
        )

        return context.getString(R.string.check_failed, explain)
    }

    private fun add(sb: SpannableStringBuilder, text: String, color: Int? = null, bold: Boolean = false) {
        val start = sb.length

        sb.append(text)

        if (color != null) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun click(action: () -> Unit): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) {
                action()
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.isUnderlineText = false
            }
        }
    }
}
