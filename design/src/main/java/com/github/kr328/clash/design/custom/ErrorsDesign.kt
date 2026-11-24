package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignErrorsBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.common.custom.UpdateGuardRecord
import com.google.android.material.button.MaterialButton
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #21): the "errors and diagnostics" page. Only shows data that already exists.
class ErrorsDesign(context: Context) : Design<ErrorsDesign.Request>(context) {
    enum class Request {
        Refresh, Clear, Copy, IgnoreUpdateGuard,
    }

    private val binding = DesignErrorsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private var shown: String = ""

    // CUSTOM (CUSTOM.md #53): "ignore" / "un-ignore" taps of the config problems block, (id, ignore)
    val issueRequests = kotlinx.coroutines.channels.Channel<Pair<String, Boolean>>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    private val issuesBlock = IssuesBlock(context) { id, ignore -> issueRequests.trySend(id to ignore) }
    private var issuesPlain: String = ""
    private var updateGuardRow: View? = null

    fun request(request: Request) {
        requests.trySend(request)
    }

    suspend fun patch(report: ErrorsReport) {
        val text = ErrorsText.build(context, report)

        withContext(Dispatchers.Main) {
            // no write (and no relayout) when the text did not change
            if (binding.errorsTextView.text.toString() != text) {
                binding.errorsTextView.text = text
            }
            shown = text

            // CUSTOM (CUSTOM.md #53): the config problems block above the text
            issuesBlock.fill(binding.issuesList, report.issues)
            issuesPlain = issuesBlock.plain(report.issues)
        }
    }


    /** CUSTOM #173: the latest subscription node-count warning, placed above the diagnostics summary. */
    suspend fun setUpdateGuard(record: UpdateGuardRecord?, onIgnore: () -> Unit) {
        withContext(Dispatchers.Main) {
            updateGuardRow?.let { binding.issuesList.removeView(it) }
            updateGuardRow = null
            val warning = record?.takeIf { !it.ignored } ?: return@withContext
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(8), dp(8))
            }
            row.addView(
                TextView(context).apply {
                    text = context.getString(R.string.update_guard_summary, warning.oldCount, warning.newCount)
                    textSize = 14f
                    setTextColor(context.resolveThemedColor(android.R.attr.textColorPrimary))
                    setPadding(0, 0, dp(8), 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.update_guard_ignore)
                    isAllCaps = false
                    setOnClickListener { onIgnore() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            binding.issuesList.addView(row, 0)
            updateGuardRow = row
        }
    }

    suspend fun copyAll() {
        withContext(Dispatchers.Main) {
            context.getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("errors", if (issuesPlain.isEmpty()) shown else issuesPlain + "\n\n" + shown))
        }

        showToast(R.string.copied, ToastDuration.Short)
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)
    }
}
