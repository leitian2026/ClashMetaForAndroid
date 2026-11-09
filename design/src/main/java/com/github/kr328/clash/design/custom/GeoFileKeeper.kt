package com.github.kr328.clash.design.custom

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

// CUSTOM (CUSTOM.md #16): keeps the geo files the user downloaded alive across app updates.
//
// The upstream MainApplication.extractGeoFiles() replaces every geo file that is older than the app's
// last update with the built-in one. So a copy of each downloaded file is kept in
// filesDir/custom/geo/, and the one-line hook after extractGeoFiles() calls restoreAfterUpdate(),
// which puts the copies back right away (before the core reads any geo file).
//
// Event driven and nearly free: no timers, no network. The hook costs nothing for someone who never
// downloaded a file (the folder does not exist); otherwise it reads a tiny marker file, and only the
// first start after an app update copies the files.
object GeoFileKeeper {
    private const val MARKER = "handled_update" // lastUpdateTime of the app that was last handled
    private const val LOCK = ".lock"

    private fun clashDir(context: Context) = context.filesDir.resolve("clash")

    private fun keepDir(context: Context) = context.filesDir.resolve("custom").resolve("geo")

    /** The kept copy of [file] (may not exist). */
    fun copyOf(context: Context, file: GeoFile) = File(keepDir(context), file.fileName)

    fun hasCopy(context: Context, file: GeoFile): Boolean {
        return copyOf(context, file).let { it.isFile && it.length() > 0 }
    }

    /** Keeps a copy of the installed [file]; called right after a successful download. */
    fun keep(context: Context, file: GeoFile) {
        try {
            val dir = keepDir(context)
            dir.mkdirs()

            val temp = File(dir, file.fileName + ".tmp")

            clashDir(context).resolve(file.fileName).copyTo(temp, overwrite = true)

            if (!temp.renameTo(copyOf(context, file))) {
                temp.copyTo(copyOf(context, file), overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            // no copy: the file works until the next app update, which then replaces it
        }
    }

    /**
     * Called from Application.onCreate() right after the upstream extractGeoFiles(). After an app
     * update, puts the kept copies back into filesDir/clash. Safe to call from every process: the work
     * is done once per app update, under a file lock.
     */
    fun restoreAfterUpdate(context: Context) {
        try {
            val dir = keepDir(context)

            if (!dir.isDirectory) return // never downloaded anything

            val updatedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

            if (readMarker(dir) == updatedAt) return

            RandomAccessFile(File(dir, LOCK), "rw").use { lock ->
                lock.channel.lock().use {
                    // another process may have done it while this one waited
                    if (readMarker(dir) == updatedAt) return

                    val target = clashDir(context)

                    target.mkdirs()

                    for (file in GeoFile.values()) {
                        if (!hasCopy(context, file)) continue

                        val temp = File(target, file.fileName + ".restore")

                        copyOf(context, file).copyTo(temp, overwrite = true)

                        // the copy has "now" as its time, which is newer than the app update, so
                        // the upstream code leaves it alone from now on
                        val destination = File(target, file.fileName)

                        if (!temp.renameTo(destination)) {
                            temp.copyTo(destination, overwrite = true)
                            temp.delete()
                        }
                    }

                    File(dir, MARKER).writeText(updatedAt.toString())
                }
            }
        } catch (e: Exception) {
            // keep starting: the built-in files are in place, the home page then shows the GeoSite hint
        }
    }

    private fun readMarker(dir: File): Long? {
        return try {
            File(dir, MARKER).readText().trim().toLong()
        } catch (e: Exception) {
            null
        }
    }
}
