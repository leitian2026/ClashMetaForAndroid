package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.processingDir
import java.io.File
import java.util.UUID

// CUSTOM (CUSTOM.md #28, not upstream): the last few versions of each profile's config.yaml. A version is kept
// when the user commits an edited profile (the hook in ProfileProcessor.apply calls [keep] just before the
// committed copy is replaced): the version that is being replaced and the new one are both saved, unless they
// equal the newest one already kept. The history page (BackupActivity, action HISTORY) lists them, shows the
// differences and rolls back.
//
// Power: files are written only at that moment, nothing runs in the background.
object ConfigHistory {
    const val MAX = 10
    private const val MAX_BYTES = 2L * 1024 * 1024

    class Version(val file: File, val at: Long) {
        val size: Long get() = file.length()

        fun text(): String = file.readText(Charsets.UTF_8)

        /** CUSTOM.md #38: the proxy was seen working while this version was the one in use. */
        val verified: Boolean get() = marker().exists()

        internal fun marker(): File = file.resolveSibling("$at.ok")
    }

    private fun dir(context: Context, uuid: UUID): File {
        return context.filesDir.resolve("custom/config_history/$uuid")
    }

    /** The versions of the profile, newest first. */
    fun list(context: Context, uuid: UUID): List<Version> {
        val files = dir(context, uuid).listFiles { f -> f.isFile && f.name.endsWith(".yaml") } ?: return emptyList()

        return files
            .mapNotNull { f -> f.name.removeSuffix(".yaml").toLongOrNull()?.let { Version(f, it) } }
            .sortedByDescending { it.at }
    }

    /** The committed config.yaml of the profile (what is in use), or null. */
    fun current(context: Context, uuid: UUID): File? {
        return context.importedDir.resolve("$uuid/config.yaml").takeIf { it.isFile }
    }

    /**
     * CUSTOM.md #38: the proxy was seen working (the home page got a working answer for the node in use) with the
     * config in use. The kept version with the same content gets a marker file next to it; no kept version with the
     * same content (a subscription, or one never saved here) -> nothing. Never throws.
     */
    fun markVerified(context: Context, uuid: UUID) {
        try {
            val current = current(context, uuid) ?: return

            if (current.length() == 0L || current.length() > MAX_BYTES) return

            val bytes = current.readBytes()

            list(context, uuid)
                .firstOrNull { it.size == bytes.size.toLong() && it.file.readBytes().contentEquals(bytes) }
                ?.let { v -> if (!v.verified) v.marker().createNewFile() }
        } catch (_: Exception) {
        }
    }

    /** Called before the committed copy of a profile is replaced by the one in the processing folder. Never throws. */
    fun keep(context: Context, uuid: UUID) {
        try {
            save(context, uuid, context.importedDir.resolve("$uuid/config.yaml")) // what is being replaced
            save(context, uuid, context.processingDir.resolve("config.yaml")) // what replaces it
        } catch (_: Exception) {
        }
    }

    private fun save(context: Context, uuid: UUID, source: File) {
        if (!source.isFile || source.length() == 0L || source.length() > MAX_BYTES) return

        val bytes = source.readBytes()
        val versions = list(context, uuid)
        val newest = versions.firstOrNull()

        if (newest != null && newest.file.readBytes().contentEquals(bytes)) return

        val target = dir(context, uuid)

        target.mkdirs()

        // a strictly newer name than the newest kept one, so that two saves in the same millisecond keep their order
        val at = maxOf(System.currentTimeMillis(), (newest?.at ?: 0L) + 1)

        target.resolve("$at.yaml").writeBytes(bytes)

        versions.drop(MAX - 1).forEach {
            it.file.delete()
            it.marker().delete()
        }
    }
}
