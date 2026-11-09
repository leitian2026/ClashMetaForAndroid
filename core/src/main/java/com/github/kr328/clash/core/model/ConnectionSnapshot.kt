package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CUSTOM (CUSTOM.md #8, not upstream): one connection of the core. [chains] is as the core keeps it:
 * the node that really carries the connection first, the groups it was selected through after it.
 */
@Serializable
data class ConnectionItem(
    val id: String,
    val host: String = "",
    val ip: String = "", // destination address
    val port: String = "",
    val network: String = "",
    val type: String = "", // inbound type
    val source: String = "",
    val process: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val payload: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val start: Long = 0, // unix milliseconds, 0 if unknown
    val nodeIp: String = "", // address the core last used for the node, "" if unknown
    val nodeCountry: String = "",
)

/** CUSTOM (CUSTOM.md #20): open sockets of a node to one address. [country] only when already known. */
@Serializable
data class MuxIp(
    val ip: String = "",
    val count: Int = 0,
    val country: String = "",
)

/**
 * CUSTOM (CUSTOM.md #20): [tcp] / [udp] are the sockets the core really has open to the node (with
 * multiplexing working, [tcp] stays far below [requests]). [node] is "" for sockets that belong to no node.
 */
@Serializable
data class MuxNode(
    val node: String = "",
    val tcp: Int = 0,
    val udp: Int = 0,
    val requests: Int = 0,
    val ips: List<MuxIp> = emptyList(),
)

/**
 * [total] counts every connection, [matched] those that pass the search text, [items] holds only the
 * first ones of them in the requested order.
 */
@Serializable
data class ConnectionSnapshot(
    val total: Int = 0,
    val matched: Int = 0,
    val items: List<ConnectionItem> = emptyList(),
    val mux: List<MuxNode> = emptyList(), // CUSTOM #20
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): ConnectionSnapshot {
            return json.decodeFromString(serializer(), text)
        }
    }
}
