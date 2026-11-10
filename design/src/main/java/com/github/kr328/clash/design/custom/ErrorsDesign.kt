package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignErrorsBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #21): the "errors and diagnostics" page. Only shows data that already exists.
class ErrorsDesign(context: Context) : Design<ErrorsDesign.Request>(context) {
    enum class Request {
        Refresh, Clear, Copy,
    }

    private val binding = DesignErrorsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private var shown: String = ""

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
        }
    }

    suspend fun copyAll() {
        withContext(Dispatchers.Main) {
            context.getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("errors", shown))
        }

        showToast(R.string.copied, ToastDuration.Short)
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)
    }
}
