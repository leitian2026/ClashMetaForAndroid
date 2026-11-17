package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** CUSTOM (CUSTOM.md #43): what the user chose in the dialog of "test other IPs". [mode] is "direct" or "node". */
data class IpTestChoice(
    val mode: String,
    val ips: String,
    val node: String,
    val ports: String = "", // CUSTOM.md #52: the ports to try on an address that works (direct)
    val url: String = "", // CUSTOM.md #52: the address the "测速" of a row downloads
    val target: String = "", // CUSTOM.md #66: a site the user really uses; through-node tries go to it ("" = the node's test URL)
)

// CUSTOM (CUSTOM.md #43, not upstream): the dialog of "test other IPs": the addresses to test (what was typed last
// time is put back), the node to put them into, and the two ways to test. Built in code, no layout file.
object IpTestDialog {
    suspend fun ask(
        context: Context,
        prefs: SharedPreferences,
        nodes: List<String>,
        readConfig: (suspend () -> IpTestReport?)? = null, // CUSTOM.md #69: null = no "read the IPs of the config" button
    ): IpTestChoice? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<IpTestChoice?> { ctx ->
                val density = context.resources.displayMetrics.density
                val pad = (20 * density).toInt()

                val box = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                }

                val hint = TextView(context).apply {
                    setText(R.string.iptest_dialog_hint)
                    textSize = 13f
                    alpha = 0.7f
                }

                val input = EditText(context).apply {
                    setHint(R.string.iptest_ips_hint)
                    minLines = 4
                    maxLines = 8
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    isVerticalScrollBarEnabled = true

                    try {
                        setText(prefs.getString("ip_text", "") ?: "")
                    } catch (_: Exception) {
                    }
                }

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

                // CUSTOM.md #69: reads the addresses of the profile (one tap, nothing runs by itself); the scope is
                // cancelled when the dialog goes away, so a late answer does not open a second dialog
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

                val readButton = MaterialButton(context).apply {
                    setText(R.string.iptest_cfg_button)
                    isAllCaps = false

                    setOnClickListener {
                        val read = readConfig ?: return@setOnClickListener

                        isEnabled = false
                        setText(R.string.iptest_cfg_reading)

                        scope.launch {
                            val report = try {
                                read()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }

                            isEnabled = true
                            setText(R.string.iptest_cfg_button)

                            if (report == null) {
                                android.widget.Toast.makeText(context, R.string.iptest_cfg_error, android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                pickConfig(context, report) { text ->
                                    // CUSTOM.md #71: the ticked addresses replace what the box held (they were added after it);
                                    // nothing ticked leaves the box as it was
                                    if (text.isNotEmpty()) {
                                        input.setText(text.joinToString("\n"))
                                        input.setSelection(input.text.length)
                                    }
                                }
                            }
                        }
                    }
                }

                box.addView(hint, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (readConfig != null) {
                    box.addView(readButton, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                box.addView(nodeLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                if (nodes.isNotEmpty()) {
                    box.addView(spinner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                box.addView(targetLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(targetInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(portsLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(portsInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(directUrlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(directUrlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(nodeUrlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(nodeUrlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val scroll = ScrollView(context).apply {
                    addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                fun done(mode: String) {
                    val text = input.text.toString()
                    val node = if (nodes.isEmpty()) "" else (spinner.selectedItem as? String ?: "")

                    val ports = portsInput.text.toString().trim().ifEmpty { DEFAULT_PORTS }
                    val directUrl = directUrlInput.text.toString().trim().ifEmpty { DEFAULT_URL_DIRECT }
                    val nodeUrl = nodeUrlInput.text.toString().trim().ifEmpty { DEFAULT_URL_NODE }
                    val url = if (mode == "direct") directUrl else nodeUrl
                    val target = targetInput.text.toString().trim()

                    try {
                        prefs.edit().putString("ip_text", text).putString("ip_node", node)
                            .putString("ip_ports", ports)
                            .putString("ip_url_direct", directUrl).putString("ip_url_node", nodeUrl)
                            .putString("ip_target", target).apply()
                    } catch (_: Exception) {
                    }

                    if (!ctx.isCompleted) ctx.resume(IpTestChoice(mode, text, node, ports, url, target))
                }

                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.iptest_dialog_title)
                    .setView(scroll)
                    .setPositiveButton(R.string.iptest_btn_node) { _, _ -> done("node") }
                    .setNeutralButton(R.string.iptest_btn_direct) { _, _ -> done("direct") }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener {
                        scope.cancel()

                        if (!ctx.isCompleted) ctx.resume(null)
                    }
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

        if (report.configOmitted > 0) notes += context.getString(R.string.iptest_cfg_omitted, report.configOmitted)
        if (report.configFamily > 0) notes += context.getString(R.string.iptest_cfg_family, report.configFamily)

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
    private const val DEFAULT_URL_DIRECT = "https://speed.cloudflare.com/__down?bytes=10485760"
    private const val DEFAULT_URL_NODE = "https://proof.ovh.net/files/100Mb.dat"
}
