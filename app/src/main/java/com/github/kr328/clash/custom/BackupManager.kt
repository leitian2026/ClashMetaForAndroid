package com.github.kr328.clash.custom

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.design.custom.GeoFile
import com.github.kr328.clash.design.custom.GeoSiteDownloader
import com.github.kr328.clash.design.custom.SavedIpStore
import com.github.kr328.clash.design.custom.SavedIpRestoreStat
import com.github.kr328.clash.common.custom.CarrierSwitchStore // CUSTOM (CUSTOM.md #17 #169)
import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.custom.ConfigHistory
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// CUSTOM (CUSTOM.md #17): backup and restore of the subscriptions, the app settings and the override
// settings, as one zip file.
//
//   backup.json                   everything except the profile files (see below), including saved IPs (CUSTOM.md #156 step 25)
//   profiles/<uuid>/<file>        the files of every imported profile (config.yaml, providers, ...)
//   history/<uuid>/<ms>.yaml      CUSTOM.md #70: the pinned history versions of a File profile (listed in the
//                                 manifest as "pinned": [ms, ...]; no such key = an older backup)
//
// Runs only when the user picks "export" or "import"; nothing in the background.
//
// NOT part of the backup: the geo files (they can be downloaded again), the app's own caches, the
// per-profile node selections, and the signing keystore.
object BackupManager {
    private const val VERSION = 1
    private const val MANIFEST = "backup.json"
    internal const val PROFILES = "profiles/"

    // "hide app icon" is a switch of the launcher component, not only a setting: it is not restored
    internal val UI_SKIP = setOf("hide_app_icon")

    class BackupException(message: String) : Exception(message)

    class Backup(val root: File, val manifest: JSONObject) {
        val profiles: JSONArray get() = manifest.optJSONArray("profiles") ?: JSONArray()
        val createdAt: Long get() = manifest.optLong("createdAt")
    }

    class RestoreResult(
        val restored: Int,
        val skipped: Int, // already on this device
        val failed: List<String>, // names; kept as pending profiles, can be updated by hand
        val settings: Boolean,
        val override: Boolean,
        val deleted: List<String> = emptyList(), // CUSTOM.md #32: removed by a replace
        val savedIps: SavedIpRestoreStat? = null,
        val carrierRestored: Int = 0,
        val carrierKept: Int = 0,
        val carrierFailed: Int = 0,
        val activeProfileChanged: Boolean = false,
        val activeProfileUuid: String? = null,
        val configurationReloadRequested: Boolean = false,
    )

    // ---- export -------------------------------------------------------------------------------

    /** Writes a plain ZIP local backup into [uri]. */
    suspend fun export(context: Context, uri: Uri): Int {
        return write(context, sync = false) {
            context.contentResolver.openOutputStream(uri, "w")
                ?: throw BackupException("cannot open $uri")
        }
    }

    /**
     * CUSTOM.md #32: the backup for the cloud, in memory, without the settings that belong to this device
     * only (the access control mode and its app list). Returns the zip bytes and the number of subscriptions.
     */
    suspend fun exportBytes(context: Context): Pair<ByteArray, Int> {
        val bytes = ByteArrayOutputStream()
        val count = write(context, sync = true) { bytes }

        return bytes.toByteArray() to count
    }

    /** CUSTOM.md #32: the full backup (nothing left out) into a file; used as the safety copy before a restore. */
    suspend fun exportFile(context: Context, file: File): Int {
        file.parentFile?.mkdirs()

        return write(context, sync = false) { file.outputStream() }
    }

