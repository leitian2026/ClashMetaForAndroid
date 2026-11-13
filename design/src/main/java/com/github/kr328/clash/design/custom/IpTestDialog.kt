package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** CUSTOM (CUSTOM.md #43): what the user chose in the dialog of "test other IPs". [mode] is "direct" or "node". */
data class IpTestChoice(val mode: String, val ips: String, val node: String)

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

                box.addView(hint, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(nodeLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                if (nodes.isNotEmpty()) {
                    box.addView(spinner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                fun done(mode: String) {
                    val text = input.text.toString()
                    val node = if (nodes.isEmpty()) "" else (spinner.selectedItem as? String ?: "")

                    try {
                        prefs.edit().putString("ip_text", text).putString("ip_node", node).apply()
                    } catch (_: Exception) {
                    }

                    if (!ctx.isCompleted) ctx.resume(IpTestChoice(mode, text, node))
                }

                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.iptest_dialog_title)
                    .setView(box)
                    .setPositiveButton(R.string.iptest_btn_node) { _, _ -> done("node") }
                    .setNeutralButton(R.string.iptest_btn_direct) { _, _ -> done("direct") }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(null) }
            }
        }
    }
}
