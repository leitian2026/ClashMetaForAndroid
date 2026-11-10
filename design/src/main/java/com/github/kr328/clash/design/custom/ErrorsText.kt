package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.ErrorEntry
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CUSTOM (CUSTOM.md #21, not upstream): the text of the errors page. Four blocks, each only when it has
 * something: app crashes, node speed-test timeouts, failed connections (per node) and other warnings.
 */
object ErrorsText {
    fun build(context: Context, report: ErrorsReport): String {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val blocks = ArrayList<String>()

        // 1. the app itself
        val crashLines = ArrayList<String>()
        for (c in report.crashes) {
            crashLines += context.getString(
                R.string.errors_crash_head, time.format(Date(c.time)), c.process, c.thread, c.version,
            )
            crashLines += c.trace.trim().lines().take(TRACE_LINES).joinToString("\n") { "    $it" }
        }
        for (e in report.exits) {
            val kind = when (e.kind) {
                "native" -> R.string.errors_exit_native
                "anr" -> R.string.errors_exit_anr
                "lowmem" -> R.string.errors_exit_lowmem
                else -> R.string.errors_exit_init
            }
            crashLines += context.getString(
                R.string.errors_exit_item, time.format(Date(e.time)), e.process, context.getString(kind),
            )
            if (e.description.isNotEmpty()) crashLines += "    ${e.description}"
        }
        if (crashLines.isNotEmpty()) {
            blocks += section(context.getString(R.string.errors_section_crash, report.crashes.size + report.exits.size), crashLines)
        }

        // 2. nodes whose speed test timed out
        report.timeouts?.takeIf { it.isNotEmpty() }?.let { names ->
            blocks += section(
                context.getString(R.string.errors_section_timeout, names.size),
                names.map { context.getString(R.string.errors_timeout_item, it) },
            )
        }

        val items = report.snapshot?.items.orEmpty()

        // 3. failed connections, grouped by node (the node with the most failures first)
        val connects = items.filter { it.kind == "connect" }
        if (connects.isNotEmpty()) {
            val lines = ArrayList<String>()

            connects.groupBy { it.node }
                .entries.sortedByDescending { (_, list) -> list.sumOf { it.count } }
                .forEach { (node, list) ->
                    lines += context.getString(R.string.errors_node_head, node.ifEmpty { "?" }, list.sumOf { it.count })
                    list.forEach { lines += connectLines(context, time, it) }
                }

            blocks += section(
                context.getString(R.string.errors_section_connect, connects.sumOf { it.count }),
                lines,
            )
        }

        // 4. everything else the core complained about
        val others = items.filter { it.kind != "connect" }
        if (others.isNotEmpty()) {
            blocks += section(
                context.getString(R.string.errors_section_other, others.sumOf { it.count }),
                others.flatMap { otherLines(context, time, it) },
            )
        }

        if (blocks.isEmpty()) {
            return if (report.running) {
                context.getString(R.string.errors_empty)
            } else {
                context.getString(R.string.errors_empty_not_running)
            }
        }

        if (!report.running) {
            blocks += context.getString(R.string.errors_not_running_note)
        }

        return blocks.joinToString("\n\n")
    }

    private fun section(title: String, lines: List<String>): String {
        return "■ $title\n" + lines.joinToString("\n")
    }

    private fun connectLines(context: Context, time: SimpleDateFormat, e: ErrorEntry): List<String> {
        val head = StringBuilder("    ").append(time.format(Date(e.time))).append("  ")
            .append(e.network).append(' ').append(e.dest.ifEmpty { "?" })

        if (e.rule.isNotEmpty()) head.append(context.getString(R.string.errors_rule, e.rule))
        if (e.count > 1) head.append(context.getString(R.string.errors_count, e.count))

        return listOf(head.toString(), "        " + reason(context, e))
    }

    private fun otherLines(context: Context, time: SimpleDateFormat, e: ErrorEntry): List<String> {
        val head = StringBuilder(time.format(Date(e.time))).append("  ")
            .append(context.getString(if (e.level == "error") R.string.errors_level_error else R.string.errors_level_warn))
            .append("  ").append(e.message)

        if (e.count > 1) head.append(context.getString(R.string.errors_count, e.count))

        return if (e.code == "other") {
            listOf(head.toString())
        } else {
            listOf(head.toString(), "    " + explain(context, e.code))
        }
    }

    private fun reason(context: Context, e: ErrorEntry): String {
        val explain = explain(context, e.code)

        return if (e.code == "other" || e.reason.isEmpty()) {
            context.getString(R.string.errors_reason, e.reason.ifEmpty { explain })
        } else {
            context.getString(R.string.errors_reason_raw, explain, e.reason)
        }
    }

    private fun explain(context: Context, code: String): String {
        return context.getString(
            when (code) {
                "timeout" -> R.string.errors_code_timeout
                "refused" -> R.string.errors_code_refused
                "reset" -> R.string.errors_code_reset
                "eof" -> R.string.errors_code_eof
                "dns" -> R.string.errors_code_dns
                "network" -> R.string.errors_code_network
                "tls" -> R.string.errors_code_tls
                "auth" -> R.string.errors_code_auth
                "reject" -> R.string.errors_code_reject
                else -> R.string.errors_code_other
            }
        )
    }

    private const val TRACE_LINES = 8
}
