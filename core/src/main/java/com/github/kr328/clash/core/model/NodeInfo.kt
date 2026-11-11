package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CUSTOM (CUSTOM.md #23, #24, not upstream): small facts the errors page asks the core for.
 * [ipv6]: names of the nodes that can only be reached over IPv6. [testedAt] / [networkChangedAt]: unix
 * milliseconds of the last finished latency test and of the last network change the service saw (0 = never).
 */
@Serializable
data class NodeInfo(
    val ipv6: List<String> = emptyList(),
    val testedAt: Long = 0,
    val networkChangedAt: Long = 0,
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): NodeInfo {
            return json.decodeFromString(serializer(), text)
        }
    }
}
