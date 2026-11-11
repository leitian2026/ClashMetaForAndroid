package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CUSTOM (CUSTOM.md #30, not upstream): one line of the user's config with what the core / the app did to it.
 * The line is [pre] then [value], indented by [indent] spaces. [status] is 0 same, 1 replaced ([old] is the value
 * it had), 2 dropped, 3 added; [source] says who did it: "kernel", "override" or "app".
 */
@Serializable
data class EffectiveLine(
    @SerialName("i") val indent: Int = 0,
    @SerialName("p") val pre: String = "",
    @SerialName("v") val value: String = "",
    @SerialName("o") val old: String = "",
    @SerialName("s") val status: Int = 0,
    @SerialName("r") val source: String = "",
) {
    companion object {
        const val SAME = 0
        const val CHANGED = 1
        const val REMOVED = 2
        const val ADDED = 3

        const val SRC_KERNEL = "kernel"
        const val SRC_OVERRIDE = "override"
        const val SRC_APP = "app"
    }
}

/** CUSTOM (CUSTOM.md #30): the answer of the core to a query with sort mode SORT_EFFECTIVE. */
@Serializable
data class EffectiveConfig(
    val ok: Boolean = false,
    val error: String = "",
    val lines: List<EffectiveLine> = emptyList(),
    val changed: Int = 0,
    val removed: Int = 0,
    val added: Int = 0,
    val addedKernel: Int = 0,
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): EffectiveConfig {
            return json.decodeFromString(serializer(), text)
        }
    }
}
