package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CUSTOM (CUSTOM.md #43, not upstream): the result for one address in "test other IPs". [code] is an error code
 * of the errors page (or "echrejected"), "" when [ok]. [country] (exit country, 落地) is filled in node mode, and in direct mode when the config has a node whose server is the typed domain; [faster] in node mode; [tls], [alpn],
 * [ech] ("accepted", "notaccepted", "rejected", "none", or "" when unknown), [cf] (the answer of the server shows
 * Cloudflare; a positive sign only), [colo] in direct mode. [asn] is filled only after a "look up" (mode "asn").
 */
@Serializable
data class IpTestRow(
    val ip: String = "", // the address, or the domain when [isDomain]
    val isDomain: Boolean = false,
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
    val cf: Boolean = false,
    val colo: String = "",
    val geo: String = "", // CUSTOM.md #62: the country the address is registered in (归属地), "US"; "" when not found
    // CUSTOM.md #52: ten tries (percent lost; milliseconds of the ones that worked), the ports, the download test
    val sent: Int = 0,
    val loss: Int = 0,
    val min: Int = 0,
    val max: Int = 0,
    val avg: Int = 0,
    val ports: List<IpPortResult> = emptyList(),
    val port: String = "", // node mode: the node's own port (not tested)
    val speed: Double = 0.0, // megabytes per second, 0 when not tested or failed
    val speedBytes: Long = 0,
    val speedMs: Int = 0,
    val speedErr: String = "",
)

/** CUSTOM.md #52: the handshake to one port of an address. [code] is an error code of the errors page. */
@Serializable
data class IpPortResult(
    val port: Int = 0,
    val ok: Boolean = false,
    val ms: Int = 0,
    val code: String = "",
    val reason: String = "",
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
 * "no domain", "no node", "no address", "domain only node" (direct mode, and only domains were typed),
 * "unknown mode", or "".
 */
@Serializable
data class IpTestReport(
    val mode: String = "",
    val domain: String = "",
    val node: String = "",
    val nodes: List<String> = emptyList(),
    val rows: List<IpTestRow> = emptyList(),
    val invalid: List<String> = emptyList(), // words that were neither an address nor a domain
    val skipped: List<String> = emptyList(), // direct mode: domains, which only the node mode tests
    val cut: Int = 0,
    val ref: IpTestRef? = null,
    val echSource: String = "",
    val at: Long = 0,
    val cancelled: Boolean = false,
    val error: String = "",
    val colos: Map<String, String> = emptyMap(), // CUSTOM.md #52: mode "colo": data centre code -> city
    val coloCountries: Map<String, String> = emptyMap(), // CUSTOM.md #62: mode "colo": data centre code -> country code
    val landNode: String = "", // CUSTOM.md #62: direct mode: the node through which the exit country was asked, "" when none
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
