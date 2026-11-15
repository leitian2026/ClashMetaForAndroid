package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.processingDir
import java.io.File
import java.util.UUID

// CUSTOM (CUSTOM.md #28 #56, not upstream): the versions of each self-edited profile's config.yaml, kept apart from
// the copy that is in use. A version gets in by three ways:
//   * the editor's "save as history version" ([add]): only written here, nothing is applied;
//   * the editor's "apply" ([keepInUse], then [add]): the version in use and the text to apply are kept first, so
//     the list stays in the order things happened;
//   * a profile commit (the hook in ProfileProcessor.apply calls [keep] just before the committed copy is replaced).
// A version equal to any version already kept is not kept again (the existing one is used). Only profiles of type
// File have a history; the history of a deleted profile is deleted with it ([deleteAll], [cleanOrphans]).
//
// Marker files next to a version (`<ms>.ok`, `<ms>.saved`, `<ms>.bad`, empty): worked (CUSTOM.md #38), saved by the
// user and not applied since, refused by the core when it was applied.
//
// Power: files are written only when the user saves / applies / opens the list, nothing runs in the background.
object ConfigHistory {
    const val MAX = 10
    private const val MAX_BYTES = 2L * 1024 * 1024

    const val ADDED = 0
    const val EXISTS = 1
    const val EMPTY = 2
    const val TOO_BIG = 3

    class Version(val file: File, val at: Long) {
        val size: Long get() = file.length()

        fun text(): String = file.readText(Charsets.UTF_8)

        /** CUSTOM.md #38: the proxy was seen working while this version was the one in use. */
        val verified: Boolean get() = marker("ok").exists()

        /** CUSTOM.md #56: saved here by the user and not applied since. */
        val saved: Boolean get() = marker("saved").exists()

        /** CUSTOM.md #56: the core refused this version when it was applied. */
        val bad: Boolean get() = marker("bad").exists()

        internal fun marker(ext: String): File = file.resolveSibling("$at.$ext")

        internal fun remove() {
            file.delete()
            marker("ok").delete()
            marker("saved").delete()
            marker("bad").delete()
        }
    }

    /** The answer of [add]: [status] is ADDED, EXISTS, EMPTY or TOO_BIG; [version] is set for ADDED and EXISTS. */
    class AddResult(val status: Int, val version: Version?)

    private fun root(context: Context): File = context.filesDir.resolve("custom/config_history")

    private fun dir(context: Context, uuid: UUID): File = root(context).resolve(uuid.toString())

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

    private fun same(version: Version, bytes: ByteArray): Boolean {
        return version.size == bytes.size.toLong() && version.file.readBytes().contentEquals(bytes)
    }

    private fun find(versions: List<Version>, bytes: ByteArray): Version? = versions.firstOrNull { same(it, bytes) }

    private fun currentBytes(context: Context, uuid: UUID): ByteArray? {
        val file = current(context, uuid) ?: return null

        return if (file.length() > MAX_BYTES) null else file.readBytes()
    }

    /** The kept version that equals the config in use, or null (a subscription, or one never kept here). */
    fun inUse(context: Context, uuid: UUID, versions: List<Version> = list(context, uuid)): Version? {
        val bytes = currentBytes(context, uuid) ?: return null

        return find(versions, bytes)
    }

