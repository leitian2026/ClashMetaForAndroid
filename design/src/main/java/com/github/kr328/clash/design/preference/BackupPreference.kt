package com.github.kr328.clash.design.preference

import android.content.Intent
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #17): the "backup / restore" entries of the app settings page. The hook in
// AppSettingsDesign is the single call customBackup(); the work is done by BackupActivity (app
// module), which is started by its class name because the design module cannot see the app module.

private const val BACKUP_ACTIVITY = "com.github.kr328.clash.custom.BackupActivity"

// same strings as BackupActivity.ACTION_EXPORT / ACTION_IMPORT
private const val ACTION_EXPORT = "com.github.kr328.clash.custom.action.BACKUP_EXPORT"
private const val ACTION_IMPORT = "com.github.kr328.clash.custom.action.BACKUP_IMPORT"

fun PreferenceScreen.customBackup() {
    category(R.string.backup_category)

    clickable(
        title = R.string.backup_export_title,
        icon = R.drawable.ic_baseline_save,
        summary = R.string.backup_export_summary,
    ) {
        clicked { start(ACTION_EXPORT) }
    }

    clickable(
        title = R.string.backup_import_title,
        icon = R.drawable.ic_baseline_restore,
        summary = R.string.backup_import_summary,
    ) {
        clicked { start(ACTION_IMPORT) }
    }

    clickable(
        title = R.string.history_entry_title,
        icon = R.drawable.ic_baseline_restore,
        summary = R.string.history_entry_summary,
    ) {
        clicked { start(ACTION_HISTORY) }
    }

    clickable(
        title = R.string.diag_entry_title,
        icon = R.drawable.ic_baseline_save,
        summary = R.string.diag_entry_summary,
    ) {
        clicked { start(ACTION_DIAG) }
    }
}

// CUSTOM.md #27 #28 #29: same entries, same activity
private const val ACTION_HISTORY = "com.github.kr328.clash.custom.action.CONFIG_HISTORY"
private const val ACTION_DIAG = "com.github.kr328.clash.custom.action.DIAGNOSTIC_EXPORT"

private fun PreferenceScreen.start(action: String) {
    context.startActivity(Intent(action).setClassName(context, BACKUP_ACTIVITY))
}
