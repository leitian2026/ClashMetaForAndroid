package com.github.kr328.clash.design.custom

import android.content.Context
import android.util.Log
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.core.model.IpTestExtResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Status and latest average latency / download speed of a saved address; detailed port results are not stored.
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
    // CUSTOM.md #156: consecutive failed test batches; defaults keep older saved data and backups compatible.
    val failStreak: Int = 0,
    // CUSTOM.md #156：IP 登记国家代码（归属地）；默认空值兼容旧快照，不作为出口国家筛选来源。
    val countryCode: String = "",
    // CUSTOM.md #160：目标网站看到的出口国家代码；旧 JSON 缺少此字段时默认为空，不迁移归属地。
    val exitCountryCode: String = "",
    // CUSTOM.md #158: retain the latest average latency and download speed; defaults read older snapshots.
    val avg: Int = 0,
    val speed: Double = 0.0,
    // CUSTOM.md #77: retain optional direct extension diagnostics in saved batches; defaults read old snapshots.
    val ws: IpTestExtResult = IpTestExtResult(),
    val h3: IpTestExtResult = IpTestExtResult(),
    val mtu: IpTestExtResult = IpTestExtResult(),
    // CUSTOM.md #162: complete result snapshot; null means legacy or pasted IP without measurements.
    val result: IpTestRow? = null,
    val testDirect: Boolean = true,
    val testTarget: Boolean = false,
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
    // CUSTOM.md #162: data-centre labels used by the shared result table.
    val colos: Map<String, String> = emptyMap(),
    val coloCountries: Map<String, String> = emptyMap(),
)

