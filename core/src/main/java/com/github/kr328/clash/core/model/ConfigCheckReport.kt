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

/** CUSTOM (CUSTOM.md #22): the result for one address of a node's server. [verdict]: effective, accepted, rejected, failed, unknown. */
@Serializable
data class ConfigCheckTarget(
    val addr: String = "",
    val verdict: String = "unknown",
    val ech: ConfigCheckAttempt = ConfigCheckAttempt(),
    val plain: ConfigCheckAttempt = ConfigCheckAttempt(),
)

/**
 * CUSTOM (CUSTOM.md #22): the result for one node. [status]: tested, disabled, unsupported, badconfig, fetchfailed.
 * [source] is "config" (written in ech-opts) or the DNS server that gave the config for [query].
 */
@Serializable
data class ConfigCheckItem(
    val node: String = "",
    val server: String = "",
    val status: String = "tested",
    val source: String = "",
    val query: String = "",
    val error: String = "",
    val targets: List<ConfigCheckTarget> = emptyList(),
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
