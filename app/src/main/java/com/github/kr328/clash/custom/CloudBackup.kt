package com.github.kr328.clash.custom

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// CUSTOM (CUSTOM.md #32): cloud backup to Jianguoyun (WebDAV), the part without any screen.
//
// On the server (all inside one folder, shared by every device that uses the same account):
//   CMFA-backup/latest.enc                              the newest backup, whoever pushed it
//   CMFA-backup/history/<UTC yyyyMMdd-HHmmss>_<device>.enc   one file per push (the last KEEP are kept)
// A push writes both; a pull takes "latest" or any entry of the history (to go back).
//
// Everything is encrypted with the backup password (BackupCrypto) before it leaves the device, so
// Jianguoyun only sees ciphertext. The three values (account, third-party app password, backup
// password) are kept in this app's private preferences file "custom_cloud_backup" and are not part of
// any backup.
object CloudBackup {
    private const val PREFS = "custom_cloud_backup"
    private const val DIR = "CMFA-backup"
    private const val HISTORY = "CMFA-backup/history"
    private const val LATEST = "CMFA-backup/latest.enc"
    private const val KEEP = 30
    private const val KEEP_SNAPSHOTS = 3

    class Settings(val account: String, val appPassword: String, val backupPassword: String) {
        val complete: Boolean get() = account.isNotBlank() && appPassword.isNotBlank() && backupPassword.isNotEmpty()
    }

    /** One file of the history on the server. [time] is milliseconds since the epoch. */
    class Entry(val file: String, val time: Long, val device: String)

    class Pushed(val profiles: Int, val bytes: Int, val device: String)

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

    /** Model name plus 4 random characters kept on this device, so that two phones of one model differ. */
    fun deviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)

        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 4)
            prefs.edit().putString("device_id", id).apply()
        }

        val model = (Build.MODEL ?: "device").filter { it.isLetterOrDigit() && it.code < 128 }.take(16)

        return (model.ifEmpty { "device" }) + "-" + id
    }

    // ---- server -------------------------------------------------------------------------------

    /** Encrypts the backup and writes the history file and "latest". Blocking network: runs on IO. */
    suspend fun push(context: Context, settings: Settings): Pushed {
        val (zip, count) = BackupManager.exportBytes(context)
        val device = deviceName(context)

        return withContext(Dispatchers.IO) {
            val data = BackupCrypto.encrypt(zip, settings.backupPassword)
            val client = client(settings)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(System.currentTimeMillis())

            client.mkcol(DIR)
            client.mkcol(HISTORY)
            client.put("$HISTORY/${stamp}_$device.enc", data)
            client.put(LATEST, data)

            // keep the newest KEEP; a failure here must not fail the push
            try {
                list(client).drop(KEEP).forEach { client.delete("$HISTORY/${it.file}") }
            } catch (e: Exception) {
                // the next push tries again
            }

            Pushed(count, data.size, device)
        }
    }

    /** The history, newest first. Empty when nothing was pushed yet. */
    suspend fun history(settings: Settings): List<Entry> = withContext(Dispatchers.IO) {
        list(client(settings))
    }

    /** The file of [entry], or of "latest" when [entry] is null; null when it is not on the server. */
    suspend fun download(settings: Settings, entry: Entry?): ByteArray? = withContext(Dispatchers.IO) {
        client(settings).get(if (entry == null) LATEST else "$HISTORY/${entry.file}")
    }

    /** Opens the downloaded file; throws [BackupCrypto.WrongPasswordException] for a wrong backup password. */
    suspend fun decrypt(data: ByteArray, settings: Settings): ByteArray = withContext(Dispatchers.Default) {
        BackupCrypto.decrypt(data, settings.backupPassword)
    }

    /** Checks the account and the app password (the answer to a request for the backup folder). */
    suspend fun check(settings: Settings) {
        withContext(Dispatchers.IO) { client(settings).list(DIR) }
    }

    private fun list(client: WebDavClient): List<Entry> {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        return client.list(HISTORY)
            .filter { it.endsWith(".enc") && it.contains('_') }
            .mapNotNull { file ->
                val stem = file.removeSuffix(".enc")
                val time = format.parse(stem.substringBefore('_'))?.time ?: return@mapNotNull null

                Entry(file, time, stem.substringAfter('_'))
            }
            .sortedByDescending { it.file } // the name starts with the UTC time
    }

    // ---- the safety copy made before a restore (and the "undo" of it) -------------------------

    private fun snapshotDir(context: Context) = context.filesDir.resolve("custom_backups")

    /** Saves this device as it is now (nothing left out) before a restore replaces it. */
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
