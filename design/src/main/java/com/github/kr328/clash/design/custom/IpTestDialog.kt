package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import androidx.core.widget.TextViewCompat
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.github.kr328.clash.core.model.IpTestConfigIp
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.design.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** CUSTOM (CUSTOM.md #43): what the user chose in the dialog of "test other IPs". [mode] is "direct" or "node". */
data class IpTestChoice(
    val mode: String,
    val ips: String,
    val node: String,
    val ports: String = "", // CUSTOM.md #52: the ports to try on an address that works (direct)
    val url: String = "", // CUSTOM.md #52: the address the "下载测速" of a row downloads
    val target: String = "", // CUSTOM.md #66: a site the user really uses; through-node tries go to it ("" = the node's test URL)
    val ext: List<String> = emptyList(), // CUSTOM.md #77: direct-only extension probes selected by the user
    val wsPath: String = "", // CUSTOM.md #78: optional WebSocket request path
)

object IpTestDialog {



    suspend fun ask(
        context: Context,
        prefs: SharedPreferences,
        nodes: List<String>,
        readConfig: (suspend () -> IpTestReport?)? = null, // CUSTOM.md #69: null = no "read the IPs of the config" button
        presetIps: String? = null, // CUSTOM.md #156 step 4: one-shot prefill from selected saved IPs
        resolveDomains: (suspend (String) -> IpTestReport?)? = null, // CUSTOM.md step 67
        cancelResolve: (suspend () -> Unit)? = null, // CUSTOM.md step 67: stop the core lookup when this dialog closes
        normalizeIps: (suspend (String) -> IpTestReport?)? = null, // CUSTOM.md #156 step 46: reuse the core IP parser
    ): IpTestChoice? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<IpTestChoice?> { ctx ->
                val density = context.resources.displayMetrics.density
                val pad = (20 * density).toInt()

                val box = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                }

                // CUSTOM #99: title and input controls were moved out to keep ask below the Kotlin coroutine stack limit.
                val dialogInput = IpTestDialogInput.create(context, prefs, presetIps, density, pad)
                val hint = dialogInput.hint
                val titleRow = dialogInput.titleRow
                val inputLabel = dialogInput.inputLabel
                val inputField = dialogInput.inputField
                val inputHeader = dialogInput.inputHeader
                val state = IpTestDialogState(context, dialogInput.input, normalizeIps)
                val resolve = IpTestDialogResolve(context, prefs, state, resolveDomains, density, pad)




                val options = IpTestDialogOptions(context, prefs, nodes, density, pad)

                val readButton = IpTestConfigReadButton.create(context, state.scope, readConfig) { report ->
                    pickConfig(context, report) { text ->
                        // CUSTOM.md #71: the ticked addresses replace what the box held (they were added after it);
                        // nothing ticked leaves the box as it was
                        if (text.isNotEmpty()) {
                            state.scope.launch { state.fillTestBox(text) }
                        }
                    }
                }

                box.addView(hint, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(inputHeader, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(inputField, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (readConfig != null) {
                    box.addView(readButton, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                box.addView(resolve.resolveResultHeader, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(resolve.resolveResultField, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(resolve.resolveLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(resolve.resolveInputField, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(resolve.resolveActions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                box.addView(options.nodeLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                if (nodes.isNotEmpty()) {
                    box.addView(options.spinner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                box.addView(options.targetLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.targetInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.portsLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.portsInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.extLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.extOptions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.wsPathLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.wsPathInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.directUrlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.directUrlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.nodeUrlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.nodeUrlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(options.downloadLimitsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val scroll = ScrollView(context).apply {
                    addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                IpTestDialogLifecycle(context, prefs, state, resolve, options, cancelResolve)
                    .show(ctx, titleRow, scroll, dialogInput)

            }
        }
    }

    // CUSTOM.md #69: the list of what the profile uses, each with the nodes it comes from, to tick; [onPick] gets the
    // ticked addresses. The servers that gave nothing and the limits are told above the list.
    private fun pickConfig(context: Context, report: IpTestReport, onPick: (List<String>) -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val notes = ArrayList<String>()

        for (f in report.configFail) {
            val reason = when (f.reason) {
                "empty" -> context.getString(R.string.iptest_cfg_fail_empty)
                "fake" -> context.getString(R.string.iptest_cfg_fail_fake)
                "loop" -> context.getString(R.string.iptest_cfg_fail_loop)
                "timeout" -> context.getString(R.string.iptest_cfg_fail_timeout)
                "zone" -> context.getString(R.string.iptest_cfg_fail_zone)
                else -> context.getString(R.string.iptest_cfg_fail_failed) + if (f.detail.isNotEmpty()) " (${f.detail})" else ""
            }

            notes += context.getString(R.string.iptest_cfg_fail, f.name, f.nodes.joinToString("、"), reason)
        }

        if (report.config.isEmpty()) notes.add(0, context.getString(R.string.iptest_cfg_none))

        if (notes.isNotEmpty()) {
            box.addView(
                TextView(context).apply {
                    text = notes.joinToString("\n")
                    textSize = 13f
                    alpha = 0.8f
                    setPadding(0, 0, 0, pad / 2)
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        val boxes = ArrayList<Pair<CheckBox, IpTestConfigIp>>()

        for (c in report.config) {
            val via = when (c.via) {
                "hosts" -> context.getString(R.string.iptest_cfg_via_hosts, c.name)
                "alias" -> context.getString(R.string.iptest_cfg_via_alias, c.name)
                "dns" -> context.getString(R.string.iptest_cfg_via_dns, c.name)
                else -> context.getString(R.string.iptest_cfg_via_server)
            }

            val chain = (if (c.chain) " · " + context.getString(R.string.iptest_cfg_chain) else "") +
                (if (c.inUse) " · " + context.getString(R.string.iptest_cfg_in_use) else "") // CUSTOM.md #71

            val box1 = CheckBox(context).apply {
                text = c.ip + "\n" + context.getString(R.string.iptest_cfg_item, c.from.joinToString("、"), via) + chain
                isChecked = true
                textSize = 14f
            }

            boxes += box1 to c

            box.addView(box1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val scroll = ScrollView(context).apply {
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.iptest_cfg_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel) { _, _ -> }

        if (report.config.isNotEmpty()) {
            builder.setPositiveButton(R.string.iptest_cfg_fill) { _, _ ->
                onPick(boxes.filter { it.first.isChecked }.map { it.second.ip })
            }
        }

        builder.show()
    }

}

/** CUSTOM #99: preserve the input-history cancellation behavior after the input UI was extracted. */
private fun IpTestDialogInput.dismissOnCancellation(dialog: android.app.Dialog) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        flush()
        dialog.dismiss()
    }
}
