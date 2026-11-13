package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CUSTOM (CUSTOM.md #43, not upstream): the result for one address in "test other IPs". [code] is an error code
 * of the errors page (or "echrejected"), "" when [ok]. [country], [faster] are filled in node mode; [tls], [alpn],
 * [ech] ("accepted", "notaccepted", "rejected", "none", or "" when unknown), [asn], [colo] in direct mode.
 */
@Serializable
data class IpTestRow(
    val ip: String = "",
    val ok: Boolean = false,
    val ms: Int = 0,
    val code: String = "",
    val reason: String = "",
    val inHosts: Boolean = false,
    val country: String = "",
    val faster: Int = 0,
    val tls: String = "",
    val alpn: String = "",
    val ech: String = "",
    val asn: String = "",
    val colo: String = "",
)

/** CUSTOM (CUSTOM.md #43): the quickest address of the node's `hosts:` entry in its last speed test; [at] is unix milliseconds. */
@Serializable
data class IpTestRef(
    val ip: String = "",
    val ms: Int = 0,
    val at: Long = 0,
)

/**
 * CUSTOM (CUSTOM.md #43): the answer of the core to a query with sort mode SORT_CHECK and `"kind":"ip"`. [mode] is
 * "direct", "node" or "nodes" (then only [nodes] is filled: the nodes that can be chosen). [error] is a short code:
 * "no domain", "no node", "no address", "unknown mode", or "".
 */
@Serializable
data class IpTestReport(
    val mode: String = "",
    val domain: String = "",
    val node: String = "",
    val nodes: List<String> = emptyList(),
    val rows: List<IpTestRow> = emptyList(),
    val invalid: List<String> = emptyList(),
    val cut: Int = 0,
    val ref: IpTestRef? = null,
    val echSource: String = "",
    val at: Long = 0,
    val cancelled: Boolean = false,
    val error: String = "",
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): IpTestReport {
            return json.decodeFromString(serializer(), text)
        }
    }
}
