package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.MuxSnapshot
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
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

    /** [snapshot] is null while the proxy is not running or the core could not be asked. */
    suspend fun patch(snapshot: MuxSnapshot?, running: Boolean) {
        val text = when {
            !running -> context.getString(R.string.connections_not_running)
            snapshot == null || snapshot.mux.isEmpty() -> context.getString(R.string.connections_empty)
            else -> MuxText.build(context, snapshot.mux)
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

        binding.activityBarLayout.applyFrom(context)
    }
}
