package com.github.kr328.clash.design.custom

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    // a small dark pill over the (still visible) settings page; hidden until there is something to say
    private val statusView = TextView(context).apply {
        val density = context.resources.displayMetrics.density

        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = 16 * density
        }
        setPadding((24 * density).toInt(), (14 * density).toInt(), (24 * density).toInt(), (14 * density).toInt())
        visibility = View.GONE
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
            statusView.visibility = View.VISIBLE
        }
    }

    /** Shows a message and returns when the user closes it. */
    suspend fun message(title: CharSequence, message: CharSequence) {
        withContext(Dispatchers.Main) {
            statusView.visibility = View.GONE

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
            statusView.visibility = View.GONE

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
