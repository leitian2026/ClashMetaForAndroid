// CUSTOM (CUSTOM.md #17)
package com.github.kr328.clash.service.custom

import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import java.util.UUID

/** Room-independent rows exposed to the app module for backup and restore. */
data class ProfileBackupRow(
    val uuid: UUID,
    val name: String,
    val type: String,
    val source: String,
    val interval: Long,
    val createdAt: Long,
    val ageSecretKey: String?,
)

data class SelectionRow(
    val proxy: String,
    val selected: String,
)

/** Keeps Room implementation types inside the service module. */
object SelectionBackup {
    suspend fun queryImportedRows(): List<ProfileBackupRow> {
        val dao = ImportedDao()
        return dao.queryAllUUIDs().mapNotNull { uuid ->
            dao.queryByUUID(uuid)?.let {
                ProfileBackupRow(
                    it.uuid, it.name, it.type.name, it.source, it.interval,
                    it.createdAt, it.ageSecretKey,
                )
            }
        }
    }

    suspend fun queryPendingRows(): List<ProfileBackupRow> {
        val dao = PendingDao()
        return dao.queryAllUUIDs().mapNotNull { uuid ->
            dao.queryByUUID(uuid)?.let {
                ProfileBackupRow(
                    it.uuid, it.name, it.type.name, it.source, it.interval,
                    it.createdAt, it.ageSecretKey,
                )
            }
        }
    }

    suspend fun querySelectionRows(uuid: UUID): List<SelectionRow> {
        return SelectionDao().querySelections(uuid).map {
            SelectionRow(it.proxy, it.selected)
        }
    }

    suspend fun writeSelectionRows(uuid: UUID, rows: List<SelectionRow>) {
        val dao = SelectionDao()
        val existing = dao.querySelections(uuid).map { it.proxy }
        if (existing.isNotEmpty()) {
            dao.removeSelections(uuid, existing)
        }
        rows.forEach { row ->
            dao.setSelected(Selection(uuid, row.proxy, row.selected))
        }
    }
}
