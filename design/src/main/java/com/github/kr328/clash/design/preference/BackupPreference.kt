package com.github.kr328.clash.design.preference

import android.content.Intent
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #17): the "backup / restore" entries of the app settings page. The hook in
// AppSettingsDesign is the single call customBackup(); the work is done by BackupActivity (app
// module), which is started by its class name because the design module cannot see the app module.

private const val BACKUP_ACTIVITY = "com.github.kr328.clash.custom.BackupActivity"


fun PreferenceScreen.customBackup() {
    category(R.string.backup_category)

    // r35 (CUSTOM.md #32): "export backup" / "import backup" moved to the menu at the top right of the
    // home page (MainCustomMenu); the diagnostic bundle stays here. r68 (CUSTOM.md #56): the config history moved
    // into the editor page of a config (menu "History versions").

    clickable(
        title = R.string.diag_entry_title,
        icon = R.drawable.ic_baseline_info,
        summary = R.string.diag_entry_summary,
    ) {
        clicked { start(ACTION_DIAG) }
    }
}

// CUSTOM.md #29: same entry, same activity
private const val ACTION_DIAG = "com.github.kr328.clash.custom.action.DIAGNOSTIC_EXPORT"

private fun PreferenceScreen.start(action: String) {
    context.startActivity(Intent(action).setClassName(context, BACKUP_ACTIVITY))
}
