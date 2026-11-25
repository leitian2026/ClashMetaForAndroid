package com.github.kr328.clash.custom

import android.content.Context
import android.content.pm.PackageManager
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.custom.ProfileBackupRow
import com.github.kr328.clash.service.custom.SelectionBackup
import com.github.kr328.clash.service.custom.SelectionRow
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #17): restores additional preferences, Room selections and persistent files.
internal object BackupExtrasRestore {
    suspend fun restore(
        context: Context,
        backup: BackupManager.Backup,
        uuidMap: Map<String, UUID>,
        applySettings: Boolean,
        failed: MutableList<String>,
    ): Boolean {
        if (!applySettings) return false
        val manifest = backup.manifest
        val settings = restorePreferences(context, manifest, failed)
        restoreLauncherIcon(context, manifest, failed)
        restoreImportedMetadata(manifest, uuidMap, failed)
        restoreSelections(backup, manifest, uuidMap, failed)
        restorePendingMetadata(manifest, failed)
        restoreExtraFiles(context, backup, manifest, failed)
        return settings
    }

    private fun restorePreferences(context: Context, manifest: JSONObject, failed: MutableList<String>): Boolean {
        var restored = false
        // BACKUP-IMPORT prefs:service
        manifest.optJSONObject("servicePrefs")?.let { json ->
            try {
                restorePrefs(PreferenceProvider.createSharedPreferencesFromContext(context), json, setOf("active_profile"))
                restored = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.add("服务设置")
            }
        }
        manifest.optJSONObject("extraPrefs")?.let { groups ->
            val names = groups.keys()
            while (names.hasNext()) {
                val name = names.next()
                // BACKUP-IMPORT prefs: (dynamic group name)
                try {
                    restorePrefs(context.getSharedPreferences(name, Context.MODE_PRIVATE), groups.getJSONObject(name))
                    restored = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed.add("偏好设置 $name")
                }
            }
        }
        return restored
    }

    private fun restoreLauncherIcon(context: Context, manifest: JSONObject, failed: MutableList<String>) {
        // BACKUP-IMPORT ui:hide_app_icon
        if (!manifest.has("ui")) return
        try {
            val state = if (UiStore(context).hideAppIcon) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            context.packageManager.setComponentEnabledSetting(
                context.mainActivityAlias, state, PackageManager.DONT_KILL_APP,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.add("桌面图标状态")
        }
    }

    private suspend fun restoreImportedMetadata(
        manifest: JSONObject,
        uuidMap: Map<String, UUID>,
        failed: MutableList<String>,
    ) {
        // BACKUP-IMPORT room:imported
        val array = manifest.optJSONArray("importedMetadata") ?: return
        try {
            val rows = ArrayList<ProfileBackupRow>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val target = uuidMap[item.getString("uuid")] ?: continue
                rows.add(ProfileBackupRow(
                    target, item.getString("name"), item.getString("type"), item.getString("source"),
                    item.optLong("interval"), item.optLong("createdAt"),
                    if (item.isNull("ageSecretKey")) null else item.getString("ageSecretKey"),
                ))
            }
            SelectionBackup.writeImportedRows(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.add("已导入订阅元数据")
        }
    }

    private suspend fun restoreSelections(
        backup: BackupManager.Backup,
        manifest: JSONObject,
        uuidMap: Map<String, UUID>,
        failed: MutableList<String>,
    ) {
        // BACKUP-IMPORT room:selections
        val array = manifest.optJSONArray("selections") ?: return
        val oldUuids = LinkedHashSet<String>()
        val profiles = backup.profiles
        for (index in 0 until profiles.length()) oldUuids.add(profiles.getJSONObject(index).getString("uuid"))
        val values = ArrayList<BackupExtrasRestorePolicy.SelectionValue>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            values.add(BackupExtrasRestorePolicy.SelectionValue(
                item.getString("uuid"), item.getString("proxy"), item.getString("selected"),
            ))
        }
        val grouped = BackupExtrasRestorePolicy.mapSelections(values, uuidMap)
        oldUuids.forEach { oldId ->
            val target = uuidMap[oldId] ?: return@forEach
            val rows = grouped[target].orEmpty().map { SelectionRow(it.first, it.second) }
            try {
                SelectionBackup.writeSelectionRows(target, rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.add("节点选择 $oldId")
            }
        }
    }

    private suspend fun restorePendingMetadata(manifest: JSONObject, failed: MutableList<String>) {
        // BACKUP-IMPORT room:pending
        val array = manifest.optJSONArray("pendingMetadata") ?: return
        try {
            val rows = ArrayList<ProfileBackupRow>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                rows.add(ProfileBackupRow(
                    UUID.fromString(item.getString("uuid")), item.getString("name"), item.getString("type"),
                    item.getString("source"), item.optLong("interval"), item.optLong("createdAt"),
                    if (item.isNull("ageSecretKey")) null else item.getString("ageSecretKey"),
                ))
            }
            SelectionBackup.writePendingRows(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.add("待处理订阅元数据")
        }
    }

    private suspend fun restoreExtraFiles(
        context: Context,
        backup: BackupManager.Backup,
        manifest: JSONObject,
        failed: MutableList<String>,
    ) {
        // BACKUP-IMPORT extraFiles
        val files = manifest.optJSONArray("extraFiles") ?: return
        for (index in 0 until files.length()) {
            val path = files.getString(index)
            if (!BackupExtrasRestorePolicy.isSafeRelativePath(path)) {
                failed.add("备份文件路径无效：$path")
                continue
            }
            try {
                val source = File(backup.root, "extra/$path")
                if (!source.isFile) continue
                val root = context.filesDir.canonicalFile
                val target = File(context.filesDir, path).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) {
                    failed.add("备份文件路径无效：$path")
                    continue
                }
                withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    source.inputStream().buffered().use { input ->
                        target.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.add("备份文件：$path")
            }
        }
    }

    private fun restorePrefs(
        preferences: android.content.SharedPreferences,
        json: JSONObject,
        skip: Set<String> = emptySet(),
    ) {
        val editor = preferences.edit()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in skip) continue
            val item = json.getJSONObject(key)
            when (item.getString("t")) {
                "b" -> editor.putBoolean(key, item.getBoolean("v"))
                "i" -> editor.putInt(key, item.getInt("v"))
                "l" -> editor.putLong(key, item.getLong("v"))
                "f" -> editor.putFloat(key, item.getDouble("v").toFloat())
                "s" -> editor.putString(key, item.getString("v"))
                "ss" -> {
                    val array = item.getJSONArray("v")
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
                else -> throw IllegalArgumentException("unsupported preference type")
            }
        }
        editor.apply()
    }
}
