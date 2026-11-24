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




                val nodeLabel = TextView(context).apply {
                    setText(if (nodes.isEmpty()) R.string.iptest_no_nodes else R.string.iptest_node_label)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }

                val spinner = Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, nodes)

                    val saved = try {
                        prefs.getString("ip_node", "") ?: ""
                    } catch (_: Exception) {
                        ""
                    }

                    val index = nodes.indexOf(saved)

                    if (index >= 0) setSelection(index)
                }

                // CUSTOM.md #77: optional direct-only extension probes; choices are remembered and default off.
                val extLabel = TextView(context).apply {
                    setText(R.string.iptest_ext_options_title)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }
                val wsCheck = CheckBox(context).apply {
                    setText(R.string.iptest_ext_option_ws)
                    isChecked = prefs.getBoolean("ip_ext_ws", false)
                }
                val h3Check = CheckBox(context).apply {
                    setText(R.string.iptest_ext_option_h3)
                    isChecked = prefs.getBoolean("ip_ext_h3", false)
                }
                val mtuCheck = CheckBox(context).apply {
                    setText(R.string.iptest_ext_option_mtu)
                    isChecked = prefs.getBoolean("ip_ext_mtu", false)
                }
                val extOptions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    addView(wsCheck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(h3Check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(mtuCheck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }

                val wsPathLabel = TextView(context).apply {
                    setText(R.string.iptest_ext_ws_path_label)
                    textSize = 13f
                    alpha = 0.7f
                    visibility = if (wsCheck.isChecked) View.VISIBLE else View.GONE
                }
                val wsPathInput = EditText(context).apply {
                    setHint(R.string.iptest_ext_ws_path_hint)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setSingleLine(true)
                    visibility = if (wsCheck.isChecked) View.VISIBLE else View.GONE
                    try {
                        setText(prefs.getString("ip_ws_path", "") ?: "")
                    } catch (_: Exception) {
                    }
                }
                wsCheck.setOnCheckedChangeListener { _, checked ->
                    wsPathLabel.visibility = if (checked) View.VISIBLE else View.GONE
                    wsPathInput.visibility = if (checked) View.VISIBLE else View.GONE
                }

                // CUSTOM.md #52: the ports to try; CUSTOM.md #98: separate download addresses for each mode
                val portsLabel = TextView(context).apply {
                    setText(R.string.iptest_ports_label)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }

                val portsInput = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setSingleLine(true)

                    try {
                        setText(prefs.getString("ip_ports", DEFAULT_PORTS) ?: DEFAULT_PORTS)
                    } catch (_: Exception) {
                    }
                }

                // CUSTOM.md #98: each test mode remembers its own download address; the old "ip_url" is ignored.
                val directUrlLabel = TextView(context).apply {
                    setText(R.string.iptest_url_direct_label)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }

                val directUrlInput = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setSingleLine(true)

                    try {
                        setText(prefs.getString("ip_url_direct", DEFAULT_URL_DIRECT) ?: DEFAULT_URL_DIRECT)
                    } catch (_: Exception) {
                    }
                }

                val nodeUrlLabel = TextView(context).apply {
                    setText(R.string.iptest_url_node_label)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }

                val nodeUrlInput = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setSingleLine(true)

                    try {
                        setText(prefs.getString("ip_url_node", DEFAULT_URL_NODE) ?: DEFAULT_URL_NODE)
                    } catch (_: Exception) {
                    }
                }

                // CUSTOM #99/#158: limits are shared by both download-test entry points.
                val currentDownloadLimits = IpDownloadLimit.read(prefs)
                val maxDownloadMbInput = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    setText(currentDownloadLimits.maxMb.toString())
                }
                val maxDownloadSecondsInput = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    setText(currentDownloadLimits.maxSeconds.toString())
                }
                val maxDownloadMbColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        setText(R.string.iptest_download_max_mb)
                        textSize = 13f
                        alpha = 0.7f
                    })
                    addView(maxDownloadMbInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(TextView(context).apply {
                        setText(R.string.iptest_download_direct_limit_hint)
                        textSize = 11f
                        alpha = 0.65f
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                val maxDownloadSecondsColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        setText(R.string.iptest_download_max_seconds)
                        textSize = 13f
                        alpha = 0.7f
                    })
                    addView(maxDownloadSecondsInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                val downloadLimitsRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    addView(maxDownloadMbColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = (8 * density).toInt()
                    })
                    addView(maxDownloadSecondsColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }

                // CUSTOM.md #66: the target address (node mode only), remembered; empty = off
                val targetLabel = TextView(context).apply {
                    setText(R.string.iptest_target_label)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }

                val targetInput = EditText(context).apply {
                    setHint(R.string.iptest_target_hint)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setSingleLine(true)

                    try {
                        setText(prefs.getString("ip_target", "") ?: "")
                    } catch (_: Exception) {
                    }
                }

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

                box.addView(nodeLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                if (nodes.isNotEmpty()) {
                    box.addView(spinner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                box.addView(targetLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(targetInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(portsLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(portsInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(extLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(extOptions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(wsPathLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(wsPathInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(directUrlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(directUrlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(nodeUrlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(nodeUrlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(downloadLimitsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val scroll = ScrollView(context).apply {
                    addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                fun done(mode: String) {
                    if (state.resolving) {
                        android.widget.Toast.makeText(context, R.string.iptest_resolve_wait, android.widget.Toast.LENGTH_SHORT).show()
                        return
                    }
                    val text = state.input.text.toString()
                    val node = if (nodes.isEmpty()) "" else (spinner.selectedItem as? String ?: "")

                    val ports = portsInput.text.toString().trim().ifEmpty { DEFAULT_PORTS }
                    val directUrl = directUrlInput.text.toString().trim().ifEmpty { DEFAULT_URL_DIRECT }
                    val nodeUrl = nodeUrlInput.text.toString().trim().ifEmpty { DEFAULT_URL_NODE }
                    val url = if (mode == "direct") directUrl else nodeUrl
                    val target = targetInput.text.toString().trim()
                    val wsPath = wsPathInput.text.toString().trim()
                    val extensions = if (mode == "direct") buildList {
                        if (wsCheck.isChecked) add("ws")
                        if (h3Check.isChecked) add("h3")
                        if (mtuCheck.isChecked) add("mtu")
                    } else emptyList()

                    try {
                        val edit = prefs.edit()
                        // The input history helper flushes its pending value on every dialog dismissal.
                        edit.putString("ip_resolve_domains", resolve.resolveInput.text.toString())
                        edit.putString("ip_node", node).putString("ip_ports", ports)
                            .putString("ip_url_direct", directUrl).putString("ip_url_node", nodeUrl)
                            .putString("ip_target", target)
                            .putBoolean("ip_ext_ws", wsCheck.isChecked)
                            .putBoolean("ip_ext_h3", h3Check.isChecked)
                            .putBoolean("ip_ext_mtu", mtuCheck.isChecked)
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
                IpDownloadLimit.bindAutoSave(context, prefs, maxDownloadMbInput, maxDownloadSecondsInput)
                val dialogDecor = dialog.window?.decorView
                val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus) dialogInput.updateHeader()
                }
                dialogDecor?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
                dialog.setOnDismissListener {
                    IpDownloadLimit.flushPending(maxDownloadMbInput)
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

    private const val DEFAULT_PORTS = "443 2053 2083 2087 2096 8443"
    private const val DEFAULT_URL_DIRECT = "https://speed.cloudflare.com/__down?bytes=31457280"
    private const val DEFAULT_URL_NODE = "https://proof.ovh.net/files/100Mb.dat"
}

/** CUSTOM #99: preserve the input-history cancellation behavior after the input UI was extracted. */
private fun IpTestDialogInput.dismissOnCancellation(dialog: android.app.Dialog) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        flush()
        dialog.dismiss()
    }
}
