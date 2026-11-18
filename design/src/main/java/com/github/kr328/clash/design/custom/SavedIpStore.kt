package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.core.model.IpTestRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Status of a saved address. Only the latest reachability result is kept; latency and port details are not stored.
 */
@Serializable
enum class SavedIpStatus {
    UNTESTED,
    REACHABLE,
    UNREACHABLE,
}

/** One address in a saved-operator group. */
@Serializable
data class SavedIpEntry(
    val address: String,
    val status: SavedIpStatus = SavedIpStatus.UNTESTED,
)

/**
 * A saved operator. Built-in operators use a fixed [id] and an empty [name]; their display names come from resources.
 * User-created operators keep their editable display name in [name].
 */
@Serializable
data class SavedIpOperator(
    val id: String,
    val name: String = "",
    val ips: List<SavedIpEntry> = emptyList(),
)

/** Counts returned after saving one completed test batch. */
data class SavedIpSaveResult(
    val added: Int,
    val updated: Int,
    val skippedDomains: Int,
    val skippedOther: Int,
)

/** Persisted state for the saved-IP feature. */
@Serializable
data class SavedIpSnapshot(
    val version: Int = SavedIpStore.DATA_VERSION,
    val operators: List<SavedIpOperator> = emptyList(),
    val lastOperatorId: String = SavedIpStore.OPERATOR_TELECOM,
)

// CUSTOM.md #156 (step 1/5): persistent data layer for the saved-IP list.
/**
 * Local storage for saved IP addresses.
 *
 * Reading never writes or migrates preferences. Writes happen only when a caller performs an explicit user action,
 * and use SharedPreferences.apply() so disk I/O is not performed on the UI thread.
 */
object SavedIpStore {
    const val OPERATOR_TELECOM = "telecom"
    const val OPERATOR_UNICOM = "unicom"
    const val OPERATOR_MOBILE = "mobile"

    const val MAX_IP_COUNT = 5000
    const val DATA_VERSION = 1

    private const val PREFERENCES = "custom_saved_ips"
    private const val KEY_DATA = "data"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Built-in IDs are stable and deliberately independent of localized display names. */
    val builtInOperatorIds: Set<String> = linkedSetOf(
        OPERATOR_TELECOM,
        OPERATOR_UNICOM,
        OPERATOR_MOBILE,
    )

    fun emptySnapshot(): SavedIpSnapshot = SavedIpSnapshot(
        operators = listOf(
            SavedIpOperator(OPERATOR_TELECOM),
            SavedIpOperator(OPERATOR_UNICOM),
            SavedIpOperator(OPERATOR_MOBILE),
        ),
    )

    fun read(context: Context): SavedIpSnapshot {
        val raw = try {
            context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null)
        } catch (_: Exception) {
            null
        }

        val decoded = try {
            if (raw.isNullOrBlank()) emptySnapshot() else json.decodeFromString<SavedIpSnapshot>(raw)
        } catch (_: Exception) {
            emptySnapshot()
        }

        return normalize(decoded)
    }

    /**
     * Persist a complete snapshot after an explicit user action.
     *
     * Duplicate addresses are removed within each operator while preserving the first occurrence and its position.
     * The same address may exist in different operators. More than [MAX_IP_COUNT] total entries is rejected rather
     * than silently truncating the user's list.
     */
    fun write(context: Context, snapshot: SavedIpSnapshot) {
        val normalized = normalize(snapshot)
        val total = normalized.operators.sumOf { it.ips.size }
        require(total <= MAX_IP_COUNT) { "Saved IP limit exceeded: $total/$MAX_IP_COUNT" }

        val value = json.encodeToString(normalized)
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, value)
            .apply()
    }

    /**
     * Merge tested rows into one operator. Existing addresses keep their position and only have their status
     * refreshed; new addresses are appended. Domain rows are never stored.
     */
    fun saveRows(
        context: Context,
        operatorId: String,
        rows: List<IpTestRow>,
        onlyReachable: Boolean,
    ): SavedIpSaveResult {
        val snapshot = read(context)
        val operatorIndex = snapshot.operators.indexOfFirst { it.id == operatorId }
        require(operatorIndex >= 0) { "Unknown saved-IP operator: $operatorId" }

        val operator = snapshot.operators[operatorIndex]
        val entries = operator.ips.toMutableList()
        val positions = entries.mapIndexed { index, entry -> entry.address to index }.toMap()
        var added = 0
        var updated = 0
        var skippedDomains = 0
        var skippedOther = 0

        for (row in rows) {
            if (row.isDomain) {
                skippedDomains++
                continue
            }
            if (onlyReachable && !row.ok) {
                skippedOther++
                continue
            }

            val address = row.ip.trim()
            if (address.isEmpty()) {
                skippedOther++
                continue
            }

            val status = if (row.ok) SavedIpStatus.REACHABLE else SavedIpStatus.UNREACHABLE
            val existing = positions[address]
            if (existing != null) {
                entries[existing] = entries[existing].copy(status = status)
                updated++
            } else {
                entries += SavedIpEntry(address, status)
                added++
            }
        }

        val operators = snapshot.operators.toMutableList()
        operators[operatorIndex] = operator.copy(ips = entries)
        val resultSnapshot = snapshot.copy(operators = operators, lastOperatorId = operatorId)
        val total = resultSnapshot.operators.sumOf { it.ips.size }
        require(total <= MAX_IP_COUNT) { "Saved IP limit exceeded: $total/$MAX_IP_COUNT" }

        if (added > 0 || updated > 0 || snapshot.lastOperatorId != operatorId) {
            write(context, resultSnapshot)
        }

        return SavedIpSaveResult(added, updated, skippedDomains, skippedOther)
    }

    private fun normalize(snapshot: SavedIpSnapshot): SavedIpSnapshot {
        val builtIns = listOf(
            SavedIpOperator(OPERATOR_TELECOM),
            SavedIpOperator(OPERATOR_UNICOM),
            SavedIpOperator(OPERATOR_MOBILE),
        )
        val byId = snapshot.operators
            .filter { it.id.isNotBlank() }
            .associateBy { it.id }

        val operators = ArrayList<SavedIpOperator>()
        for (default in builtIns) {
            val stored = byId[default.id]
            operators += normalizeOperator(stored ?: default, default.name)
        }

        for (stored in snapshot.operators) {
            if (stored.id in builtInOperatorIds || stored.id.isBlank()) continue
            operators += normalizeOperator(stored, stored.name)
        }

        return SavedIpSnapshot(
            version = DATA_VERSION,
            operators = operators,
            lastOperatorId = snapshot.lastOperatorId.takeIf { id -> operators.any { it.id == id } }
                ?: OPERATOR_TELECOM,
        )
    }

    private fun normalizeOperator(operator: SavedIpOperator, name: String): SavedIpOperator {
        val seen = HashSet<String>()
        val ips = operator.ips.filter { entry ->
            entry.address.isNotBlank() && seen.add(entry.address)
        }

        return operator.copy(name = name, ips = ips)
    }
}
