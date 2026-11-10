package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** CUSTOM (CUSTOM.md #22, not upstream): one handshake of the ech test. [code] is an error code of the errors page. */
@Serializable
data class ConfigCheckAttempt(
    val ok: Boolean = false,
    val accepted: Boolean = false,
    val ms: Int = 0,
    val code: String = "",
    val reason: String = "",
)

/** CUSTOM (CUSTOM.md #22): the result for one node. [verdict]: effective, accepted, rejected, failed, unknown, noconfig, disabled. */
@Serializable
data class ConfigCheckItem(
    val node: String = "",
    val server: String = "",
    val verdict: String = "unknown",
    val ech: ConfigCheckAttempt = ConfigCheckAttempt(),
    val plain: ConfigCheckAttempt = ConfigCheckAttempt(),
)

/** CUSTOM (CUSTOM.md #22): the answer of the core to a query with sort mode SORT_CHECK. */
@Serializable
data class ConfigCheckReport(
    val domain: String = "",
    val items: List<ConfigCheckItem> = emptyList(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): ConfigCheckReport {
            return json.decodeFromString(serializer(), text)
        }
    }
}
