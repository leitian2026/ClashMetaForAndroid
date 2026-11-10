package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.ConfigCheckAttempt
import com.github.kr328.clash.core.model.ConfigCheckGroup
import com.github.kr328.clash.core.model.ConfigCheckReport
import com.github.kr328.clash.design.R

/** CUSTOM (CUSTOM.md #22, not upstream): the result text of the config check page. */
object ConfigCheckText {
    fun build(context: Context, report: ConfigCheckReport): String {
        if (report.groups.isEmpty() && report.items.isEmpty()) return context.getString(R.string.check_no_items)

        val lines = ArrayList<String>()

        lines += "■ " + context.getString(R.string.check_section_ech, report.domain)

        for (group in report.groups) {
            lines += context.getString(R.string.check_group_nodes, group.nodes.joinToString("、"))
            lines += "    " + source(context, group)
            lines += "    " + verdict(context, group)

            for (t in group.targets) {
                lines += "    " + context.getString(
                    R.string.check_try_line,
                    t.addr,
                    attempt(context, t.ech, true),
                    attempt(context, t.plain, false),
                )
            }
        }

        for (item in report.items) {
            lines += context.getString(R.string.check_node_head, item.node, item.server)

            when (item.status) {
                "disabled" -> lines += "    " + context.getString(R.string.check_status_disabled)
                "unsupported" -> lines += "    " + context.getString(R.string.check_status_unsupported)
                "badconfig" -> lines += "    " + context.getString(R.string.check_status_badconfig, item.error)
                else -> lines += "    " + context.getString(R.string.check_status_fetchfailed, item.query, item.error)
            }
        }

        lines += ""
        lines += context.getString(R.string.check_footer)

        return lines.joinToString("\n")
    }

    private fun source(context: Context, group: ConfigCheckGroup): String {
        return if (group.source == "config") {
            context.getString(R.string.check_source_config)
        } else {
            context.getString(R.string.check_source_dns, group.query)
        }
    }

    private fun verdict(context: Context, group: ConfigCheckGroup): String {
        return when (group.verdict) {
            "effective" -> context.getString(R.string.check_verdict_effective)
            "accepted" -> context.getString(R.string.check_verdict_accepted)
            "rejected" -> context.getString(R.string.check_verdict_rejected)
            "failed" -> context.getString(R.string.check_verdict_failed)
            else -> context.getString(R.string.check_verdict_unknown, group.targets.size)
        }
    }

    private fun attempt(context: Context, a: ConfigCheckAttempt, ech: Boolean): String {
        if (a.ok) {
            return if (ech && a.accepted) {
                context.getString(R.string.check_ok_accepted, a.ms)
            } else {
                context.getString(R.string.check_ok, a.ms)
            }
        }

        // The core's own wording carries local addresses and ports; it is shown only when the cause is not recognised.
        val explain = context.getString(
            when (a.code) {
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
                else -> return context.getString(R.string.check_failed_raw, ErrorsText.explain(context, a.code), a.reason.ifEmpty { "-" })
            }
        )

        return context.getString(R.string.check_failed, explain)
    }
}