    /** Whether [text] is exactly the config in use. */
    fun isInUse(context: Context, uuid: UUID, text: String): Boolean {
        val file = current(context, uuid) ?: return false
        val bytes = text.toByteArray(Charsets.UTF_8)

        return file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)
    }

    /**
     * CUSTOM.md #38: the proxy was seen working (the home page got a working answer for the node in use) with the
     * config in use. The kept version with the same content gets a marker file next to it; no kept version with the
     * same content (a subscription, or one never saved here) -> nothing. Never throws.
     */
    fun markVerified(context: Context, uuid: UUID) {
        try {
            val bytes = currentBytes(context, uuid) ?: return

            if (bytes.isEmpty()) return

            find(list(context, uuid), bytes)?.let { v -> if (!v.verified) v.marker("ok").createNewFile() }
        } catch (_: Exception) {
        }
    }

    /** CUSTOM.md #56: marks a version the core refused. Never throws. */
    fun markBad(version: Version) {
        try {
            version.marker("bad").createNewFile()
        } catch (_: Exception) {
        }
    }

    /**
     * CUSTOM.md #56: the newest version that was saved by the user, was not refused, is not the one in use and has
     * not been applied since. [newerThanInUse]: only one that is newer than the version in use counts (an older one
     * was passed over by what was applied later).
     */
    fun unapplied(
        context: Context,
        uuid: UUID,
        versions: List<Version> = list(context, uuid),
        newerThanInUse: Boolean = true,
    ): Version? {
        val bytes = currentBytes(context, uuid)
        val used = if (bytes == null) null else find(versions, bytes)

        return versions.firstOrNull { v ->
            v.saved && !v.bad && (bytes == null || !same(v, bytes)) && (!newerThanInUse || used == null || v.at > used.at)
        }
    }

    /** The editor's "save as history version". Never throws a file error away silently: the caller shows it. */
    fun add(context: Context, uuid: UUID, text: String): AddResult {
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (text.isBlank()) return AddResult(EMPTY, null)
        if (bytes.size > MAX_BYTES) return AddResult(TOO_BIG, null)

        val existing = find(list(context, uuid), bytes)

        if (existing != null) return AddResult(EXISTS, existing)

        val version = write(context, uuid, bytes) ?: return AddResult(EMPTY, null)
        val used = currentBytes(context, uuid)

        // saved but not applied: it differs from the config in use
        if (used == null || !used.contentEquals(bytes)) version.marker("saved").createNewFile()

        prune(context, uuid)

        return AddResult(ADDED, version)
    }

    /** CUSTOM.md #56: keeps the config in use (if it is not kept yet), before a text that replaces it is kept. Never throws. */
    fun keepInUse(context: Context, uuid: UUID) {
        try {
            val bytes = currentBytes(context, uuid) ?: return

            if (bytes.isEmpty()) return

            if (find(list(context, uuid), bytes) == null) write(context, uuid, bytes)

            prune(context, uuid)
        } catch (_: Exception) {
        }
    }

    /** Called before the committed copy of a profile is replaced by the one in the processing folder. Never throws. */
    fun keep(context: Context, uuid: UUID) {
        try {
            keepInUse(context, uuid) // what is being replaced

            val source = context.processingDir.resolve("config.yaml")

            if (!source.isFile || source.length() == 0L || source.length() > MAX_BYTES) return

            val bytes = source.readBytes()
            val version = find(list(context, uuid), bytes) ?: write(context, uuid, bytes) ?: return

            // it is about to be the one in use: it is no longer "saved, not applied", and the core accepted it
            version.marker("saved").delete()
            version.marker("bad").delete()

            prune(context, uuid)
        } catch (_: Exception) {
        }
    }

    /** CUSTOM.md #56: the history of a deleted profile goes with it. Never throws. */
    fun deleteAll(context: Context, uuid: UUID) {
        try {
            dir(context, uuid).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /** CUSTOM.md #56: deletes the history folders of every profile that is not in [keepIds] (uuids of the File profiles). Never throws. */
    fun cleanOrphans(context: Context, keepIds: Set<String>): Int {
        var count = 0

        try {
            root(context).listFiles { f -> f.isDirectory }?.forEach {
                if (it.name !in keepIds && it.deleteRecursively()) count++
            }
        } catch (_: Exception) {
        }

        return count
    }

    // writes a new version (the caller has looked that no kept version has this content)
    private fun write(context: Context, uuid: UUID, bytes: ByteArray): Version? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null

        val target = dir(context, uuid)

        target.mkdirs()

        // a strictly newer name than the newest kept one, so that two saves in the same millisecond keep their order
        val newest = list(context, uuid).firstOrNull()
        val at = maxOf(System.currentTimeMillis(), (newest?.at ?: 0L) + 1)
        val file = target.resolve("$at.yaml")

        file.writeBytes(bytes)

        return Version(file, at)
    }

    // At most MAX versions are kept, newest first. Not counted and never deleted: the one in use, those that worked
    // (CUSTOM.md #38) and the newest one that is saved and not applied.
    private fun prune(context: Context, uuid: UUID) {
        val versions = list(context, uuid)
        val bytes = currentBytes(context, uuid)
        val pending = unapplied(context, uuid, versions, newerThanInUse = false)
        var count = 0

        for (v in versions) {
            if (v.verified || v == pending || (bytes != null && same(v, bytes))) continue

            count++

            if (count > MAX) v.remove()
        }
    }
}