/** Counts returned after saving one completed test batch. */
data class SavedIpSaveResult(
    val added: Int,
    val updated: Int,
    val skippedDomains: Int,
    val skippedOther: Int,
    val repeatedPasted: Int,
    val ignoredPasted: Int,
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

    const val MAX_IP_COUNT = 300
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

    // CUSTOM.md #156 step 13: why the last read had to fall back to an empty list; taken (and cleared) by the page
    // that shows it, so a damaged list is no longer shown as "nothing saved" without a word.
    @Volatile
    private var readError: String? = null

    fun takeReadError(): String? {
        val error = readError
        readError = null
        return error
    }

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
                // CUSTOM.md #156 step 13: name the serializer explicitly (like IpTestReport / IpHistory) instead of the
                // runtime lookup by class, which is not found in the shrunk (R8) build.
                json.decodeFromString(SavedIpSnapshot.serializer(), raw)
            } catch (e: Exception) {
                // CUSTOM.md #156 step 8: report corrupted or undecodable persisted data instead of silently hiding it.
                Log.e("SavedIpStore", "Failed to decode saved IP data; using an empty snapshot", e)
                readError = e.message ?: e.javaClass.simpleName // CUSTOM.md #156 step 13: shown once by the page
                emptySnapshot()
            }
        }

        return normalize(decoded)
    }

    /** Strictly serialize the current snapshot for inclusion in an encrypted backup. */
    fun encodeBackup(context: Context): String {
        val raw = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_DATA, null)
        val snapshot = if (raw.isNullOrBlank()) {
            emptySnapshot()
        } else {
            json.decodeFromString(SavedIpSnapshot.serializer(), raw)
        }
        return json.encodeToString(SavedIpSnapshot.serializer(), normalize(snapshot))
    }

    /** Validate a backup snapshot without changing local data. */
    fun validateBackup(raw: String) {
        val snapshot = json.decodeFromString(SavedIpSnapshot.serializer(), raw)
        val normalized = normalize(snapshot)
        // Backups may contain legacy lists above today's new-save limit; preserve them intact.
    }

    /** Restore a previously validated snapshot through the normal storage path. */
    fun restoreBackup(context: Context, raw: String) {
        val snapshot = json.decodeFromString(SavedIpSnapshot.serializer(), raw)
        write(context, snapshot)
    }

    /**
     * Persist a complete snapshot after an explicit user action.
     *
     * Duplicate addresses are removed within each operator, preserving the first occurrence before timestamp-based ordering is applied.
     * The same address may exist in different operators. Only new addresses that would take the total above
     * [MAX_IP_COUNT] are rejected; pre-existing entries are never truncated or blocked from being edited/deleted.
     */
    @Synchronized
    fun write(context: Context, snapshot: SavedIpSnapshot) {
        val normalized = normalize(snapshot)
        // Existing lists may exceed the current new-save limit. Never truncate or block edits/deletions.
        val value = json.encodeToString(SavedIpSnapshot.serializer(), normalized) // CUSTOM.md #156 step 13
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, value)
            .apply()
    }

    /** Atomically read, transform, and persist a snapshot for an explicit list mutation. */
    @Synchronized
    fun update(context: Context, transform: (SavedIpSnapshot) -> SavedIpSnapshot) {
        val current = readForUpdate(context)
        write(context, transform(current))
    }

    private fun readForUpdate(context: Context): SavedIpSnapshot {
        val preferences = try {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot open saved-IP storage; refusing to overwrite it", e)
        }
        val raw = try {
            preferences.getString(KEY_DATA, null)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot read saved-IP storage; refusing to overwrite it", e)
        }
        if (raw.isNullOrBlank()) return emptySnapshot()
        val decoded = try {
            json.decodeFromString(SavedIpSnapshot.serializer(), raw)
        } catch (e: Exception) {
            throw IllegalStateException("Saved-IP data is damaged; refusing to overwrite it", e)
        }
        return normalize(decoded)
    }

    /**
     * Merge completed test rows and parsed pasted addresses into one operator in a single write.
     * Test rows retain result-table order and override pasted duplicates; new pasted entries follow as UNTESTED.
     * Existing addresses are not duplicated and domain rows are never stored. // CUSTOM.md #156 step 11
     */
    @Synchronized
    fun saveRows(
        context: Context,
        operatorId: String,
        rows: List<IpTestRow>,
        onlyReachable: Boolean,
        pastedAddresses: List<String> = emptyList(),
        pastedDuplicates: Int = 0,
        ignoredPasted: Int = 0,
        testDirect: Boolean = true,
        testTarget: Boolean = false,
        colos: Map<String, String> = emptyMap(),
        coloCountries: Map<String, String> = emptyMap(),
    ): SavedIpSaveResult {
        // A batch save must never replace a damaged/unreadable list with an empty snapshot.
        val snapshot = readForUpdate(context)
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
        var repeatedPasted = pastedDuplicates

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
                batchEntries[address] = previousInBatch.copy(
                    status = status,
                    lastTestedAt = testedAt,
                    failStreak = if (row.ok) 0 else previousInBatch.failStreak + 1,
                    countryCode = row.geo.trim().ifBlank { previousInBatch.countryCode },
                    exitCountryCode = row.country.trim().ifBlank { previousInBatch.exitCountryCode },
                    avg = row.avg,
                    speed = row.speed,
                    ws = row.ws,
                    h3 = row.h3,
                    mtu = row.mtu,
                    result = row,
                    testDirect = testDirect,
                    testTarget = testTarget,
                )
                continue
            }

            val previous = existingByAddress[address]
            batchEntries[address] = SavedIpEntry(
                address = address,
                status = status,
                lastTestedAt = testedAt,
                addedAt = previous?.addedAt ?: testedAt,
                failStreak = if (row.ok) 0 else (previous?.failStreak ?: 0) + 1,
                countryCode = row.geo.trim().ifBlank { previous?.countryCode.orEmpty() },
                exitCountryCode = row.country.trim().ifBlank { previous?.exitCountryCode.orEmpty() },
                avg = row.avg,
                speed = row.speed,
                ws = row.ws,
                h3 = row.h3,
                mtu = row.mtu,
                result = row,
                testDirect = testDirect,
                testTarget = testTarget,
            )
            if (previous == null) added++ else updated++
        }

        // CUSTOM.md #156 step 11: pasted values merge after test rows; existing/test-row duplicates are counted, not overwritten.
        for (rawAddress in pastedAddresses) {
            val address = rawAddress.trim()
            if (address.isEmpty()) continue
            if (address in batchEntries || address in existingByAddress) {
                repeatedPasted++
                continue
            }
            batchEntries[address] = SavedIpEntry(
                address = address,
                status = SavedIpStatus.UNTESTED,
                addedAt = testedAt,
            )
            added++
        }

        // Test rows stay first in result-table order; newly pasted entries follow, then untouched older entries.
        val entries = batchEntries.values.toList() + operator.ips.filterNot { it.address in batchEntries }

        val operators = snapshot.operators.toMutableList()
        val updatedOperator = operator.copy(
            ips = entries,
            colos = operator.colos + colos,
            coloCountries = operator.coloCountries + coloCountries,
        )
        operators[operatorIndex] = updatedOperator
        val resultSnapshot = snapshot.copy(operators = operators, lastOperatorId = operatorId)
        val total = resultSnapshot.operators.sumOf { it.ips.size }
        // Only reject adding new addresses. Existing legacy entries above 300 remain untouched/editable.
        if (added > 0 && total > MAX_IP_COUNT) throw SavedIpLimitException(total)

        if (added > 0 || updated > 0 || snapshot.lastOperatorId != operatorId ||
            updatedOperator.colos != operator.colos || updatedOperator.coloCountries != operator.coloCountries
        ) {
            write(context, resultSnapshot)
        }

        return SavedIpSaveResult(added, updated, skippedDomains, skippedOther, repeatedPasted, ignoredPasted)
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