    private suspend fun write(context: Context, sync: Boolean, open: () -> OutputStream): Int {
        val profiles = withProfile { queryAll() }
            .filter { it.imported && it.type != Profile.Type.External }
        val active = withProfile { queryActive() }

        return withContext(Dispatchers.IO) {
            // CUSTOM.md #70: the pinned versions of the profiles the user edits (only File profiles have a history)
            val pinned = profiles.filter { it.type == Profile.Type.File }
                .associate { p -> p.uuid.toString() to ConfigHistory.list(context, p.uuid).filter { it.pinned } }

            val manifest = JSONObject().apply {
                put("version", VERSION)
                put("createdAt", System.currentTimeMillis())
                put("activeProfile", active?.uuid?.toString() ?: JSONObject.NULL)
                put("profiles", JSONArray().apply {
                    profiles.forEach {
                        put(JSONObject().apply {
                            put("uuid", it.uuid.toString())
                            put("name", it.name)
                            put("type", it.type.name)
                            put("source", it.source)
                            put("interval", it.interval)
                            put("ageSecretKey", it.ageSecretKey ?: JSONObject.NULL)
                            pinned[it.uuid.toString()]?.let { list -> put("pinned", JSONArray(list.map { v -> v.at })) } // CUSTOM.md #70
                            CarrierSwitchStore.read(context, it.uuid).takeUnless(CarrierSwitchStore::isEmpty)?.let {
                                put("carrier", CarrierSwitchStore.encode(it))
                            } // CUSTOM (CUSTOM.md #17 #169): optional profile-scoped carrier mappings
                        })
                    }
                })
                put("sync", sync)
                // BACKUP-EXPORT prefs:ui
                put("ui", prefsToJson(context, "ui", skip = UI_SKIP))
                // BACKUP-EXPORT prefs:custom_geosite (download URLs only)
                put("geoUrls", JSONObject().apply {
                    val store = context.getSharedPreferences(
                        GeoSiteDownloader.STORE_NAME,
                        Context.MODE_PRIVATE,
                    )

                    GeoFile.values().forEach { file ->
                        store.getString(file.urlKey, null)?.let { put(file.urlKey, it) }
                    }
                })
                put("override", readOverride(context) ?: JSONObject.NULL)
                // CUSTOM.md #156 step 25: saved IP data is inside the ZIP and encrypted for local and cloud exports.
                // BACKUP-EXPORT prefs:custom_saved_ips
                put("savedIps", JSONObject(SavedIpStore.encodeBackup(context)))
            }

            val output = open()

            ZipOutputStream(output.buffered()).use { zip ->
                profiles.forEach { profile ->
                    // BACKUP-EXPORT dir:imported/*
                    val dir = context.importedDir.resolve(profile.uuid.toString())

                    if (dir.isDirectory) {
                        dir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val name = PROFILES + profile.uuid + "/" +
                                    file.relativeTo(dir).path.replace(File.separatorChar, '/')

                            zip.putNextEntry(ZipEntry(name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }

                // CUSTOM.md #70
                pinned.forEach { (id, list) ->
                    list.forEach { v ->
                        zip.putNextEntry(ZipEntry("history/$id/${v.at}.yaml"))
                        zip.write(v.file.readBytes())
                        zip.closeEntry()
                    }
                }

                BackupExtras.write(context, sync, manifest, zip)

                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            profiles.size
        }
    }

    // ---- import -------------------------------------------------------------------------------

    /** Unpacks the backup in [uri] into a temporary folder and reads its manifest. */
    suspend fun open(context: Context, uri: Uri): Backup {
        return unpack(context) {
            context.contentResolver.openInputStream(uri) ?: throw BackupException("cannot open $uri")
        }
    }

    /** CUSTOM.md #32: same, from bytes in memory (the decrypted file from the cloud). */
    suspend fun open(context: Context, bytes: ByteArray): Backup {
        return unpack(context) { ByteArrayInputStream(bytes) }
    }

    /** CUSTOM.md #32: same, from a file (the safety copy made before a restore). */
    suspend fun open(context: Context, file: File): Backup {
        return unpack(context) { file.inputStream() }
    }

    private suspend fun unpack(context: Context, open: () -> InputStream): Backup = withContext(Dispatchers.IO) {
        val root = context.cacheDir.resolve("custom_restore")

        root.deleteRecursively()
        root.mkdirs()

        val input = open()

        ZipInputStream(input.buffered()).use { zip ->
            val rootPath = root.canonicalPath + File.separator

            while (true) {
                val entry = zip.nextEntry ?: break

                if (entry.isDirectory) continue

                val target = File(root, entry.name).canonicalFile

                // a zip entry must never leave the folder (zip slip)
                if (!target.path.startsWith(rootPath)) {
                    throw BackupException("bad entry ${entry.name}")
                }

                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }

        val manifestFile = File(root, MANIFEST)

        if (!manifestFile.isFile) {
            throw BackupException("not a backup file (no $MANIFEST)")
        }

        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))

        if (manifest.optInt("version") !in 1..VERSION) {
            throw BackupException("unsupported backup version ${manifest.optInt("version")}")
        }

        Backup(root, manifest)
    }

    /** Puts the backup back. Subscriptions that already exist on this device are skipped. */
    // CUSTOM.md #32: [replace] makes this device the same as the backup (subscriptions that are not in the backup
    // are deleted, the backup's choice of the active subscription wins); [applySettings] = false leaves the app,
    // service and override settings of this device alone (subscriptions only); [replaceFiles]: a file subscription
    // of this device that is also in the backup gets the backup's content.
    suspend fun restore(
        context: Context,
        backup: Backup,
        replace: Boolean = false,
        applySettings: Boolean = true,
        replaceFiles: Boolean = replace,
    ): RestoreResult = BackupRestore.restore(
        context, backup, replace, applySettings, replaceFiles,
    )

    /** CUSTOM.md #32: the names of the subscriptions on this device that a replace would remove. */
    suspend fun planDeletes(backup: Backup): List<String> {
        return withProfile { queryAll() }
            .filter { it.imported && it.type != Profile.Type.External && !inBackup(backup, it) }
            .map { it.name }
    }

    /** CUSTOM.md #36: how the subscriptions of a backup relate to the ones on this device (names, in the order of the backup). */
    class Comparison(
        val added: List<String>, // not on this device: will be added
        val same: List<String>, // already here (same name, type and address): this device's stays
        val replaced: List<String>, // file subscriptions that are already here: the backup's content replaces it
    )

    suspend fun compare(backup: Backup): Comparison {
        val local = withProfile { queryAll() }
        val added = ArrayList<String>()
        val same = ArrayList<String>()
        val replaced = ArrayList<String>()
        val list = backup.profiles

        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val name = item.getString("name")
            val type = item.getString("type")
            val source = item.getString("source")

            val here = local.any { it.name == name && it.type.name == type && it.source == source }

            when {
                !here -> added.add(name)
                type == Profile.Type.File.name -> replaced.add(name)
                else -> same.add(name)
            }
        }

        return Comparison(added, same, replaced)
    }

    // same name, type and address = the same subscription (the rule of the "skip" above)
    internal fun inBackup(backup: Backup, profile: Profile): Boolean {
        val list = backup.profiles

        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)

            if (item.getString("name") == profile.name &&
                item.getString("type") == profile.type.name &&
                item.getString("source") == profile.source
            ) return true
        }

        return false
    }

