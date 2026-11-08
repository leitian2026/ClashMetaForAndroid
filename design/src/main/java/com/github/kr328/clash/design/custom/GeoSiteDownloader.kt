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

// CUSTOM: download geosite.dat from a user-supplied https URL (see CUSTOM.md #6).
// Runs only when the user presses the button; no timers, no background work.

class GeoSiteDownloadException(message: String) : Exception(message)

object GeoSiteDownloader {
    private const val TARGET_NAME = "geosite.dat"
    private const val TEMP_NAME = "geosite.dat.download"
    private const val MIN_SIZE = 1024L
    private const val PROGRESS_INTERVAL_MS = 300L

    // geosite.dat is a protobuf GeoSiteList: its first byte is the tag of field 1 (0x0A).
    private const val PROTOBUF_FIRST_BYTE = 0x0A

    /**
     * Downloads [url] into [dir]/geosite.dat. The data goes to a temp file first and only
     * replaces the old file after it passes the checks, so a failed download keeps the old file.
     * Returns the size in bytes of the installed file.
     */
    suspend fun download(
        context: Context,
        url: String,
        dir: File,
        onProgress: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        dir.mkdirs()

        val temp = File(dir, TEMP_NAME)
        val target = File(dir, TARGET_NAME)
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

            if (total < MIN_SIZE || temp.inputStream().use { it.read() } != PROTOBUF_FIRST_BYTE) {
                throw GeoSiteDownloadException(
                    context.getString(R.string.geosite_download_err_invalid)
                )
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }

            total
        } finally {
            connection?.disconnect()
            temp.delete()
        }
    }
}
