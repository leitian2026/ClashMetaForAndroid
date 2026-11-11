package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.service.R
import java.io.File

// CUSTOM (CUSTOM.md #27, not upstream): when the core refuses a config.yaml and names a line in its error ("line 12:
// ..."), the error that is shown says which line that is and shows it, instead of only the core's sentence.
// Errors that name no line are returned unchanged.
object ConfigErrorText {
    private val reLine = Regex("(?i)\\bline (\\d+)")

    /** The line number the message names, or 0. */
    fun lineOf(message: String?): Int {
        return reLine.find(message ?: return 0)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** A few lines around [line] (1-based) of the file, each with its number; the line itself marked with `>`. Empty when unreadable. */
    fun snippet(file: File, line: Int, around: Int = 1): String {
        if (line <= 0 || !file.isFile) return ""

        return try {
            val lines = file.readLines(Charsets.UTF_8)

            if (line > lines.size + 1) return ""

            val from = maxOf(1, line - around)
            val to = minOf(lines.size, line + around)

            (from..to).joinToString("\n") { n ->
                val text = lines[n - 1].let { if (it.length > 120) it.take(120) + "…" else it }

                (if (n == line) "> " else "  ") + n + " | " + text
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** The exception to throw instead of [e]: the same cause, with the line shown. [dir] holds the config.yaml that was checked. */
    fun explain(context: Context, dir: File, e: Exception): Exception {
        val line = lineOf(e.message)
        val shown = snippet(dir.resolve("config.yaml"), line)

        if (shown.isEmpty()) return e

        return Exception(
            context.getString(R.string.custom_config_error, line, e.message ?: "") + "\n" + shown,
            e,
        )
    }
}
