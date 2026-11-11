package com.github.kr328.clash.custom

import android.view.View
import android.widget.Toast
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.LogsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.withClash
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * CUSTOM (CUSTOM.md #31, not upstream): the log level, on the logs page next to the "clear all" button. It is the same
 * setting as the old "log level" entry of the override settings (override.json, persisted); the choices are the same
 * words. A level that is chosen is also given to the running core at once, so the next lines already follow it.
 *
 * Power: a tap shows a dialog and writes one small file; nothing runs on its own.
 */
object LogLevelControl {
    // the same order and words as the old override entry: "do not modify" first
    private val levels = arrayOf<LogMessage.Level?>(
        null,
        LogMessage.Level.Info,
        LogMessage.Level.Warning,
        LogMessage.Level.Error,
        LogMessage.Level.Debug,
        LogMessage.Level.Silent,
    )

    private val texts = intArrayOf(
        R.string.dont_modify,
        R.string.info,
        R.string.warning,
        R.string.error,
        R.string.debug,
        R.string.silent,
    )

    // what the core calls them
    private val names = arrayOf("", "info", "warning", "error", "debug", "silent")

    fun attach(activity: BaseActivity<LogsDesign>, design: LogsDesign) {
        design.root.findViewById<View>(R.id.log_level_view).setOnClickListener {
            activity.launch { choose(activity) }
        }
    }

    private suspend fun choose(activity: BaseActivity<LogsDesign>) {
        val current = try {
            withClash { queryOverride(Clash.OverrideSlot.Persist) }.logLevel
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        val labels = Array(texts.size) { activity.getString(texts[it]) }

        val picked = suspendCancellableCoroutine<Int?> { ctx ->
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.log_level)
                .setSingleChoiceItems(labels, levels.indexOf(current).coerceAtLeast(0)) { d, which ->
                    ctx.resume(which)
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .show()

            dialog.setOnDismissListener { if (!ctx.isCompleted) ctx.resume(null) }
            ctx.invokeOnCancellation { dialog.dismiss() }
        } ?: return

        val saved = try {
            withClash {
                val override = queryOverride(Clash.OverrideSlot.Persist)

                override.logLevel = levels[picked]

                patchOverride(Clash.OverrideSlot.Persist, override)
            }

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

        if (!saved) {
            Toast.makeText(activity, R.string.log_level_failed, Toast.LENGTH_SHORT).show()

            return
        }

        // "do not modify" has no level to give: the profile's own level comes back when it is loaded again
        val live = levels[picked] != null && Remote.broadcasts.clashRunning && try {
            withClash { queryCustomConnections(CustomConnections.SORT_LOG_LEVEL, names[picked]) }

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

        Toast.makeText(
            activity,
            activity.getString(if (live) R.string.log_level_live else R.string.log_level_next, labels[picked]),
            Toast.LENGTH_LONG,
        ).show()
    }
}