    /** Removes the unpacked files (when the user cancels). */
    suspend fun discard(backup: Backup) {
        withContext(Dispatchers.IO) { backup.root.deleteRecursively() }
    }

    // ---- settings -----------------------------------------------------------------------------

    private fun serviceToJson(store: ServiceStore, sync: Boolean): JSONObject = JSONObject().apply {
        put("bypassPrivateNetwork", store.bypassPrivateNetwork)
        // CUSTOM.md #32: which apps go through the VPN depends on what is installed on this device,
        // so the cloud backup leaves it out (and a restore leaves the local value alone)
        if (!sync) {
            put("accessControlMode", store.accessControlMode.name)
            put("accessControlPackages", JSONArray(store.accessControlPackages.toList()))
        }
        put("dnsHijacking", store.dnsHijacking)
        put("systemProxy", store.systemProxy)
        put("allowBypass", store.allowBypass)
        put("allowIpv6", store.allowIpv6)
        put("tunStackMode", store.tunStackMode)
        put("dynamicNotification", store.dynamicNotification)
        put("showIp", store.showIp) // CUSTOM
    }

    internal fun restoreSettings(context: Context, manifest: JSONObject): Boolean {
        var any = false

        manifest.optJSONObject("service")?.let { json ->
            val store = ServiceStore(context)

            if (json.has("bypassPrivateNetwork")) store.bypassPrivateNetwork = json.getBoolean("bypassPrivateNetwork")
            if (json.has("accessControlMode")) {
                AccessControlMode.values().firstOrNull { it.name == json.getString("accessControlMode") }
                    ?.let { store.accessControlMode = it }
            }
            json.optJSONArray("accessControlPackages")?.let { array ->
                store.accessControlPackages = (0 until array.length()).map { array.getString(it) }.toSet()
            }
            if (json.has("dnsHijacking")) store.dnsHijacking = json.getBoolean("dnsHijacking")
            if (json.has("systemProxy")) store.systemProxy = json.getBoolean("systemProxy")
            if (json.has("allowBypass")) store.allowBypass = json.getBoolean("allowBypass")
            if (json.has("allowIpv6")) store.allowIpv6 = json.getBoolean("allowIpv6")
            if (json.has("tunStackMode")) store.tunStackMode = json.getString("tunStackMode")
            if (json.has("dynamicNotification")) store.dynamicNotification = json.getBoolean("dynamicNotification")
            store.showIp = json.optBoolean("showIp", true) // CUSTOM: older backups default to enabled

            any = true
        }

        // BACKUP-IMPORT prefs:ui
        manifest.optJSONObject("ui")?.let { json ->
            val editor = context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()

            json.keys().forEach { key ->
                if (key !in UI_SKIP) putJson(editor, key, json.getJSONObject(key))
            }

            editor.apply()
            any = true
        }

        // BACKUP-IMPORT prefs:custom_geosite
        manifest.optJSONObject("geoUrls")?.let { json ->
            val editor = context.getSharedPreferences(
                GeoSiteDownloader.STORE_NAME,
                Context.MODE_PRIVATE,
            ).edit()

            GeoFile.values().forEach { file ->
                if (json.has(file.urlKey)) editor.putString(file.urlKey, json.getString(file.urlKey))
            }

            editor.apply()
        }

        return any
    }

