package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.util.logsDir
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** CUSTOM (CUSTOM.md #17): exports the additional persistent application and service state. */
internal object BackupExtras {
    private val PREF_NAMES = listOf(
        "custom_config_check",
        "custom_saved_ip_ui",
        "custom_editor",
        "custom_errors",
        "app",
        "tips",
    )

    suspend fun write(
        context: Context,
        sync: Boolean,
        manifest: JSONObject,
        zip: ZipOutputStream,
    ) {
        // BACKUP-EXPORT prefs:service
        val servicePrefs = try {
            val all = PreferenceProvider.createSharedPreferencesFromContext(context).all
                ?: throw IllegalStateException("getAll() returned null")
            prefsToJson(all.filterKeys { it != "active_profile" })
        } catch (e: Exception) {
            throw BackupManager.BackupException("服务设置导出失败（${e.javaClass.simpleName}）")
        }
        manifest.put("servicePrefs", servicePrefs)

        val extraPrefs = JSONObject()
        PREF_NAMES.forEach { name ->
            // BACKUP-EXPORT prefs:$name
            extraPrefs.put(name, prefsToJson(context.getSharedPreferences(name, Context.MODE_PRIVATE).all))
        }
        manifest.put("extraPrefs", extraPrefs)

        val imported = ImportedDao().queryAllUUIDs().mapNotNull { ImportedDao().queryByUUID(it) }
        manifest.put("importedMetadata", JSONArray().apply {
            imported.forEach { item ->
                put(JSONObject()
                    .put("uuid", item.uuid.toString())
                    .put("name", item.name)
                    .put("type", item.type.name)
                    .put("source", item.source)
                    .put("interval", item.interval)
                    .put("createdAt", item.createdAt)
                    .put("ageSecretKey", item.ageSecretKey ?: JSONObject.NULL))
            }
        })

        val pending = PendingDao().queryAllUUIDs().mapNotNull { PendingDao().queryByUUID(it) }
        manifest.put("pendingMetadata", JSONArray().apply {
            pending.forEach { item ->
                put(JSONObject()
                    .put("uuid", item.uuid.toString())
                    .put("name", item.name)
                    .put("type", item.type.name)
                    .put("source", item.source)
                    .put("interval", item.interval)
                    .put("createdAt", item.createdAt)
                    .put("ageSecretKey", item.ageSecretKey ?: JSONObject.NULL))
            }
        })

        manifest.put("selections", JSONArray().apply {
            imported.forEach { item ->
                SelectionDao().querySelections(item.uuid).forEach { selection ->
                    put(JSONObject()
                        .put("uuid", selection.uuid.toString())
                        .put("proxy", selection.proxy)
                        .put("selected", selection.selected))
                }
            }
        })

        // BACKUP-EXPORT room:imported
        // BACKUP-EXPORT room:pending
        // BACKUP-EXPORT room:selections
        val files = collectFiles(context)
        val included = JSONArray()
        var missing = 0
        files.forEach { (file, entryName) ->
            val input = try {
                file.inputStream()
            } catch (_: FileNotFoundException) {
                missing++
                return@forEach
            } catch (e: Exception) {
                throw BackupManager.BackupException("读取备份文件失败：$entryName（${e.javaClass.simpleName}）")
            }
            try {
                zip.putNextEntry(ZipEntry("extra/$entryName"))
                input.use { it.copyTo(zip) }
                included.put(entryName)
            } catch (e: Exception) {
                throw BackupManager.BackupException("读取备份文件失败：$entryName（${e.javaClass.simpleName}）")
            } finally {
                runCatching { zip.closeEntry() }
                runCatching { input.close() }
            }
        }
        manifest.put("extraFiles", included)
        manifest.put("extraSkippedMissingFiles", missing)
    }

    private fun collectFiles(context: Context): List<Pair<File, String>> {
        val result = LinkedHashMap<String, File>()
        fun add(file: File) {
            if (file.isFile) {
                result[file.relativeTo(context.filesDir).path.replace(File.separatorChar, '/')] = file
            } else if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.forEach {
                    val relative = it.relativeTo(context.filesDir).path.replace(File.separatorChar, '/')
                    result[relative] = it
                }
            }
        }

        // BACKUP-EXPORT dir:pending/*
        add(context.filesDir.resolve("pending"))
        // BACKUP-EXPORT dir:processing/*
        add(context.processingDir)
        // BACKUP-EXPORT file:custom/ip_history.json
        add(context.filesDir.resolve("custom/ip_history.json"))
        // BACKUP-EXPORT dir:custom/update_guard/*
        add(context.filesDir.resolve("custom/update_guard"))
        // BACKUP-EXPORT dir:custom/config_update_snapshot/*
        add(context.filesDir.resolve("custom/config_update_snapshot"))
        // BACKUP-EXPORT dir:custom/crash/*
        add(context.filesDir.resolve("custom/crash"))
        // BACKUP-EXPORT dir:custom/config_history/*
        add(context.filesDir.resolve("custom/config_history"))
        // BACKUP-EXPORT file:custom_network_identity.json
        add(context.filesDir.resolve("custom_network_identity.json"))
        // BACKUP-EXPORT file:custom_carrier_switch_*.json
        context.filesDir.listFiles { f -> f.isFile && f.name.startsWith("custom_carrier_switch_") && f.name.endsWith(".json") }
            ?.forEach { add(it) }

        // BACKUP-EXPORT file:logs/clash-*.log
        context.logsDir.listFiles { f -> f.isFile && f.name.startsWith("clash-") && f.name.endsWith(".log") }
            ?.forEach { file -> result["logs/${file.name}"] = file }

        return result.map { it.value to it.key }
    }

    private fun prefsToJson(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            val item = JSONObject()
            when (value) {
                is Boolean -> item.put("t", "b").put("v", value)
                is Int -> item.put("t", "i").put("v", value)
                is Long -> item.put("t", "l").put("v", value)
                is Float -> item.put("t", "f").put("v", value.toDouble())
                is String -> item.put("t", "s").put("v", value)
                is Set<*> -> item.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> throw BackupManager.BackupException("无法备份偏好键：$key")
            }
            put(key, item)
        }
    }
}
