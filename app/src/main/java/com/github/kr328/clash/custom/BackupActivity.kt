package com.github.kr328.clash.custom

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.custom.BackupDesign
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CUSTOM (CUSTOM.md #17): picks the file for "export backup" / "import backup" (started from the
// entries of the app settings page) and runs BackupManager. One action per start, then it finishes.
class BackupActivity : BaseActivity<BackupDesign>() {
    override suspend fun main() {
        // transparent window (see BackupTranslucentTheme): must be set before the content is installed,
        // otherwise the theme's opaque app background is used and the page flashes
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val design = BackupDesign(this)

        setContentDesign(design)

        try {
            when (intent?.action) {
                ACTION_EXPORT -> exportBackup(design)
                ACTION_IMPORT -> importBackup(design)
                ACTION_HISTORY -> ConfigTools.run(this, design) // CUSTOM.md #27 #28
                ACTION_DIAG -> exportDiagnostics(design) // CUSTOM.md #29
                ACTION_CLOUD_PUSH -> CloudBackupFlow.push(this, design) // CUSTOM.md #32
                ACTION_CLOUD_PULL -> CloudBackupFlow.pull(this, design)
                ACTION_CLOUD_SETTINGS -> CloudBackupFlow.settings(this, design)
                ACTION_CLOUD_DELETE -> CloudBackupFlow.delete(this, design) // CUSTOM.md #36
                ACTION_RESTORE_UNDO -> CloudBackupFlow.undo(this, design)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            // CUSTOM.md #32: the network (or the server) said no; the text of a WebDAV answer is already readable
            design.message(
                getString(DesignR.string.backup_failed_title),
                if (e is WebDavClient.DavException) {
                    e.message ?: ""
                } else {
                    getString(DesignR.string.cloud_network_error, e.message ?: e.javaClass.simpleName)
                },
            )
        } catch (e: Exception) {
            design.message(
                getString(DesignR.string.backup_failed_title),
                e.message ?: e.javaClass.simpleName,
            )
        } finally {
            finish()
        }
    }

    private suspend fun exportBackup(design: BackupDesign) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val uri = startActivityForResult(
            ActivityResultContracts.CreateDocument("application/zip"),
            "ClashMeta-backup-$stamp.zip",
        ) ?: return

        design.setStatus(getString(DesignR.string.backup_working))

        val count = BackupManager.export(this, uri)

        design.message(
            getString(DesignR.string.backup_export_done_title),
            getString(DesignR.string.backup_export_done, count),
        )
    }

    // CUSTOM (CUSTOM.md #29): the diagnostic bundle. Asks first, because the bundle holds node addresses and the
    // domains of recent failed connections; the zip is written where the user picks.
    private suspend fun exportDiagnostics(design: BackupDesign) {
        val ok = design.confirm(
            getString(DesignR.string.diag_confirm_title),
            getString(DesignR.string.diag_confirm),
        )

        if (!ok) return

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val uri = startActivityForResult(
            ActivityResultContracts.CreateDocument("application/zip"),
            "ClashMeta-diagnostic-$stamp.zip",
        ) ?: return

        design.setStatus(getString(DesignR.string.diag_working))

        val count = DiagnosticBundle.export(this, uri, clashRunning)

        design.message(
            getString(DesignR.string.diag_done_title),
            getString(DesignR.string.diag_done, count),
        )
    }

    private suspend fun importBackup(design: BackupDesign) {
        val uri = startActivityForResult(
            ActivityResultContracts.OpenDocument(),
            arrayOf("application/zip", "application/octet-stream", "*/*"),
        ) ?: return

        design.setStatus(getString(DesignR.string.backup_working))

        val backup = BackupManager.open(this, uri)

        val date = if (backup.createdAt > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(backup.createdAt))
        } else {
            "-"
        }

        val ok = design.confirm(
            getString(DesignR.string.backup_import_confirm_title),
            getString(DesignR.string.backup_import_confirm, date, backup.profiles.length()),
        )

        if (!ok) {
            BackupManager.discard(backup)

            return
        }

        val result = BackupManager.restore(this, backup)

        val lines = ArrayList<String>()

        lines.add(getString(DesignR.string.backup_result_profiles, result.restored, result.skipped))

        if (result.failed.isNotEmpty()) {
            lines.add(getString(DesignR.string.backup_result_failed, result.failed.joinToString("、")))
        }
        if (result.settings) lines.add(getString(DesignR.string.backup_result_settings))
        if (result.override) lines.add(getString(DesignR.string.backup_result_override))

        lines.add(getString(DesignR.string.backup_result_hint))

        design.message(
            getString(DesignR.string.backup_import_done_title),
            lines.joinToString("\n"),
        )
    }

    companion object {
        const val ACTION_EXPORT = "com.github.kr328.clash.custom.action.BACKUP_EXPORT"
        const val ACTION_IMPORT = "com.github.kr328.clash.custom.action.BACKUP_IMPORT"
        const val ACTION_HISTORY = "com.github.kr328.clash.custom.action.CONFIG_HISTORY"
        const val ACTION_DIAG = "com.github.kr328.clash.custom.action.DIAGNOSTIC_EXPORT"
        // CUSTOM.md #32: same strings as in MainCustomMenu (design module)
        const val ACTION_CLOUD_PUSH = "com.github.kr328.clash.custom.action.CLOUD_PUSH"
        const val ACTION_CLOUD_PULL = "com.github.kr328.clash.custom.action.CLOUD_PULL"
        const val ACTION_CLOUD_SETTINGS = "com.github.kr328.clash.custom.action.CLOUD_SETTINGS"
        const val ACTION_CLOUD_DELETE = "com.github.kr328.clash.custom.action.CLOUD_DELETE" // CUSTOM.md #36
        const val ACTION_RESTORE_UNDO = "com.github.kr328.clash.custom.action.RESTORE_UNDO"
    }
}
