package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.DnsCheckReport
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

        out.append('\n')

        if (report.servers.isEmpty()) {
            out.append(context.getString(R.string.check_dns_no_servers)).append('\n')
        } else {
            out.append(context.getString(R.string.check_dns_servers_head, report.servers.size)).append("\n\n")

            for (item in report.servers) {
                out.append(serverText(context, item)).append("\n\n")
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

    private fun serverText(context: Context, s: DnsCheckServer): String {
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

        if (s.via.isNotEmpty()) out.append('\n').append("  ").append(context.getString(R.string.check_dns_via, s.via))
        if (s.h3) out.append('\n').append("  ").append(context.getString(R.string.check_dns_h3))

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
