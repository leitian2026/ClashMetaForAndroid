package com.github.kr328.clash.design.custom

import android.content.Context
import android.util.Log
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
    // Defaults keep entries written by the previous schema readable.
    val lastTestedAt: Long = 0L,
    val addedAt: Long = 0L,
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
// CUSTOM.md #156 step 8: distinguish capacity rejection from serialization and storage failures.
class SavedIpLimitException(val total: Int) : IllegalStateException("Saved IP limit exceeded: $total/${SavedIpStore.MAX_IP_COUNT}")
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

        val decoded = if (raw.isNullOrBlank()) {
            emptySnapshot()
        } else {
            try {
                json.decodeFromString<SavedIpSnapshot>(raw)
            } catch (e: Exception) {
                // CUSTOM.md #156 step 8: report corrupted or undecodable persisted data instead of silently hiding it.
                Log.e("SavedIpStore", "Failed to decode saved IP data; using an empty snapshot", e)
                emptySnapshot()
            }
        }

        return normalize(decoded)
    }

    /**
     * Persist a complete snapshot after an explicit user action.
     *
     * Duplicate addresses are removed within each operator, preserving the first occurrence before timestamp-based ordering is applied.
     * The same address may exist in different operators. More than [MAX_IP_COUNT] total entries is rejected rather
     * than silently truncating the user's list.
     */
    fun write(context: Context, snapshot: SavedIpSnapshot) {
        val normalized = normalize(snapshot)
        val total = normalized.operators.sumOf { it.ips.size }
        if (total > MAX_IP_COUNT) throw SavedIpLimitException(total)

        val value = json.encodeToString(normalized)
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, value)
            .apply()
    }

    /**
     * Merge one completed test batch into an operator. The whole batch receives one test timestamp and is
     * placed first in result-table order; untouched older entries follow. Domain rows are never stored.
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
        val existingByAddress = operator.ips.associateBy { it.address }
        // LinkedHashMap keeps the result table's order for every address in this batch.
        val batchEntries = LinkedHashMap<String, SavedIpEntry>()
        val testedAt = System.currentTimeMillis()
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
            val previousInBatch = batchEntries[address]
            if (previousInBatch != null) {
                // Duplicate rows within one result set keep their first position and latest result.
                batchEntries[address] = previousInBatch.copy(status = status, lastTestedAt = testedAt)
                continue
            }

            val previous = existingByAddress[address]
            batchEntries[address] = SavedIpEntry(
                address = address,
                status = status,
                lastTestedAt = testedAt,
                addedAt = previous?.addedAt ?: testedAt,
            )
            if (previous == null) added++ else updated++
        }

        // The current batch is always first. Stable normalization keeps its result-table order when timestamps tie.
        val entries = batchEntries.values.toList() + operator.ips.filterNot { it.address in batchEntries }

        val operators = snapshot.operators.toMutableList()
        operators[operatorIndex] = operator.copy(ips = entries)
        val resultSnapshot = snapshot.copy(operators = operators, lastOperatorId = operatorId)
        val total = resultSnapshot.operators.sumOf { it.ips.size }
        if (total > MAX_IP_COUNT) throw SavedIpLimitException(total)

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
        val ips = sortEntries(operator.ips.filter { entry ->
            entry.address.isNotBlank() && seen.add(entry.address)
        })

        return operator.copy(name = name, ips = ips)
    }

    /** Shared stable ordering for the full list and every filtered view. */
    fun sortEntries(entries: List<SavedIpEntry>): List<SavedIpEntry> {
        return entries.sortedWith(Comparator { left, right ->
            when {
                left.lastTestedAt != right.lastTestedAt ->
                    right.lastTestedAt.compareTo(left.lastTestedAt)
                left.lastTestedAt == 0L ->
                    right.addedAt.compareTo(left.addedAt)
                // Stable sorting preserves result-table order for a saved test batch.
                else -> 0
            }
        })
    }
}
