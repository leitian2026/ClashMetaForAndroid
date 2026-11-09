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

/** [total] counts every connection, [items] holds only the newest ones, newest first. */
@Serializable
data class ConnectionSnapshot(
    val total: Int = 0,
    val items: List<ConnectionItem> = emptyList(),
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
