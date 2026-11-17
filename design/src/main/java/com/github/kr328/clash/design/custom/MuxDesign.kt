package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.MuxSnapshot
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.kr328.clash.design.databinding.DesignMuxBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #20): the "underlying connections" page, how many sockets the core has open to each node.
class MuxDesign(context: Context) : Design<MuxDesign.Request>(context) {
    enum class Request {
        Refresh,
    }

    private val binding = DesignMuxBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    fun request(request: Request) {
        requests.trySend(request)
    }

    /** [error] is non-null only when querying or parsing the core response failed. */
    suspend fun patch(snapshot: MuxSnapshot?, running: Boolean, error: String? = null) {
        val text = when {
            !running -> context.getString(R.string.connections_not_running)
            error != null -> context.getString(R.string.mux_error, error)
            snapshot == null || (snapshot.mux.isEmpty() && snapshot.nodesOmitted == 0) -> context.getString(R.string.connections_empty)
            else -> MuxText.build(context, snapshot)
        }

        withContext(Dispatchers.Main) {
            // no write (and no relayout) when the text did not change
            if (binding.muxTextView.text.toString() != text) {
                binding.muxTextView.text = text
            }
        }
    }

    init {
        binding.self = this

        binding.muxHintButton.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mux_title)
                .setMessage(R.string.mux_help)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.activityBarLayout.applyFrom(context)
    }
}
