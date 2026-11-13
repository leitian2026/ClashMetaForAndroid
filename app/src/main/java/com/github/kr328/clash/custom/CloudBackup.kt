package com.github.kr328.clash.custom

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// CUSTOM (CUSTOM.md #32): cloud backup to Jianguoyun (WebDAV), the part without any screen.
//
// On the server (one folder, shared by every device that uses the same account):
//   CMFA-backup/history/<UTC yyyyMMdd-HHmmss>_<device id>_<device name>.enc
// One file per push. Every device keeps its own last KEEP_PER_DEVICE files (a push deletes that
// device's older ones), so a device that pushes often never pushes out another device's backups.
// A pull lists all of them (time, device name) and takes the one the user picks. (r35 also wrote
// "latest.enc"; r36 no longer does, and removes the old one on the next push.)
//
// Everything is encrypted with the backup password (BackupCrypto) before it leaves the device, so
// Jianguoyun only sees ciphertext. The three values (account, third-party app password, backup
// password) are kept in this app's private preferences file "custom_cloud_backup" and are not part of
// any backup.
object CloudBackup {
    private const val PREFS = "custom_cloud_backup"
    private const val DIR = "CMFA-backup"
    private const val HISTORY = "CMFA-backup/history"
    private const val OLD_LATEST = "CMFA-backup/latest.enc"
    private const val KEEP_PER_DEVICE = 5
    private const val KEEP_SNAPSHOTS = 3

    class Settings(val account: String, val appPassword: String, val backupPassword: String) {
        val complete: Boolean get() = account.isNotBlank() && appPassword.isNotBlank() && backupPassword.isNotEmpty()
    }

    /** One backup on the server. [file] is the plain file name (not percent-encoded); [time] is milliseconds since the epoch. */
    class Entry(val file: String, val time: Long, val deviceId: String, val deviceName: String)

    class Pushed(val profiles: Int, val bytes: Int, val deviceName: String)

    // ---- settings -----------------------------------------------------------------------------

    fun load(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return Settings(
            prefs.getString("account", "") ?: "",
            prefs.getString("app_password", "") ?: "",
            prefs.getString("backup_password", "") ?: "",
        )
    }

    fun save(context: Context, settings: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("account", settings.account.trim())
            .putString("app_password", settings.appPassword.trim())
            .putString("backup_password", settings.backupPassword) // spaces can be part of a password
            .apply()
    }

    fun client(settings: Settings) = WebDavClient(settings.account, settings.appPassword)

    /** 4 random characters kept on this device: tells two phones with the same name apart. */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)

        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 4)
            prefs.edit().putString("device_id", id).apply()
        }

        return id
    }

    /** The name of this phone as set in the system settings ("device name"); the model when it cannot be read. */
    fun deviceName(context: Context): String {
        val system = try {
            AndroidSettings.Global.getString(context.contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }

        val name = (system?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "device")
            .map { if (it.isISOControl() || it in "/\\:*?\"<>|") '-' else it } // fine in a file name
            .joinToString("")
            .trim()
            .take(40)

        return name.ifEmpty { "device" }
    }

    // ---- server -------------------------------------------------------------------------------

    /** Encrypts the backup and writes one history file. Blocking network: runs on IO. */
    suspend fun push(context: Context, settings: Settings): Pushed {
        val (zip, count) = BackupManager.exportBytes(context)
        val id = deviceId(context)
        val name = deviceName(context)

        return withContext(Dispatchers.IO) {
            val data = BackupCrypto.encrypt(zip, settings.backupPassword)
            val client = client(settings)
            val stamp = timeFormat().format(System.currentTimeMillis())

            client.mkcol(DIR)
            client.mkcol(HISTORY)
            client.put(path("${stamp}_${id}_$name.enc"), data)

            // this device's older backups beyond the newest KEEP_PER_DEVICE, and the "latest.enc" of r35;
            // a failure here must not fail the push (the next push tries again)
            try {
                list(client).filter { it.deviceId == id }.drop(KEEP_PER_DEVICE)
                    .forEach { client.delete(path(it.file)) }
            } catch (e: Exception) {
                // ignored
            }

            try {
                client.delete(OLD_LATEST)
            } catch (e: Exception) {
                // ignored
            }

            Pushed(count, data.size, name)
        }
    }

    /** All backups on the server, newest first. Empty when nothing was pushed yet. */
    suspend fun history(settings: Settings): List<Entry> = withContext(Dispatchers.IO) {
        list(client(settings))
    }

    /** The file of [entry]; null when it is not on the server (any more). */
    suspend fun download(settings: Settings, entry: Entry): ByteArray? = withContext(Dispatchers.IO) {
        client(settings).get(path(entry.file))
    }

    /** Deletes one backup from the server (CUSTOM.md #36). */
    suspend fun delete(settings: Settings, entry: Entry) {
        withContext(Dispatchers.IO) { client(settings).delete(path(entry.file)) }
    }

    /** Opens the downloaded file; throws [BackupCrypto.WrongPasswordException] for a wrong backup password. */
    suspend fun decrypt(data: ByteArray, settings: Settings): ByteArray = withContext(Dispatchers.Default) {
        BackupCrypto.decrypt(data, settings.backupPassword)
    }

    /** Checks the account and the app password (the answer to a request for the backup folder). */
    suspend fun check(settings: Settings) {
        withContext(Dispatchers.IO) { client(settings).list(DIR) }
    }

    // the file name goes into the URL percent-encoded (device names can have any characters)
    private fun path(file: String) = "$HISTORY/${Uri.encode(file)}"

    private fun timeFormat() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun list(client: WebDavClient): List<Entry> {
        val format = timeFormat()

        return client.list(HISTORY)
            .filter { it.endsWith(".enc") && it.contains('_') }
            .mapNotNull { file ->
                // <time>_<id>_<name>.enc; a file of r35 is <time>_<model-id>.enc (no separate name)
                val parts = file.removeSuffix(".enc").split('_', limit = 3)
                val time = format.parse(parts[0])?.time ?: return@mapNotNull null

                if (parts.size >= 3) {
                    Entry(file, time, parts[1], parts[2])
                } else {
                    Entry(file, time, parts[1], parts[1])
                }
            }
            .sortedByDescending { it.file } // the name starts with the UTC time
    }

    // ---- the safety copy made before a restore (and the "undo" of it) -------------------------

    private fun snapshotDir(context: Context) = context.filesDir.resolve("custom_backups")

    /** Saves this device as it is now (nothing left out) before a restore changes it. */
    suspend fun snapshot(context: Context) {
        val dir = snapshotDir(context)

        BackupManager.exportFile(context, File(dir, "pre-restore-${System.currentTimeMillis()}.zip"))

        withContext(Dispatchers.IO) {
            dir.listFiles { f -> f.name.startsWith("pre-restore-") }
                ?.sortedByDescending { it.name }
                ?.drop(KEEP_SNAPSHOTS)
                ?.forEach { it.delete() }
        }
    }

    /** The newest safety copy, or null. */
    fun lastSnapshot(context: Context): File? {
        return snapshotDir(context).listFiles { f -> f.name.startsWith("pre-restore-") && f.name.endsWith(".zip") }
            ?.maxByOrNull { it.name }
    }

    /** When the safety copy was made (milliseconds), read from its name. */
    fun snapshotTime(file: File): Long {
        return file.name.removePrefix("pre-restore-").removeSuffix(".zip").toLongOrNull() ?: file.lastModified()
    }
}
