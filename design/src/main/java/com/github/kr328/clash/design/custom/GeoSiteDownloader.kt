package com.github.kr328.clash.design.custom

import android.content.Context
import android.os.SystemClock
import com.github.kr328.clash.design.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// CUSTOM: download a geo file (geosite.dat, and since #16 geoip.metadb / ASN.mmdb) from a user-supplied
// https URL (see CUSTOM.md #6, #16). Runs only when the user presses the button; no timers, no
// background work.

class GeoSiteDownloadException(message: String) : Exception(message)

object GeoSiteDownloader {
    const val STORE_NAME = "custom_geosite"
    const val KEY_URL = "url"

    // lastModified of the installed geosite.dat; used to tell when an app update replaced it
    // (GeoFile.GeoSite.installedKey, the same key).
    const val KEY_INSTALLED_AT = "installed_at"

    private const val MIN_SIZE = 1024L
    private const val PROGRESS_INTERVAL_MS = 300L

    /**
     * Downloads [url] into [dir]/<name of [file]>. The data goes to a temp file first and only
     * replaces the old file after it passes the checks, so a failed download keeps the old file.
     * A copy is kept for GeoFileKeeper, so the file survives an app update.
     * Returns the size in bytes of the installed file.
     */
    suspend fun download(
        context: Context,
        url: String,
        dir: File,
        file: GeoFile,
        onProgress: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        dir.mkdirs()

        val temp = File(dir, file.fileName + ".download")
        val target = File(dir, file.fileName)
        var connection: HttpURLConnection? = null

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ClashMetaForAndroid")

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw GeoSiteDownloadException(
                    context.getString(R.string.geosite_download_err_http, code)
                )
            }

            var total = 0L
            var lastReport = 0L

            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        ensureActive()

                        val n = input.read(buffer)
                        if (n < 0) break

                        output.write(buffer, 0, n)
                        total += n

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onProgress(total)
                        }
                    }
                }
            }

            if (total < MIN_SIZE || !file.looksValid(temp)) {
                throw GeoSiteDownloadException(
                    context.getString(R.string.geofile_download_err_invalid, file.fileName)
                )
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }

            GeoFileKeeper.keep(context, file)

            total
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }
}
