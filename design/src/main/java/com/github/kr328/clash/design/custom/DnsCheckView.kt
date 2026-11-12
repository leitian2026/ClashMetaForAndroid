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
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.core.model.DnsCheckServer
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #26, r32): the page of the DNS check, shorter to read than the old plain list:
//  - the first lines are the result in words (is the answer the core really uses the same as the encrypted
//    DNS's?), what is wrong, and whether it matters;
//  - the servers are in two groups, the ones the core really asks (nameserver, fallback, nameserver-policy) and
//    the ones that only serve a special purpose (direct-nameserver, default-nameserver, ...), one line each:
//    the result in a word and the time. A tap on a line opens its addresses and details;
//  - everything else (what an app gets, the core's resolver, nameserver-policy, the notes) is in one fold that
//    is closed until tapped.
// Nothing here runs on its own: it only lays out the report that one tap on "DNS check" fetched. plain() is the
// same text with everything open; it is what the copy button and the diagnostic bundle get.
class DnsCheckView(
    private val context: Context,
    private val report: DnsCheckReport,
) {
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val colorBad = if (night) Color.parseColor("#EF9A9A") else Color.parseColor("#C62828")
    private val colorWarn = if (night) Color.parseColor("#FFB74D") else Color.parseColor("#E65100")
    private val colorGood = if (night) Color.parseColor("#81C784") else Color.parseColor("#2E7D32")
    private val colorMuted = Color.parseColor("#9E9E9E")

    private val flags = report.verdict.flags.associateBy { it.address }
    private val open = HashSet<String>() // addresses of the servers whose details are shown
    private var detailsOpen = false

    // the sections whose servers the core really asks; the others serve one purpose only
    private fun isPrimary(s: DnsCheckServer): Boolean {
        return s.roles.any { it == "nameserver" || it == "fallback" || it == "nameserver-policy" }
    }

    /** The page. [onChange] is called after a tap changed what is shown; draw again then. */
    fun render(onChange: () -> Unit): CharSequence {
        return compose(false, onChange)
    }

    /** The whole text with everything open (what the copy button and the diagnostic bundle get). */
    fun plain(): String {
        return compose(true, null).toString()
    }

    private fun compose(all: Boolean, onChange: (() -> Unit)?): SpannableStringBuilder {
        val sb = SpannableStringBuilder()

        add(sb, context.getString(R.string.check_dns_section, report.domain) + "\n\n", bold = true)

        if (report.aliasTo.isNotEmpty()) {
            add(sb, context.getString(R.string.check_dns_alias, report.domain, report.aliasTo) + "\n")
        }

        conclusion(sb)
        sb.append('\n')

        if (report.servers.isEmpty()) {
            add(sb, context.getString(R.string.check_dns_no_servers) + "\n\n")
        } else {
            if (!all) add(sb, context.getString(R.string.check_dns_tap_hint) + "\n\n", colorMuted)

            group(sb, R.string.check_dns_g_primary, report.servers.filter { isPrimary(it) }, all, onChange)
            group(sb, R.string.check_dns_g_secondary, report.servers.filter { !isPrimary(it) }, all, onChange)
        }

        fold(sb, all, onChange)

        return sb
    }

    // the result in words, then what is wrong and whether it matters
    private fun conclusion(sb: SpannableStringBuilder) {
        val v = report.verdict

        if (v.kind == "nxdomain_all") {
            DnsCheckText.verdict(context, report)?.let { add(sb, it + "\n", bold = true) }

            return
        }

        val c = report.core
        val answered = c.asked && c.ok && c.rname != "NXDOMAIN"
        val matched = answered && c.matches.isNotEmpty()

        val lead = when {
            matched -> context.getString(R.string.check_dns_c_ok, c.matches.size)
            answered -> context.getString(R.string.check_dns_c_nomatch)
            v.kind == "consistent" -> context.getString(R.string.check_dns_v_consistent)
            v.kind == "single" -> context.getString(R.string.check_dns_v_single)
            v.kind == "polluted" -> context.getString(R.string.check_dns_c_polluted)
            v.kind == "suspect" -> context.getString(R.string.check_dns_c_suspect)
            else -> context.getString(R.string.check_dns_c_none)
        }

        add(sb, lead + "\n", bold = true)

        if (answered) {
            val none = context.getString(R.string.check_dns_none)

            add(
                sb,
                context.getString(
                    R.string.check_dns_c_core_line,
                    c.a.joinToString(", ").ifEmpty { none },
                    c.aaaa.joinToString(", ").ifEmpty { none },
                    c.ms,
                ) + "\n",
            )
        }

        val flagged = report.servers.filter { flags.containsKey(it.address) }
        val inUse = flagged.count { isPrimary(it) }
        val special = flagged.size - inUse

        if (inUse > 0) add(sb, context.getString(R.string.check_dns_c_flag_primary, inUse) + "\n", colorBad)
        if (special > 0) add(sb, context.getString(R.string.check_dns_c_flag_secondary, special) + "\n", colorWarn)

        if (v.trusted.isNotEmpty()) {
            add(sb, context.getString(R.string.check_dns_v_trusted, v.trusted.joinToString(", ")) + "\n", colorMuted)
        }
    }

    private fun group(
        sb: SpannableStringBuilder,
        title: Int,
        list: List<DnsCheckServer>,
        all: Boolean,
        onChange: (() -> Unit)?,
    ) {
        if (list.isEmpty()) return

        add(sb, context.getString(title) + "\n", bold = true)

        for (s in list) row(sb, s, all, onChange)

        sb.append('\n')
    }

    private fun row(sb: SpannableStringBuilder, s: DnsCheckServer, all: Boolean, onChange: (() -> Unit)?) {
        val start = sb.length
        val flagged = flags.containsKey(s.address)

        val mark = when {
            s.kind == "unsupported" -> "·"
            !s.ok -> "✗"
            flagged -> "⚠"
            s.rname == "NXDOMAIN" -> "·"
            else -> "✓"
        }

        add(sb, "  $mark ${s.address}")

        if (!isPrimary(s)) add(sb, " [${s.roles.joinToString(", ")}]", colorMuted)

        sb.append("\n    ")

        val (word, color) = result(s)

        add(sb, word, color, bold = true)

        if (s.ok && s.ms > 0) add(sb, " · " + context.getString(R.string.check_dns_r_ms, s.ms), colorMuted)

        sb.append('\n')

        if (onChange != null && !all) {
            sb.setSpan(
                click {
                    if (!open.remove(s.address)) open.add(s.address)

                    onChange()
                },
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        if (all || open.contains(s.address)) {
            val detail = DnsCheckText.serverDetail(context, s, flags[s.address])

            for (line in detail.lines()) {
                if (line.isBlank()) continue

                add(sb, "    " + line.trimStart() + "\n", colorMuted)
            }
        }
    }

    private fun result(s: DnsCheckServer): Pair<String, Int> {
        val flag = flags[s.address]

        return when {
            s.kind == "unsupported" -> context.getString(R.string.check_dns_r_unsupported) to colorMuted
            !s.ok -> context.getString(R.string.check_dns_r_none) to colorBad
            flag != null && flag.level == "likely" -> context.getString(R.string.check_dns_r_likely) to colorBad
            flag != null -> context.getString(R.string.check_dns_r_suspect) to colorWarn
            s.rname == "NXDOMAIN" -> context.getString(R.string.check_dns_r_nx) to colorMuted
            report.core.matches.contains(s.address) -> context.getString(R.string.check_dns_r_same) to colorGood
            else -> context.getString(R.string.check_dns_r_ok) to colorGood
        }
    }

    // what an app gets, the core's own resolver, nameserver-policy and the notes: closed until tapped
    private fun fold(sb: SpannableStringBuilder, all: Boolean, onChange: (() -> Unit)?) {
        val shown = all || detailsOpen

        val head = when {
            all -> R.string.check_dns_fold_plain
            detailsOpen -> R.string.check_dns_fold_open
            else -> R.string.check_dns_fold_closed
        }

        val start = sb.length

        add(sb, context.getString(head) + "\n", bold = true)

        if (onChange != null && !all) {
            sb.setSpan(
                click {
                    detailsOpen = !detailsOpen

                    onChange()
                },
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        if (shown) add(sb, "\n" + DnsCheckText.details(context, report) + "\n")
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
