package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.github.kr328.clash.design.R

private const val DEFAULT_PORTS = "443 2053 2083 2087 2096 8443"
private const val DEFAULT_URL_DIRECT = "https://speed.cloudflare.com/__down?bytes=31457280"
private const val DEFAULT_URL_NODE = "https://proof.ovh.net/files/100Mb.dat"

internal class IpTestDialogOptions(
    context: Context,
    prefs: SharedPreferences,
    private val nodes: List<String>,
    density: Float,
    pad: Int,
) {
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


    fun portsValue(): String = portsInput.text.toString().trim().ifEmpty { DEFAULT_PORTS }
    fun directUrlValue(): String = directUrlInput.text.toString().trim().ifEmpty { DEFAULT_URL_DIRECT }
    fun nodeUrlValue(): String = nodeUrlInput.text.toString().trim().ifEmpty { DEFAULT_URL_NODE }
    fun selectedNode(): String = if (nodes.isEmpty()) "" else (spinner.selectedItem as? String ?: "")
}
