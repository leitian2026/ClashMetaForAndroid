package com.github.kr328.clash.core.custom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CUSTOM (CUSTOM.md #53, not upstream): the problems of the running config that the core found (answer of
 * CustomConnections.SORT_ISSUES). [active] counts those that are neither ignored nor resolved.
 */
@Serializable
data class IssuesSnapshot(
    val active: Int = 0,
    val items: List<IssueItem> = emptyList(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): IssuesSnapshot = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class IssueItem(
    val id: String = "",
    val kind: String = "",
    val subject: String = "",
    val code: String = "",
    val reason: String = "",
    val extra: String = "",
    val first: Long = 0,
    val time: Long = 0,
    val count: Int = 0,
    val ignored: Boolean = false,
    val resolved: Boolean = false,
)
