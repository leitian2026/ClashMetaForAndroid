package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.custom.BackupDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CUSTOM (CUSTOM.md #32): what the user sees when they tap an entry of the top-right menu: the
// dialogs around CloudBackup / BackupManager. Started by BackupActivity (one action per start).
// Everything here runs only after a tap; nothing is scheduled.
object CloudBackupFlow {
    /** The settings dialog. Returns the saved settings, or null when the user cancelled or left a field empty. */
    suspend fun settings(context: Context, design: BackupDesign): CloudBackup.Settings? {
        val old = CloudBackup.load(context)

        val values = design.form(
            context.getString(DesignR.string.cloud_settings_title),
            context.getString(DesignR.string.cloud_settings_message),
            listOf(
                BackupDesign.Field(context.getString(DesignR.string.cloud_field_account), old.account, email = true),
                BackupDesign.Field(context.getString(DesignR.string.cloud_field_app_password), old.appPassword),
                BackupDesign.Field(context.getString(DesignR.string.cloud_field_backup_password), old.backupPassword),
            ),
        ) ?: return null

        val settings = CloudBackup.Settings(values[0], values[1], values[2])

        if (!settings.complete) {
            design.message(
                context.getString(DesignR.string.backup_failed_title),
                context.getString(DesignR.string.cloud_settings_incomplete),
            )

            return null
        }

        CloudBackup.save(context, settings)

        // tell right away whether the account and the app password work
        design.setStatus(context.getString(DesignR.string.backup_working))

        try {
            CloudBackup.check(settings)

            design.message(
                context.getString(DesignR.string.cloud_settings_saved_title),
                context.getString(DesignR.string.cloud_settings_saved_ok),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(
                context.getString(DesignR.string.cloud_settings_saved_title),
                context.getString(DesignR.string.cloud_settings_saved_bad, describe(e)),
            )
        }

        return settings
    }

    suspend fun push(context: Context, design: BackupDesign) {
        val settings = ready(context, design) ?: return

        design.setStatus(context.getString(DesignR.string.cloud_pushing))

        val pushed = CloudBackup.push(context, settings)

        design.message(
            context.getString(DesignR.string.cloud_push_done_title),
            context.getString(
                DesignR.string.cloud_push_done,
                pushed.profiles,
                (pushed.bytes + 1023) / 1024,
                pushed.deviceName,
            ),
        )
    }

    suspend fun pull(context: Context, design: BackupDesign) {
        val settings = ready(context, design) ?: return

        design.setStatus(context.getString(DesignR.string.cloud_pulling))

        val history = CloudBackup.history(settings)

        if (history.isEmpty()) {
            design.message(
                context.getString(DesignR.string.backup_failed_title),
                context.getString(DesignR.string.cloud_nothing_there),
            )

            return
        }

        val index = design.pick(context.getString(DesignR.string.cloud_pick_title), labelsOf(context, history))

        if (index < 0) return

        val entry = history[index]

        design.setStatus(context.getString(DesignR.string.cloud_pulling))

        val data = CloudBackup.download(settings, entry)

        if (data == null) {
            design.message(
                context.getString(DesignR.string.backup_failed_title),
                context.getString(DesignR.string.cloud_nothing_there),
            )

            return
        }

        val zip = try {
            CloudBackup.decrypt(data, settings)
        } catch (e: BackupCrypto.WrongPasswordException) {
            design.message(
                context.getString(DesignR.string.backup_failed_title),
                context.getString(DesignR.string.cloud_wrong_password),
            )

            return
        } catch (e: IllegalArgumentException) {
            design.message(
                context.getString(DesignR.string.backup_failed_title),
                context.getString(DesignR.string.cloud_not_a_backup),
            )

            return
        }

        val backup = BackupManager.open(context, zip)

        var text = context.getString(
            DesignR.string.cloud_pull_confirm,
            format(entry.time),
            entry.deviceName,
            backup.profiles.length(),
        )

        // CUSTOM.md #36: which subscriptions come in, which are already here, which file subscriptions get replaced
        val cmp = BackupManager.compare(backup)
        val parts = ArrayList<String>()

        if (cmp.added.isNotEmpty()) {
            parts.add(context.getString(DesignR.string.cloud_cmp_added, names(context, cmp.added)))
        }
        if (cmp.same.isNotEmpty()) {
            parts.add(context.getString(DesignR.string.cloud_cmp_same, names(context, cmp.same)))
        }
        if (cmp.replaced.isNotEmpty()) {
            parts.add(context.getString(DesignR.string.cloud_cmp_replaced, names(context, cmp.replaced)))
        }

        if (parts.isNotEmpty()) text += "\n\n" + parts.joinToString("\n")

        // the box: also replace the settings of this device (not checked: subscriptions only)
        val withSettings = design.confirmWithOption(
            context.getString(DesignR.string.cloud_pull_confirm_title),
            text,
            context.getString(DesignR.string.cloud_pull_option_settings),
        )

        if (withSettings == null) {
            BackupManager.discard(backup)

            return
        }

        design.setStatus(context.getString(DesignR.string.backup_working))

        // the safety copy first: if it cannot be made, nothing is changed
        try {
            CloudBackup.snapshot(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupManager.discard(backup)

            throw e
        }

        // subscriptions are added (nothing is deleted); a file subscription gets the backup's content
        val result = BackupManager.restore(
            context,
            backup,
            replace = false,
            applySettings = withSettings,
            replaceFiles = true,
        )

        showResult(context, design, DesignR.string.backup_import_done_title, result, withSettings)
    }

    /** CUSTOM.md #36: deletes one backup from Jianguoyun (any device's), after a confirmation. */
    suspend fun delete(context: Context, design: BackupDesign) {
        val settings = ready(context, design) ?: return

        design.setStatus(context.getString(DesignR.string.cloud_pulling))

        val history = CloudBackup.history(settings)

        if (history.isEmpty()) {
            design.message(
                context.getString(DesignR.string.cloud_delete_title),
                context.getString(DesignR.string.cloud_nothing_there),
            )

            return
        }

        val index = design.pick(context.getString(DesignR.string.cloud_delete_pick_title), labelsOf(context, history))

        if (index < 0) return

        val entry = history[index]

        val sure = design.confirm(
            context.getString(DesignR.string.cloud_delete_title),
            context.getString(DesignR.string.cloud_delete_confirm, format(entry.time), entry.deviceName),
        )

        if (!sure) return

        design.setStatus(context.getString(DesignR.string.cloud_pulling))

        CloudBackup.delete(settings, entry)

        design.message(
            context.getString(DesignR.string.cloud_delete_title),
            context.getString(DesignR.string.cloud_delete_done),
        )
    }

    /** "Undo last restore": goes back to the safety copy that was made before the last cloud restore. */
    suspend fun undo(context: Context, design: BackupDesign) {
        val file = CloudBackup.lastSnapshot(context)

        if (file == null) {
            design.message(
                context.getString(DesignR.string.cloud_undo_title),
                context.getString(DesignR.string.cloud_undo_none),
            )

            return
        }

        design.setStatus(context.getString(DesignR.string.backup_working))

        val backup = BackupManager.open(context, file)
        val deletes = BackupManager.planDeletes(backup)

        var text = context.getString(DesignR.string.cloud_undo_confirm, format(CloudBackup.snapshotTime(file)))

        if (deletes.isNotEmpty()) {
            text += "\n\n" + context.getString(DesignR.string.cloud_confirm_deletes, deletes.joinToString("、"))
        }

        if (!design.confirm(context.getString(DesignR.string.cloud_undo_title), text)) {
            BackupManager.discard(backup)

            return
        }

        design.setStatus(context.getString(DesignR.string.backup_working))

        val result = BackupManager.restore(context, backup, replace = true)

        file.delete() // used once

        showResult(context, design, DesignR.string.cloud_undo_done_title, result, true)
    }

    // ---- shared -------------------------------------------------------------------------------

    // the settings, asking for them first when they are not there yet
    private suspend fun ready(context: Context, design: BackupDesign): CloudBackup.Settings? {
        val settings = CloudBackup.load(context)

        return if (settings.complete) settings else settings(context, design)
    }

    private suspend fun showResult(
        context: Context,
        design: BackupDesign,
        title: Int,
        result: BackupManager.RestoreResult,
        settingsApplied: Boolean,
    ) {
        val lines = ArrayList<String>()

        lines.add(context.getString(DesignR.string.backup_result_profiles, result.restored, result.skipped))

        if (result.deleted.isNotEmpty()) {
            lines.add(context.getString(DesignR.string.cloud_result_deleted, result.deleted.joinToString("、")))
        }
        if (result.failed.isNotEmpty()) {
            lines.add(context.getString(DesignR.string.backup_result_failed, result.failed.joinToString("、")))
        }
        if (result.settings) lines.add(context.getString(DesignR.string.backup_result_settings))
        if (result.override) lines.add(context.getString(DesignR.string.backup_result_override))

        lines.add(
            context.getString(
                if (settingsApplied) DesignR.string.backup_result_hint else DesignR.string.cloud_result_settings_kept,
            ),
        )
        lines.add(context.getString(DesignR.string.cloud_result_undo_hint))

        design.message(context.getString(title), lines.joinToString("\n"))
    }

    // newest first; "time · device name", this phone marked
    private fun labelsOf(context: Context, history: List<CloudBackup.Entry>): List<CharSequence> {
        val ownId = CloudBackup.deviceId(context)

        return history.map {
            val mark = if (it.deviceId == ownId) context.getString(DesignR.string.cloud_pick_this_device) else ""

            "${format(it.time)} · ${it.deviceName}$mark"
        }
    }

    // subscription names for a confirmation: the first 20, then how many there are
    private fun names(context: Context, list: List<String>): String {
        val shown = list.take(20).joinToString("、")

        return if (list.size > 20) shown + context.getString(DesignR.string.cloud_names_more, list.size) else shown
    }

    private fun format(time: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }

    /** A short text for an error, for the settings check. */
    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName
}
