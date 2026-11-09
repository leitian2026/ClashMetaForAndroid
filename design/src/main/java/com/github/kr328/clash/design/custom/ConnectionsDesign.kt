package com.github.kr328.clash.design.custom

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.SimpleItemAnimator
import com.github.kr328.clash.core.custom.IpHistory
import com.github.kr328.clash.core.model.ConnectionItem
import com.github.kr328.clash.core.model.ConnectionSnapshot
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.toBytesString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// CUSTOM (CUSTOM.md #8, #10): the connections page and the IP switch history dialog.
class ConnectionsDesign(context: Context) : Design<ConnectionsDesign.Request>(context) {
    sealed class Request {
        object Refresh : Request()
        object OpenHistory : Request()

        data class Open(val item: ConnectionItem) : Request()
    }

    private val binding = DesignConnectionsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ConnectionAdapter(context) {
        requests.trySend(Request.Open(it))
    }

    override val root: View
        get() = binding.root

    fun request(request: Request) {
        requests.trySend(request)
    }

    /** [snapshot] is null while the proxy is not running or the core could not be asked. */
    suspend fun patch(snapshot: ConnectionSnapshot?, running: Boolean) {
        val items = snapshot?.items ?: emptyList()

        adapter.patchDataSet(adapter::items, items, false, ConnectionItem::id)

        val summary = when {
            !running -> context.getString(R.string.connections_not_running)
            snapshot == null || snapshot.total == 0 -> context.getString(R.string.connections_empty)
            else -> {
                val up = items.sumOf { it.upload }.toBytesString()
                val down = items.sumOf { it.download }.toBytesString()

                if (snapshot.total > items.size) {
                    context.getString(
                        R.string.connections_summary_more,
                        snapshot.total,
                        items.size,
                        up,
                        down,
                    )
                } else {
                    context.getString(R.string.connections_summary, snapshot.total, up, down)
                }
            }
        }

        withContext(Dispatchers.Main) {
            binding.connectionsSummaryView.text = summary
        }
    }

    /** Shows the details of a connection; true when the user chose to close it. */
    suspend fun showDetail(item: ConnectionItem): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { ctx ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(ConnectionText.target(item))
                    .setMessage(ConnectionText.detail(context, item))
                    .setPositiveButton(R.string.connection_close) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(R.string.close) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
            }
        }
    }

    /** Shows the recent switches of the node IP in use, newest first. */
    suspend fun showHistory() {
        val text = withContext(Dispatchers.IO) {
            try {
                // written by the core: <filesDir>/custom/ip_history.json
                context.filesDir.resolve("custom").resolve("ip_history.json").readText()
            } catch (e: Exception) {
                null
            }
        }

        val entries = if (text == null) emptyList() else IpHistory.parse(text).asReversed()

        withContext(Dispatchers.Main) {
            val builder = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ip_history_title)
                .setPositiveButton(R.string.close, null)

            if (entries.isEmpty()) {
                builder.setMessage(R.string.ip_history_empty)
            } else {
                val lines = entries.map {
                    val flag = CountryFlag.of(it.country)
                    val address = if (flag.isEmpty()) it.ip else "$flag ${it.ip}"

                    "${ConnectionText.time(context, it.t, "MM-dd HH:mm:ss")}  $address · ${it.node}"
                }

                builder.setItems(lines.toTypedArray(), null)
            }

            builder.show()
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.applyLinearAdapter(context, adapter)
        // rows change on every refresh (traffic): no fade animation for that
        (binding.recyclerList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }
}
