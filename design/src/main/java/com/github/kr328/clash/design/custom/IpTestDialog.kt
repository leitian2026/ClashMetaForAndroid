package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
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
)

// CUSTOM (CUSTOM.md #43, not upstream): the dialog of "test other IPs": the addresses to test (what was typed last
// time is put back), the node to put them into, and the two ways to test. Built in code, no layout file.
object IpTestDialog {
    suspend fun ask(context: Context, prefs: SharedPreferences, nodes: List<String>): IpTestChoice? {
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

                // CUSTOM.md #52: the ports to try and the download address, both remembered
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

                val urlLabel = TextView(context).apply {
                    setText(R.string.iptest_url_label)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }

                val urlInput = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setSingleLine(true)

                    try {
                        setText(prefs.getString("ip_url", DEFAULT_URL) ?: DEFAULT_URL)
                    } catch (_: Exception) {
                    }
                }

                box.addView(hint, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(nodeLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                if (nodes.isNotEmpty()) {
                    box.addView(spinner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                box.addView(portsLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(portsInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(urlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(urlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val scroll = ScrollView(context).apply {
                    addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                fun done(mode: String) {
                    val text = input.text.toString()
                    val node = if (nodes.isEmpty()) "" else (spinner.selectedItem as? String ?: "")

                    val ports = portsInput.text.toString().trim().ifEmpty { DEFAULT_PORTS }
                    val url = urlInput.text.toString().trim().ifEmpty { DEFAULT_URL }

                    try {
                        prefs.edit().putString("ip_text", text).putString("ip_node", node)
                            .putString("ip_ports", ports).putString("ip_url", url).apply()
                    } catch (_: Exception) {
                    }

                    if (!ctx.isCompleted) ctx.resume(IpTestChoice(mode, text, node, ports, url))
                }

                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.iptest_dialog_title)
                    .setView(scroll)
                    .setPositiveButton(R.string.iptest_btn_node) { _, _ -> done("node") }
                    .setNeutralButton(R.string.iptest_btn_direct) { _, _ -> done("direct") }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(null) }
            }
        }
    }

    private const val DEFAULT_PORTS = "443 2053 2083 2087 2096 8443"
    private const val DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=10485760"
}
