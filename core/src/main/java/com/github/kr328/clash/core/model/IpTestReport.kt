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
    val ip: String = "",
    val isDomain: Boolean = false, // retained for saved-result compatibility; current test input is IP-only
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
    val coloErr: String = "", // CUSTOM.md #63: node mode: why the data centre was not read ("nosni" or the failure)
    // CUSTOM.md #66: node mode with a target address: the site's own answer (status, 0 = none), its redirect, the data centre of its Cf-Ray
    val targetStatus: Int = 0,
    val targetNote: String = "",
    val targetColo: String = "",
    // CUSTOM.md #156 step 41: details from the same through-node target GET; empty means not obtained.
    val targetTls: String = "",
    val targetAlpn: String = "",
    val targetCertSubject: String = "",
    val targetCertIssuer: String = "",
    val targetCertNotBefore: String = "",
    val targetCertNotAfter: String = "",
    val targetCertDnsNames: String = "",
    val targetCertIpAddresses: String = "",
    val targetCertVerification: String = "",
    val targetServer: String = "",
    val targetCfRay: String = "",
    val targetContentType: String = "",
    val targetRetryAfter: String = "",
    val targetCfMitigated: String = "",
    val targetSoft: String = "", // CUSTOM.md #67: "refresh" / "script" / "region" / "" (a guess from the page's first 64 KB)
    val targetSoftNote: String = "",
    val geo: String = "", // CUSTOM.md #62: the country the address is registered in (归属地), "US"; "" when not found
    // CUSTOM.md #52: ten tries (percent lost; milliseconds of the ones that worked), the ports, the download test
    val sent: Int = 0,
    val loss: Int = 0,
    val min: Int = 0,
    val max: Int = 0,
    val avg: Int = 0,
    // CUSTOM.md #68: how far the tries are from [avg] on average, and where the time goes (0 = not measured): direct mode
    // TCP connect / TLS handshake / first byte of one HEAD; node mode dial / handshake inside the tunnel / first byte
    val jitter: Int = 0,
    val dial: Int = 0,
    val shake: Int = 0,
    val first: Int = 0,
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
 * CUSTOM.md #69: one address the profile uses for its nodes (mode "config"). [via] is "server" (written as the
 * node's server), "hosts", "alias" (through a hosts value that is a name) or "dns" (resolved); [name] the hosts key
 * or domain it came from; [chain] every node that uses it has a `dialer-proxy`.
 */
@Serializable
data class IpTestConfigIp(
    val ip: String = "",
    val from: List<String> = emptyList(),
    val via: String = "",
    val name: String = "",
    val chain: Boolean = false,
    val inUse: Boolean = false, // CUSTOM.md #71: requests went through it lately
)

/** CUSTOM.md #69: a server that gave no address. [reason]: failed, empty, fake, loop, timeout, zone. */
@Serializable
data class IpTestConfigFail(
    val name: String = "",
    val nodes: List<String> = emptyList(),
    val reason: String = "",
    val detail: String = "",
)

/**
 * CUSTOM (CUSTOM.md #43): the answer of the core to a query with sort mode SORT_CHECK and `"kind":"ip"`. [mode] is
 * "nodes" (then only [nodes] is filled), "config", "asn", "parse", "probe", "nodeone", "download" or "colo".
 * [error] is a short code such as "no node", "no address" or "unknown mode".
 */
@Serializable
data class IpTestReport(
    val mode: String = "",
    val domain: String = "",
    val node: String = "",
    val nodes: List<String> = emptyList(),
    val rows: List<IpTestRow> = emptyList(),
    val invalid: List<String> = emptyList(), // words that were neither an address nor a domain
    val cut: Int = 0,
    val ref: IpTestRef? = null,
    val echSource: String = "",
    val at: Long = 0,
    val cancelled: Boolean = false,
    val error: String = "",
    val colos: Map<String, String> = emptyMap(), // CUSTOM.md #52: mode "colo": data centre code -> city
    val coloCountries: Map<String, String> = emptyMap(), // CUSTOM.md #62: mode "colo": data centre code -> country code
    val landNode: String = "", // CUSTOM.md #62: direct mode: the node through which the exit country was asked, "" when none
    // CUSTOM.md #69: mode "config": the addresses of the profile, the servers that gave none, the addresses of a resolved
    // domain over the limit, the ones left out by a node's ip-version
    val config: List<IpTestConfigIp> = emptyList(),
    val configFail: List<IpTestConfigFail> = emptyList(),
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
