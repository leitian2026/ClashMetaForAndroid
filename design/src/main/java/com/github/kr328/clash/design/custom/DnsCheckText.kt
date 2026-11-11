package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.core.model.DnsCheckFlag
import com.github.kr328.clash.core.model.DnsCheckServer
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #25): turns the answer of the DNS check into the text of the config check page.
object DnsCheckText {
    fun build(context: Context, report: DnsCheckReport): String {
        val out = StringBuilder()

        out.append(context.getString(R.string.check_dns_section, report.domain)).append("\n\n")

        if (report.aliasTo.isNotEmpty()) {
            out.append(context.getString(R.string.check_dns_alias, report.domain, report.aliasTo)).append('\n')
        }

        // CUSTOM.md #26: the overall reading of the answers comes first, it is what the reader wants to know
        verdict(context, report)?.let { out.append(it).append("\n\n") }

        // what an app gets for this domain: by the profile, and, when the core's DNS server answered, for real
        out.append(answer(context, report)).append('\n')

        when (report.aaaa) {
            "nopool6" -> R.string.check_dns_aaaa_nopool6
            "fake6" -> R.string.check_dns_aaaa_fake6
            "ipv6off" -> R.string.check_dns_aaaa_ipv6off
            else -> 0
        }.let { if (it != 0) out.append(context.getString(it)).append('\n') }

        if (!report.useHosts) out.append(context.getString(R.string.check_dns_hosts_off)).append('\n')
        if (report.respectRules) out.append(context.getString(R.string.check_dns_respect_rules)).append('\n')

        kernel(context, report)?.let { out.append(it).append('\n') }

        // CUSTOM.md #26
        out.append('\n').append(core(context, report)).append('\n')
        out.append(policy(context, report)).append('\n')

        out.append('\n')

        if (report.servers.isEmpty()) {
            out.append(context.getString(R.string.check_dns_no_servers)).append('\n')
        } else {
            out.append(context.getString(R.string.check_dns_servers_head, report.servers.size)).append("\n\n")

            val flags = report.verdict.flags.associateBy { it.address }

            for (item in report.servers) {
                out.append(serverText(context, item, flags[item.address])).append("\n\n")
            }
        }

        out.append(context.getString(R.string.check_dns_footer))

        return out.toString()
    }

    private fun answer(context: Context, report: DnsCheckReport): String {
        val fakeMode = report.mode.replace("-", "").equals("fakeip", ignoreCase = true)

        return when (report.inferred) {
            "hosts" -> context.getString(
                R.string.check_dns_answer_hosts, report.hostsKey, report.hostsValues.joinToString(", ").ifEmpty { "-" },
            )
            "fakeip" -> context.getString(
                R.string.check_dns_answer_fakeip,
                report.fakeRange.ifEmpty { context.getString(R.string.check_dns_range_default) },
            )
            "real" -> when {
                !fakeMode -> context.getString(R.string.check_dns_answer_real)
                report.filterHit.isNotEmpty() -> context.getString(R.string.check_dns_answer_filtered, report.filterHit)
                else -> context.getString(R.string.check_dns_answer_whitelist)
            }
            else -> if (report.filterMode.equals("rule", ignoreCase = true)) {
                context.getString(R.string.check_dns_answer_unknown_rule)
            } else {
                context.getString(R.string.check_dns_answer_unknown, report.filterUnk.ifEmpty { "-" })
            }
        }
    }

    // null when dns.listen is not written
    private fun kernel(context: Context, report: DnsCheckReport): String? {
        if (report.listen.isEmpty()) return null

        val kernel = report.kernel

        if (kernel == null || report.kernelFinal.isEmpty()) {
            return context.getString(R.string.check_dns_kernel_none, report.listen)
        }

        val addresses = (kernel.a + kernel.aaaa).joinToString(", ").ifEmpty { "-" }

        val label = if (report.kernelFinal == "fakeip") R.string.check_dns_kernel_fake else R.string.check_dns_kernel_real

        return context.getString(label, report.listen, addresses)
    }

    private fun serverText(context: Context, s: DnsCheckServer, flag: DnsCheckFlag?): String {
        val out = StringBuilder()

        out.append(context.getString(R.string.check_dns_server_head, s.address, s.roles.joinToString(", "))).append('\n')

        when {
            s.kind == "unsupported" -> out.append("  ").append(
                context.getString(R.string.check_dns_unsupported, s.scheme.ifEmpty { "?" }),
            )
            s.ok -> {
                if (s.rname == "NXDOMAIN") {
                    out.append("  ").append(context.getString(R.string.check_dns_ok_nx, s.ms))
                } else {
                    out.append("  ").append(context.getString(R.string.check_dns_ok, s.ms)).append('\n')
                    out.append("  ").append(
                        context.getString(
                            R.string.check_dns_records,
                            s.a.joinToString(", ").ifEmpty { context.getString(R.string.check_dns_none) },
                            s.aaaa.joinToString(", ").ifEmpty { context.getString(R.string.check_dns_none) },
                        ),
                    )
                }

                if (s.fake) out.append('\n').append("  ").append(context.getString(R.string.check_dns_fake_note))
            }
            else -> out.append("  ").append(failure(context, s))
        }

        if (flag != null) {
            val label = if (flag.level == "likely") R.string.check_dns_flag_likely else R.string.check_dns_flag_suspect

            out.append('\n').append("  ").append(context.getString(label, whyText(context, flag)))
        }

        if (s.via.isNotEmpty()) out.append('\n').append("  ").append(context.getString(R.string.check_dns_via, s.via))
        if (s.h3) out.append('\n').append("  ").append(context.getString(R.string.check_dns_h3))

        return out.toString()
    }

