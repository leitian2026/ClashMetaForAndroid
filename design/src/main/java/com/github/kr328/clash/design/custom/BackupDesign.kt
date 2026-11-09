package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.github.kr328.clash.design.Design
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// CUSTOM (CUSTOM.md #17): the (almost empty) page behind "backup / restore". It only shows what is
// going on and asks for a confirmation; the file picker and the work are in BackupActivity.
class BackupDesign(context: Context) : Design<Unit>(context) {
    private val statusView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    override val root: View = FrameLayout(context).apply {
        addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        )
    }

    suspend fun setStatus(text: CharSequence) {
        withContext(Dispatchers.Main) {
            statusView.text = text
        }
    }

    /** Shows a message and returns when the user closes it. */
    suspend fun message(title: CharSequence, message: CharSequence) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { ctx ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(Unit) }
            }
        }
    }

    suspend fun confirm(title: CharSequence, message: CharSequence): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { ctx ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
            }
        }
    }
}
