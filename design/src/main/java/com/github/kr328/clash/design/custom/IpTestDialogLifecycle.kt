package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

internal class IpTestDialogLifecycle(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val state: IpTestDialogState,
    private val resolve: IpTestDialogResolve,
    private val options: IpTestDialogOptions,
    private val cancelResolve: (suspend () -> Unit)?,
) {
    fun show(
        ctx: CancellableContinuation<IpTestChoice?>,
        titleRow: View,
        scroll: View,
        dialogInput: IpTestDialogInput,
    ) {
        fun done(mode: String) {
            if (state.resolving) {
                android.widget.Toast.makeText(context, R.string.iptest_resolve_wait, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val text = state.input.text.toString()
            val node = options.selectedNode()

            val ports = options.portsValue()
            val directUrl = options.directUrlValue()
            val nodeUrl = options.nodeUrlValue()
            val url = if (mode == "direct") directUrl else nodeUrl
            val target = options.targetInput.text.toString().trim()
            val wsPath = options.wsPathInput.text.toString().trim()
            val extensions = if (mode == "direct") buildList {
                if (options.wsCheck.isChecked) add("ws")
                if (options.h3Check.isChecked) add("h3")
                if (options.mtuCheck.isChecked) add("mtu")
            } else emptyList()

            try {
                val edit = prefs.edit()
                // The input history helper flushes its pending value on every dialog dismissal.
                edit.putString("ip_resolve_domains", resolve.resolveInput.text.toString())
                edit.putString("ip_node", node).putString("ip_ports", ports)
                    .putString("ip_url_direct", directUrl).putString("ip_url_node", nodeUrl)
                    .putString("ip_target", target)
                    .putBoolean("ip_ext_ws", options.wsCheck.isChecked)
                    .putBoolean("ip_ext_h3", options.h3Check.isChecked)
                    .putBoolean("ip_ext_mtu", options.mtuCheck.isChecked)
                    .putString("ip_ws_path", wsPath)
                    .apply()
            } catch (_: Exception) {
            }

            if (!ctx.isCompleted) ctx.resume(IpTestChoice(mode, text, node, ports, url, target, extensions, wsPath))
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setCustomTitle(titleRow)
            .setView(scroll)
            .setPositiveButton(R.string.iptest_btn_node) { _, _ -> done("node") }
            .setNeutralButton(R.string.iptest_btn_direct) { _, _ -> done("direct") }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
        // CUSTOM #99: save the two limits on focus loss; dismissal flushes the final pending value.
        IpDownloadLimit.bindAutoSave(context, prefs, options.maxDownloadMbInput, options.maxDownloadSecondsInput)
        val dialogDecor = dialog.window?.decorView
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) dialogInput.updateHeader()
        }
        dialogDecor?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
        dialog.setOnDismissListener {
            IpDownloadLimit.flushPending(options.maxDownloadMbInput)
            dialogInput.flush()
            dialogDecor?.viewTreeObserver?.let { observer ->
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
            }
                try {
                    prefs.edit().putString("ip_resolve_domains", resolve.resolveInput.text.toString()).apply()
                } catch (_: Exception) {
                }
                resolve.persistResolvedSnapshot()
                val shouldCancelResolve = state.resolving
                val cancel = cancelResolve
                state.scope.cancel()
                if (shouldCancelResolve && cancel != null) {
                    CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                        try { cancel() } catch (_: Exception) { }
                        if (!ctx.isCompleted) ctx.resume(null)
                    }
                } else if (!ctx.isCompleted) {
                    ctx.resume(null)
                }
            }
        ctx.invokeOnCancellation { dialogInput.dismissOnCancellation(dialog) }
    }
}
