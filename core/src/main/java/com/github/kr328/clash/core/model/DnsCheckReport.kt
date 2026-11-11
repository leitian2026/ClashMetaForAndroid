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
