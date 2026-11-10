package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CUSTOM (CUSTOM.md #21, not upstream): one problem the core logged. Identical problems are merged by the
 * core: [count] says how many times, [time] / [first] are the latest / first one (unix milliseconds).
 * [code] is a short name of the reason (timeout, refused, reset, eof, dns, network, tls, auth, reject, other).
 */
@Serializable
data class ErrorEntry(
    val time: Long = 0,
    val first: Long = 0,
    val count: Int = 1,
    val level: String = "warn",
    val kind: String = "other", // connect, dns, other
    val tag: String = "",
    @SerialName("net") val network: String = "",
    val node: String = "",
    val rule: String = "",
    val source: String = "",
    val dest: String = "",
    val code: String = "other",
    val reason: String = "",
    val message: String = "",
)

/** CUSTOM (CUSTOM.md #21): the answer of the core to a query with sort mode SORT_ERRORS. Newest first. */
@Serializable
data class ErrorSnapshot(
    val total: Int = 0,
    val items: List<ErrorEntry> = emptyList(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun parse(text: String): ErrorSnapshot {
            return json.decodeFromString(serializer(), text)
        }
    }
}

/** CUSTOM (CUSTOM.md #21): a crash of this app that the crash recorder wrote down. */
data class CrashRecord(
    val time: Long,
    val process: String,
    val thread: String,
    val version: String,
    val trace: String,
)

/** CUSTOM (CUSTOM.md #21): a process of this app that Android ended abnormally. [kind]: native, anr, lowmem, init. */
data class ExitRecord(
    val time: Long,
    val process: String,
    val kind: String,
    val description: String,
)