    private fun whyText(context: Context, flag: DnsCheckFlag): String {
        return flag.why.joinToString(context.getString(R.string.check_dns_why_sep)) {
            when (it) {
                "bogon" -> context.getString(R.string.check_dns_why_bogon)
                "nx" -> context.getString(R.string.check_dns_why_nx)
                "differs" -> context.getString(R.string.check_dns_why_differs)
                else -> it
            }
        }
    }

    // CUSTOM.md #26: null when there is nothing to say (no server answered)
    private fun verdict(context: Context, report: DnsCheckReport): String? {
        val v = report.verdict
        val out = StringBuilder()

        when (v.kind) {
            "nxdomain_all" -> {
                out.append(context.getString(R.string.check_dns_v_nxdomain_all))

                when {
                    v.suggest.isNotEmpty() -> out.append('\n').append(context.getString(R.string.check_dns_v_suggest, v.suggest))
                    v.hint == "single_label" -> out.append('\n').append(context.getString(R.string.check_dns_v_single_label))
                }
            }
            "polluted", "suspect" -> {
                out.append(
                    context.getString(
                        if (v.kind == "polluted") R.string.check_dns_v_polluted else R.string.check_dns_v_suspect,
                        v.flags.size,
                    ),
                )

                for (flag in v.flags) {
                    out.append("\n  ").append(context.getString(R.string.check_dns_v_flag_line, flag.address, whyText(context, flag)))
                }

                if (v.trusted.isNotEmpty()) {
                    out.append('\n').append(context.getString(R.string.check_dns_v_trusted, v.trusted.joinToString(", ")))
                }
            }
            "consistent" -> out.append(context.getString(R.string.check_dns_v_consistent))
            "single" -> out.append(context.getString(R.string.check_dns_v_single))
            else -> return null
        }

        return out.toString()
    }

    // CUSTOM.md #26: what the core's own resolver answers, with the servers it agrees with
    private fun core(context: Context, report: DnsCheckReport): String {
        val c = report.core

        return when {
            !c.asked -> context.getString(R.string.check_dns_core_none)
            !c.ok -> context.getString(R.string.check_dns_core_failed, c.reason.ifEmpty { "-" })
            c.rname == "NXDOMAIN" -> context.getString(R.string.check_dns_core_nx, c.ms)
            else -> {
                val none = context.getString(R.string.check_dns_none)
                val line = context.getString(
                    R.string.check_dns_core_ok,
                    c.ms,
                    c.a.joinToString(", ").ifEmpty { none },
                    c.aaaa.joinToString(", ").ifEmpty { none },
                )

                if (c.matches.isEmpty()) {
                    line + "\n" + context.getString(R.string.check_dns_core_nomatch)
                } else {
                    line + "\n" + context.getString(R.string.check_dns_core_match, c.matches.joinToString(", "))
                }
            }
        }
    }

    // CUSTOM.md #26: which nameserver-policy entry covers the domain
    private fun policy(context: Context, report: DnsCheckReport): String {
        val p = report.policy
        val out = StringBuilder()

        when {
            p.total == 0 -> return context.getString(R.string.check_dns_policy_none_written)
            p.key.isNotEmpty() -> {
                out.append(context.getString(R.string.check_dns_policy_hit, p.pattern, p.servers.joinToString(", ").ifEmpty { "-" }))

                if (p.pattern != p.key) {
                    out.append('\n').append(context.getString(R.string.check_dns_policy_entry, p.key))
                }
            }
            else -> out.append(context.getString(R.string.check_dns_policy_miss, p.total))
        }

        if (p.unjudged.isNotEmpty()) {
            val shown = p.unjudged.take(5).joinToString(", ") + if (p.unjudged.size > 5) "…" else ""

            out.append('\n').append(
                context.getString(if (p.key.isNotEmpty()) R.string.check_dns_policy_unjudged_before else R.string.check_dns_policy_unjudged, shown),
            )
        }

        return out.toString()
    }

    private fun failure(context: Context, s: DnsCheckServer): String {
        val explain = when (s.code) {
            "timeout" -> R.string.check_code_timeout
            "refused" -> R.string.check_code_refused
            "reset" -> R.string.check_code_reset
            "eof" -> R.string.check_code_eof
            "dns" -> R.string.check_code_dns
            "network" -> R.string.check_code_network
            "tls" -> R.string.check_code_tls
            "reject" -> R.string.check_code_reject
            else -> 0
        }

        return when {
            s.code == "rcode" -> context.getString(R.string.check_dns_rcode, s.reason)
            explain != 0 -> context.getString(R.string.check_failed, context.getString(explain))
            // an answer that is not a DNS message (JSON, HTML...) or an HTTP error: the raw reason says what came back
            else -> context.getString(R.string.check_failed_raw, ErrorsText.explain(context, s.code), s.reason.ifEmpty { "-" })
        }
    }
}
