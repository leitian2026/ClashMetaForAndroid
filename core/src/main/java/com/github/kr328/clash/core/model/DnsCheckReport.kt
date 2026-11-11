package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CUSTOM (CUSTOM.md #25, not upstream): the result for one DNS server. [kind]: doh, dot, tcp, udp, unsupported.
 * [roles] are the sections of `dns:` the address is written in. [code] is an error code of the errors page, or
 * "rcode" (the server answered with an error code, see [reason]) or "unsupported".
 */
@Serializable
data class DnsCheckServer(
    val address: String = "",
    val roles: List<String> = emptyList(),
    val kind: String = "",
    val scheme: String = "",
    val via: String = "",
    val h3: Boolean = false,
    val ok: Boolean = false,
    val ms: Int = 0,
    val rcode: Int = 0,
    val rname: String = "",
    val a: List<String> = emptyList(),
    val aaaa: List<String> = emptyList(),
    val fake: Boolean = false,
    val code: String = "",
    val reason: String = "",
)

/** CUSTOM (CUSTOM.md #26): one server whose answer looks wrong. [level]: likely, suspect. [why]: bogon, nx, differs. */
@Serializable
data class DnsCheckFlag(
    val address: String = "",
    val level: String = "",
    val why: List<String> = emptyList(),
)

/**
 * CUSTOM (CUSTOM.md #26): the answers of all servers read together. [kind]: none, nxdomain_all, polluted, suspect,
 * consistent, single. [trusted] are the addresses the encrypted (DoH / DoT) servers gave. [suggest] is a likelier
 * spelling of the domain when every server said NXDOMAIN; [hint] is typo, single_label or empty.
 */
@Serializable
data class DnsCheckVerdict(
    val kind: String = "none",
    val flags: List<DnsCheckFlag> = emptyList(),
    val trusted: List<String> = emptyList(),
    val suggest: String = "",
    val hint: String = "",
)

/** CUSTOM (CUSTOM.md #26): the nameserver-policy entry that covers the domain ([total] = entries written, 0 = none). */
@Serializable
data class DnsCheckPolicy(
    val total: Int = 0,
    val key: String = "",
    val pattern: String = "",
    val servers: List<String> = emptyList(),
    val unjudged: List<String> = emptyList(),
)

/** CUSTOM (CUSTOM.md #26): what the core's own resolver answers. [matches] are the checked servers that gave the same address. */
@Serializable
data class DnsCheckCore(
    val asked: Boolean = false,
    val ok: Boolean = false,
    val ms: Int = 0,
    val rcode: Int = 0,
    val rname: String = "",
    val a: List<String> = emptyList(),
    val aaaa: List<String> = emptyList(),
    val code: String = "",
    val reason: String = "",
    val matches: List<String> = emptyList(),
)

/**
 * CUSTOM (CUSTOM.md #25): the answer of the core to a check request of kind "dns". [inferred] is what the profile
 * alone says an app gets (hosts, fakeip, real, unknown); [kernel] / [kernelFinal] are what the core's own DNS
 * server (`dns.listen`) really answered, when it did.
 */
@Serializable
data class DnsCheckReport(
    val domain: String = "",
    val mode: String = "",
    val fakeRange: String = "",
    val filterMode: String = "",
    val listen: String = "",
    val hostsKey: String = "",
    val hostsValues: List<String> = emptyList(),
    val filterHit: String = "",
    val filterUnk: String = "",
    @SerialName("final") val inferred: String = "unknown",
    val kernel: DnsCheckServer? = null,
    val kernelFinal: String = "",
    val servers: List<DnsCheckServer> = emptyList(),
    val noServerNote: Boolean = false,
    val fakeRange6: String = "",
    val useHosts: Boolean = true,
    val ipv6: Boolean = false,
    val respectRules: Boolean = false,
    val aliasTo: String = "",
    val aaaa: String = "",
    val verdict: DnsCheckVerdict = DnsCheckVerdict(),
    val policy: DnsCheckPolicy = DnsCheckPolicy(),
    val core: DnsCheckCore = DnsCheckCore(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): DnsCheckReport {
            return json.decodeFromString(serializer(), text)
        }
    }
}
