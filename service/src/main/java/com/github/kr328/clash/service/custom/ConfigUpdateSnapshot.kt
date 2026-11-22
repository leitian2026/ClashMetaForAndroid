package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.service.util.importedDir
import java.io.File
import java.util.UUID

/** CUSTOM (CUSTOM.md #170): keep only the immediately previous subscription config for on-demand diagnostics. */
object ConfigUpdateSnapshot {
    const val MAX_BYTES = 4L * 1024 * 1024

    private fun file(context: Context, uuid: UUID): File =
        context.filesDir.resolve("custom/config_update_snapshot/$uuid.yaml")

    /** Called immediately before a non-File profile's imported directory is replaced. Never blocks the update on I/O errors. */
    fun keep(context: Context, uuid: UUID) {
        val source = context.importedDir.resolve("$uuid/config.yaml")
        val target = file(context, uuid)
        try {
            if (!source.isFile || source.length() <= 0L || source.length() > MAX_BYTES) {
                target.delete()
                return
            }
            target.parentFile?.mkdirs()
            val temporary = target.resolveSibling(target.name + ".tmp")
            source.copyTo(temporary, overwrite = true)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                target.delete()
            }
        } catch (_: Exception) {
            target.delete()
            target.resolveSibling(target.name + ".tmp").delete()
        }
    }

    /** Read the one saved previous config; no copy is made and no work is done unless diagnostics asks for it. */
    fun read(context: Context, uuid: UUID): String? {
        val source = file(context, uuid)
        return try {
            if (!source.isFile || source.length() <= 0L || source.length() > MAX_BYTES) null
            else source.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** A deleted subscription has no previous version to compare. */
    fun deleteAll(context: Context, uuid: UUID) {
        try {
            file(context, uuid).delete()
            file(context, uuid).resolveSibling(file(context, uuid).name + ".tmp").delete()
        } catch (_: Exception) {
        }
    }
}
