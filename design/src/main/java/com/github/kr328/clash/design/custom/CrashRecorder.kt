package com.github.kr328.clash.design.custom

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.core.model.CrashRecord
import com.github.kr328.clash.core.model.ExitRecord
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// CUSTOM (CUSTOM.md #21): writes down the crashes of this app so the errors page can show them.
//
// Event driven: nothing runs until the app really crashes. The installed handler writes one small text
// file to filesDir/custom/crash/ and then hands the crash on to the handler that was there before, so the
// normal crash behaviour (and any other crash reporter) is unchanged. Only the 10 newest files are kept.
//
// File: line 1 = process, line 2 = thread, line 3 = app version, the rest = the stack trace.
// Crashes of the Go core itself kill the process without reaching Java: Android's own exit record
// (exitRecords, Android 11+) is how those are found.
object CrashRecorder {
    private const val DIR = "custom/crash"
    private const val KEEP = 10
    private const val MAX_TRACE = 12 * 1024

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(app, thread, throwable)
            } catch (_: Throwable) {
                // never let the recorder hide the real crash
            }

            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Application, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }

        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }

        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString().take(MAX_TRACE)
        val now = System.currentTimeMillis()

        File(dir, "$now-${android.os.Process.myPid()}.txt")
            .writeText("${context.currentProcessName}\n${thread.name}\n$version\n$trace")

        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** Newest first. */
    fun read(context: Context): List<CrashRecord> {
        val files = File(context.filesDir, DIR).listFiles() ?: return emptyList()

        return files.sortedByDescending { it.name }.mapNotNull { f ->
            try {
                val time = f.name.substringBefore('-').toLong()
                val lines = f.readText().split('\n', limit = 4)

                CrashRecord(
                    time = time,
                    process = lines.getOrElse(0) { "" },
                    thread = lines.getOrElse(1) { "" },
                    version = lines.getOrElse(2) { "" },
                    trace = lines.getOrElse(3) { "" },
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * Abnormal ends of this app's processes that Android recorded on its own (Android 11+): native crash
     * (the core), not responding, killed for memory. Plain Java crashes are left out, the recorder has them.
     */
    fun exitRecords(context: Context): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()

        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return emptyList()

            am.getHistoricalProcessExitReasons(null, 0, 0).mapNotNull { info ->
                val kind = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                    ApplicationExitInfo.REASON_ANR -> "anr"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "lowmem"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init"
                    else -> return@mapNotNull null
                }

                ExitRecord(info.timestamp, info.processName, kind, info.description ?: "")
            }.take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
