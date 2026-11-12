package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #32): the "⋮" menu at the top right of the home page. Cloud backup (Jianguoyun,
// push / pull / settings), and the local backup export / import (moved here from the app settings
// page). The work is done by BackupActivity (app module), started by its class name because the
// design module cannot see the app module. Only runs when the user taps; no timer, no background work.
class MainCustomMenu(
    private val context: Context,
    root: View,
) {
    init {
        root.findViewById<View>(R.id.custom_more)?.setOnClickListener { show(it) }
    }

    private fun show(anchor: View) {
        val popup = PopupMenu(context, anchor)

        popup.menu.add(0, ID_PUSH, 0, R.string.cloud_menu_push)
        popup.menu.add(0, ID_PULL, 1, R.string.cloud_menu_pull)
        popup.menu.add(0, ID_SETTINGS, 2, R.string.cloud_menu_settings)
        popup.menu.add(1, ID_EXPORT, 3, R.string.backup_export_title)
        popup.menu.add(1, ID_IMPORT, 4, R.string.backup_import_title)
        popup.menu.add(1, ID_UNDO, 5, R.string.cloud_menu_undo)

        popup.setOnMenuItemClickListener { item ->
            val action = when (item.itemId) {
                ID_PUSH -> ACTION_PUSH
                ID_PULL -> ACTION_PULL
                ID_SETTINGS -> ACTION_SETTINGS
                ID_EXPORT -> ACTION_EXPORT
                ID_IMPORT -> ACTION_IMPORT
                ID_UNDO -> ACTION_UNDO
                else -> return@setOnMenuItemClickListener false
            }

            context.startActivity(Intent(action).setClassName(context, BACKUP_ACTIVITY))

            true
        }

        popup.show()
    }

    private companion object {
        const val ID_PUSH = 1
        const val ID_PULL = 2
        const val ID_SETTINGS = 3
        const val ID_EXPORT = 4
        const val ID_IMPORT = 5
        const val ID_UNDO = 6

        const val BACKUP_ACTIVITY = "com.github.kr328.clash.custom.BackupActivity"

        // same strings as the constants in BackupActivity's companion object
        const val ACTION_PUSH = "com.github.kr328.clash.custom.action.CLOUD_PUSH"
        const val ACTION_PULL = "com.github.kr328.clash.custom.action.CLOUD_PULL"
        const val ACTION_SETTINGS = "com.github.kr328.clash.custom.action.CLOUD_SETTINGS"
        const val ACTION_EXPORT = "com.github.kr328.clash.custom.action.BACKUP_EXPORT"
        const val ACTION_IMPORT = "com.github.kr328.clash.custom.action.BACKUP_IMPORT"
        const val ACTION_UNDO = "com.github.kr328.clash.custom.action.RESTORE_UNDO"
    }
}
