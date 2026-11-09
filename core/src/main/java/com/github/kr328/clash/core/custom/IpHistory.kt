package com.github.kr328.clash.core.custom

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// CUSTOM (CUSTOM.md #10): one switch of the node IP in use, written by the core to
// <filesDir>/custom/ip_history.json (oldest first).
@Serializable
data class IpHistoryEntry(
    val t: Long = 0, // unix milliseconds
    val node: String = "",
    val ip: String = "",
    val country: String = "",
)

object IpHistory {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parse(text: String): List<IpHistoryEntry> {
        return try {
            json.decodeFromString(ListSerializer(IpHistoryEntry.serializer()), text)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
