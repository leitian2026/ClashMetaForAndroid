package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.common.custom.CarrierSwitchStore
import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.design.custom.SavedIpStore
import com.github.kr328.clash.design.custom.SavedIpRestoreStat
import com.github.kr328.clash.service.custom.ConfigHistory
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

// CUSTOM (CUSTOM.md #17): subscription and settings restoration, split from BackupManager.restore.
internal object BackupRestore {
    suspend fun restore(
        context: Context,
        backup: BackupManager.Backup,
        replace: Boolean,
        applySettings: Boolean,
        replaceFiles: Boolean,
    ): BackupManager.RestoreResult {
        val savedIps = backup.manifest.optJSONObject("savedIps")?.toString()
        savedIps?.let { SavedIpStore.validateBackup(it) }
        val settings = withContext(Dispatchers.IO) {
            applySettings && BackupManager.restoreSettings(context, backup.manifest)
        }
        val override = withContext(Dispatchers.IO) {
            if (!applySettings || backup.manifest.isNull("override")) false
            else BackupManager.writeOverride(context, backup.manifest.getString("override"))
        }

        val existing = withProfile { queryAll() }
        val active = withProfile { queryActive() }
        val deleteCandidates = if (replace) existing.filter {
            it.imported && it.type != Profile.Type.External && !BackupManager.inBackup(backup, it)
        } else emptyList()

        val idMap = HashMap<String, UUID>()
        val failed = ArrayList<String>()
        val deleted = ArrayList<String>()
        var restored = 0
        var skipped = 0
        var carrierRestored = 0
        var carrierKept = 0
        var carrierFailed = 0
        var activeContentReplaced = false
        val carrierHandled = HashSet<String>()

        // Import or update every backup profile before deleting anything. The currently active
        // imported directory remains intact until ProfileProcessor has validated the replacement.
        for (i in 0 until backup.profiles.length()) {
            val item = backup.profiles.getJSONObject(i)
            val oldId = item.getString("uuid")
            val name = item.getString("name")
            val type = Profile.Type.valueOf(item.getString("type"))
            val source = item.getString("source")
            val interval = item.optLong("interval")
            val ageKey = if (item.isNull("ageSecretKey")) null else item.getString("ageSecretKey")
            val same = existing.firstOrNull {
                it.name == name && it.type == type && it.source == source
            }

            if (same != null && !(replaceFiles && type == Profile.Type.File)) {
                idMap[oldId] = same.uuid
                skipped++
                continue
            }

            val uuid = same?.uuid
            val carrierJson = item.optJSONObject("carrier")
            val previousCarrier = if (same != null && carrierJson != null) CarrierSwitchStore.read(context, same.uuid) else CarrierSwitchConfig()
            val shouldWriteCarrier = carrierJson != null &&
                (same == null || replace || CarrierSwitchStore.isEmpty(previousCarrier))
            var carrierWritten = false
            if (carrierJson != null) carrierHandled.add(oldId)
            var target: UUID? = null
            try {
                target = uuid ?: withProfile { create(type, name, source, ageKey) }
                val targetId = target ?: throw BackupManager.BackupException("cannot create subscription: $name")
                withProfile { patch(targetId, name, source, interval, ageKey) }

                withContext(Dispatchers.IO) {
                    val from = File(backup.root, BackupManager.PROFILES + oldId)
                    if (type == Profile.Type.File) {
                        if (!from.isDirectory) throw BackupManager.BackupException("missing file subscription data: $name")
                        val pending = context.pendingDir.resolve(targetId.toString())
                        pending.deleteRecursively()
                        from.copyRecursively(pending, overwrite = true)
                    } else if (uuid == null && from.isDirectory) {
                        from.copyRecursively(context.pendingDir.resolve(targetId.toString()), overwrite = true)
                    }
                }

                if (shouldWriteCarrier) {
                    carrierWritten = CarrierSwitchStore.write(context, targetId, CarrierSwitchStore.decode(carrierJson!!))
                    if (!carrierWritten) carrierFailed++
                }
                withProfile { commit(targetId, null) }
                if (carrierWritten) carrierRestored++
                else if (carrierJson != null && same != null && !shouldWriteCarrier) carrierKept++
                idMap[oldId] = targetId
                restored++
                if (same != null && active?.uuid == targetId) activeContentReplaced = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (carrierWritten) {
                    if (same != null && !CarrierSwitchStore.isEmpty(previousCarrier)) {
                        CarrierSwitchStore.write(context, same.uuid, previousCarrier)
                    } else {
                        target?.let { CarrierSwitchStore.delete(context, it) }
                    }
                }
                target?.let { failedUuid -> runCatching { withProfile { release(failedUuid) } } }
                failed.add(name)
            }
        }

        // Resolve the replacement active profile before removing old profiles. Prefer the backup's
        // active choice; otherwise retain the current active profile or choose another surviving one.
        val backupActive = if (!backup.manifest.isNull("activeProfile")) {
            idMap[backup.manifest.getString("activeProfile")]
        } else null
        val candidateIds = deleteCandidates.mapTo(HashSet()) { it.uuid }
        val survivingCurrent = active?.uuid?.takeUnless { it in candidateIds }
        val replacementActive = if (replace) {
            backupActive ?: survivingCurrent ?: idMap.values.firstOrNull { it !in candidateIds }
        } else if (active == null) {
            backupActive
        } else null

        if (replacementActive != null && replacementActive != active?.uuid && (replace || active == null || active.uuid in candidateIds)) {
            try {
                withProfile {
                    queryByUUID(replacementActive)?.takeIf { it.imported }?.let { setActive(it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the existing active subscription; deletion below will protect it if needed.
            }
        }

        // Only now remove subscriptions absent from the backup. Never delete the active profile
        // unless another imported profile has already been made active.
        if (replace) {
            val currentActive = withProfile { queryActive() }?.uuid
            for (profile in deleteCandidates) {
                if (profile.uuid == currentActive) {
                    failed.add("${profile.name}（当前启用且没有替代订阅，未删除）")
                    continue
                }
                try {
                    withProfile { delete(profile.uuid) }
                    deleted.add(profile.name)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed.add(profile.name)
                }
            }
        }

        // Map UUIDs only after the subscription outcomes are known; same-name local mappings are preserved.
        for (i in 0 until backup.profiles.length()) {
            val item = backup.profiles.getJSONObject(i)
            val oldId = item.getString("uuid")
            if (oldId in carrierHandled) continue
            val carrier = item.optJSONObject("carrier") ?: continue
            val uuid = idMap[oldId] ?: continue
            val wasAlreadyOnDevice = existing.any { it.uuid == uuid }
            val local = CarrierSwitchStore.read(context, uuid)
            if (!wasAlreadyOnDevice || replace || CarrierSwitchStore.isEmpty(local)) {
                if (CarrierSwitchStore.write(context, uuid, CarrierSwitchStore.decode(carrier))) carrierRestored++
                else carrierFailed++
            } else carrierKept++
        }

        // Pinned history is restored after UUID translation and after profile replacement succeeds.
        withContext(Dispatchers.IO) {
            for (i in 0 until backup.profiles.length()) {
                val item = backup.profiles.getJSONObject(i)
                val ids = item.optJSONArray("pinned") ?: continue
                val uuid = idMap[item.getString("uuid")] ?: continue
                val oldId = item.getString("uuid")
                val versions = ArrayList<Pair<Long, ByteArray>>()
                for (k in 0 until ids.length()) {
                    val at = ids.optLong(k)
                    val file = File(backup.root, "history/$oldId/$at.yaml")
                    if (at > 0 && file.isFile) versions.add(at to file.readBytes())
                }
                ConfigHistory.replacePinned(context, uuid, versions)
            }
        }

        val extraSettings = BackupExtrasRestore.restore(context, backup, idMap, applySettings, failed)
        val restoredActiveUuid = withProfile { queryActive()?.uuid }
        val activeProfileChanged = active?.uuid != restoredActiveUuid
        val savedIpStat = savedIps?.let {
            SavedIpStore.restoreBackup(context, it, merge = !replace)
        }

        withContext(Dispatchers.IO) { backup.root.deleteRecursively() }

        return BackupManager.RestoreResult(
            restored, skipped, failed, settings || extraSettings, override, deleted, savedIpStat,
            carrierRestored, carrierKept, carrierFailed,
            activeProfileChanged, restoredActiveUuid?.toString(),
            activeProfileChanged || activeContentReplaced,
        )
    
    }
}
