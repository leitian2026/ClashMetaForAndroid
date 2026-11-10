package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.ConfigCheckAttempt
import com.github.kr328.clash.core.model.ConfigCheckItem
import com.github.kr328.clash.core.model.ConfigCheckReport
import com.github.kr328.clash.design.R

/** CUSTOM (CUSTOM.md #22, not upstream): the result text of the config check page. */
object ConfigCheckText {
    fun build(context: Context, report: ConfigCheckReport): String {
        if (report.items.isEmpty()) return context.getString(R.string.check_no_items)

        val lines = ArrayList<String>()

        lines += "■ " + context.getString(R.string.check_section_ech, report.items.size, report.domain)

        for (item in report.items) {
            lines += context.getString(R.string.check_node_head, item.node, item.server)
            lines += "    " + verdict(context, item)

            if (item.verdict != "noconfig" && item.verdict != "disabled") {
                lines += "    " + context.getString(R.string.check_ech_line, attempt(context, item.ech, true))
                lines += "    " + context.getString(R.string.check_plain_line, attempt(context, item.plain, false))
            }
        }

        return lines.joinToString("\n")
    }

    private fun verdict(context: Context, item: ConfigCheckItem): String {
        return context.getString(
            when (item.verdict) {
                "effective" -> R.string.check_verdict_effective
                "accepted" -> R.string.check_verdict_accepted
                "rejected" -> R.string.check_verdict_rejected
                "failed" -> R.string.check_verdict_failed
                "noconfig" -> R.string.check_verdict_noconfig
                "disabled" -> R.string.check_verdict_disabled
                else -> R.string.check_verdict_unknown
            }
        )
    }

    private fun attempt(context: Context, a: ConfigCheckAttempt, ech: Boolean): String {
        if (a.ok) {
            return if (ech && a.accepted) {
                context.getString(R.string.check_ok_accepted, a.ms)
            } else {
                context.getString(R.string.check_ok, a.ms)
            }
        }

        val explain = ErrorsText.explain(context, a.code)

        return context.getString(R.string.check_failed, a.ms, explain, a.reason.ifEmpty { "-" })
    }
}
