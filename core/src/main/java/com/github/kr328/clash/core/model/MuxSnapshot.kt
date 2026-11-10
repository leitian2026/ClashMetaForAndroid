package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** CUSTOM (CUSTOM.md #20, not upstream): open sockets of a node to one address. [country] only when already known. */
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

/** CUSTOM (CUSTOM.md #20): the answer of the core to a query with sort mode SORT_MUX. */
@Serializable
data class MuxSnapshot(
    val total: Int = 0,
    val mux: List<MuxNode> = emptyList(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): MuxSnapshot {
            return json.decodeFromString(serializer(), text)
        }
    }
}