    // a value of a SharedPreferences file: {"t": type, "v": value}
    private fun prefsToJson(context: Context, name: String, skip: Set<String>): JSONObject {
        val result = JSONObject()

        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (key in skip) return@forEach

            val item = JSONObject()

            when (value) {
                is Boolean -> item.put("t", "b").put("v", value)
                is Int -> item.put("t", "i").put("v", value)
                is Long -> item.put("t", "l").put("v", value)
                is Float -> item.put("t", "f").put("v", value.toDouble())
                is String -> item.put("t", "s").put("v", value)
                is Set<*> -> item.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> return@forEach
            }

            result.put(key, item)
        }

        return result
    }

    private fun putJson(editor: android.content.SharedPreferences.Editor, key: String, item: JSONObject) {
        when (item.getString("t")) {
            "b" -> editor.putBoolean(key, item.getBoolean("v"))
            "i" -> editor.putInt(key, item.getInt("v"))
            "l" -> editor.putLong(key, item.getLong("v"))
            "f" -> editor.putFloat(key, item.getDouble("v").toFloat())
            "s" -> editor.putString(key, item.getString("v"))
            "ss" -> {
                val array = item.getJSONArray("v")

                editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
            }
        }
    }

    // ---- override settings --------------------------------------------------------------------

    // The core keeps the persistent override settings in <home>/override.json (home = filesDir/clash).
    private fun overrideFile(context: Context) = context.clashDir.resolve("override.json")

    private fun readOverride(context: Context): String? {
        return try {
            overrideFile(context).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    internal fun writeOverride(context: Context, text: String): Boolean {
        return try {
            JSONObject(text) // must be JSON, or the core would fall back to its defaults

            val file = overrideFile(context)
            val temp = File(file.parentFile, "override.json.restore")

            temp.writeText(text, Charsets.UTF_8)

            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }

            true
        } catch (e: Exception) {
            false
        }
    }
}
