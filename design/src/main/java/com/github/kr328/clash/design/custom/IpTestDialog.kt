package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import androidx.core.widget.TextViewCompat
import android.view.View
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
)

private data class ResolvedIpEntry(val ip: String, var checked: Boolean)
private data class ResolvedIpGroup(val domain: String, val ips: MutableList<ResolvedIpEntry>)
private data class ResolvedIpSnapshot(
    val groups: List<ResolvedIpGroup> = emptyList(),
    val hints: List<String> = emptyList(),
    val timestamp: Long = 0L,
)

object IpTestDialog {
    private const val RESOLVED_SNAPSHOT_KEY = "ip_resolved_last"

    private fun readResolvedSnapshot(prefs: SharedPreferences): ResolvedIpSnapshot {
        return try {
            val raw = prefs.getString(RESOLVED_SNAPSHOT_KEY, null) ?: return ResolvedIpSnapshot()
            val json = JSONObject(raw)
            val groupsJson = json.optJSONArray("groups") ?: JSONArray()
            val groups = ArrayList<ResolvedIpGroup>()
            for (i in 0 until groupsJson.length()) {
                val groupJson = groupsJson.optJSONObject(i) ?: continue
                val domain = groupJson.optString("domain")
                if (domain.isBlank()) continue
                val ipsJson = groupJson.optJSONArray("ips") ?: JSONArray()
                val ips = ArrayList<ResolvedIpEntry>()
                for (j in 0 until ipsJson.length()) {
                    val ipJson = ipsJson.optJSONObject(j) ?: continue
                    val ip = ipJson.optString("ip")
                    if (ip.isNotBlank()) ips += ResolvedIpEntry(ip, ipJson.optBoolean("checked", true))
                }
                if (ips.isNotEmpty()) groups += ResolvedIpGroup(domain, ips)
            }
            val hintsJson = json.optJSONArray("hints") ?: JSONArray()
            val hints = List(hintsJson.length()) { hintsJson.optString(it) }.filter { it.isNotBlank() }
            ResolvedIpSnapshot(groups, hints, json.optLong("timestamp", 0L))
        } catch (_: Exception) {
            ResolvedIpSnapshot()
        }
    }

    private fun writeResolvedSnapshot(
        prefs: SharedPreferences,
        groups: List<ResolvedIpGroup>,
        hints: List<String>,
        timestamp: Long,
    ) {
        try {
            val groupsJson = JSONArray()
            for (group in groups) {
                val ipsJson = JSONArray()
                for (entry in group.ips) {
                    ipsJson.put(JSONObject().put("ip", entry.ip).put("checked", entry.checked))
                }
                groupsJson.put(JSONObject().put("domain", group.domain).put("ips", ipsJson))
            }
            val json = JSONObject()
                .put("groups", groupsJson)
                .put("hints", JSONArray(hints))
                .put("timestamp", timestamp)
            prefs.edit().putString(RESOLVED_SNAPSHOT_KEY, json.toString()).apply()
        } catch (_: Exception) {
        }
    }
    suspend fun ask(
        context: Context,
        prefs: SharedPreferences,
        nodes: List<String>,
        readConfig: (suspend () -> IpTestReport?)? = null, // CUSTOM.md #69: null = no "read the IPs of the config" button
        presetIps: String? = null, // CUSTOM.md #156 step 4: one-shot prefill from selected saved IPs
        resolveDomains: (suspend (String) -> IpTestReport?)? = null, // CUSTOM.md step 67
        cancelResolve: (suspend () -> Unit)? = null, // CUSTOM.md step 67: stop the core lookup when this dialog closes
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
                    visibility = View.GONE
                }

                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad, 0, pad, 0)
                }
                val titleText = TextView(context).apply {
                    setText(R.string.iptest_dialog_title)
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val dialogWidth = minOf(context.resources.displayMetrics.widthPixels, (560 * density).toInt()) -
                        (2 * pad) - (60 * density).toInt()
                    maxWidth = dialogWidth.coerceAtLeast((80 * density).toInt())
                }
                var hintExpanded = false
                val helpButton = TextView(context).apply {
                    setText(R.string.iptest_dialog_help)
                    textSize = 14f
                    isClickable = true
                    isFocusable = true
                    setPadding((8 * density).toInt(), (8 * density).toInt(), 0, (8 * density).toInt())
                    setOnClickListener {
                        hintExpanded = !hintExpanded
                        hint.visibility = if (hintExpanded) View.VISIBLE else View.GONE
                    }
                }
                titleRow.addView(titleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                titleRow.addView(helpButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val inputLabel = TextView(context).apply {
                    setText(R.string.iptest_test_box_label)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }

                val inputField = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_iptest_outlined_field, null, false) as TextInputLayout
                val input = inputField.findViewById<TextInputEditText>(R.id.iptest_outlined_input).apply {
                    setHint(R.string.iptest_ips_hint)
                    minLines = 1
                    maxLines = 12
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    isVerticalScrollBarEnabled = true
                    setHorizontallyScrolling(false)

                    try {
                        // CUSTOM.md #156 step 4: a supplied preset wins for this dialog only; otherwise keep the manual history.
                        setText(presetIps ?: (prefs.getString("ip_text", "") ?: ""))
                    } catch (_: Exception) {
                    }
                }

                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

                // CUSTOM.md step 67: domain lookup is separate from the existing IP-test input.
                val resolveLabel = TextView(context).apply {
                    setText(R.string.iptest_resolve_domain_label)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }

                val resolveInputField = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_iptest_outlined_field, null, false) as TextInputLayout
                val resolveInput = resolveInputField.findViewById<TextInputEditText>(R.id.iptest_outlined_input).apply {
                    setHint(R.string.iptest_resolve_domain_hint)
                    minLines = 1
                    maxLines = 12
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    isVerticalScrollBarEnabled = true
                    setHorizontallyScrolling(false)
                    try {
                        setText(prefs.getString("ip_resolve_domains", "") ?: "")
                    } catch (_: Exception) {
                    }
                }

                val resolveResultBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                }
                val resolveStatusBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val resolveRowsBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val resolveResultPlaceholder = TextView(context).apply {
                    setText(R.string.iptest_resolve_not_yet)
                    textSize = 13f
                    alpha = 0.7f
                }
                resolveRowsBox.addView(resolveResultPlaceholder)
                resolveResultBox.addView(resolveStatusBox)
                resolveResultBox.addView(resolveRowsBox)
                val resolveResultOutlineTemplate = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_iptest_outlined_field, null, false) as TextInputLayout
                val resultOutlineBackground = resolveResultOutlineTemplate
                    .findViewById<TextInputEditText>(R.id.iptest_outlined_input)
                    .background ?: throw IllegalStateException("Outlined field background is missing")
                resolveResultBox.background = resultOutlineBackground.constantState?.newDrawable()?.mutate()
                    ?: resultOutlineBackground.mutate()
                val resolveResultField = resolveResultBox
                val resolveResultLabel = TextView(context).apply {
                    setText(R.string.iptest_resolve_results_title)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, pad / 2, 0, 0)
                }
                val resolveTimeLabel = TextView(context).apply {
                    textSize = 11f
                    alpha = 0.65f
                    visibility = View.GONE
                    setPadding(0, 0, (8 * density).toInt(), 0)
                }
                val restoredResolved = readResolvedSnapshot(prefs)
                val resolvedGroups = restoredResolved.groups.map { group ->
                    ResolvedIpGroup(group.domain, group.ips.map { ResolvedIpEntry(it.ip, it.checked) }.toMutableList())
                }.toMutableList()
                val latestResolveHints = restoredResolved.hints.toMutableList()
                var lastResolvedAt = restoredResolved.timestamp
                val resolveChecks = ArrayList<CheckBox>()
                var hasResolveRows = false
                var hasResolveStatus = false
                var resolving = false

                val fillResolvedButton = MaterialButton(context).apply {
                    setText(R.string.iptest_resolve_fill)
                    isAllCaps = false
                    isEnabled = false
                    setOnClickListener {
                        val selected = resolveChecks.filter { it.isChecked }.mapNotNull { it.tag as? String }.distinct()
                        if (selected.isEmpty()) {
                            android.widget.Toast.makeText(context, R.string.iptest_resolve_none_selected, android.widget.Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        input.setText(selected.joinToString("\n"))
                        input.setSelection(input.text?.length ?: 0)
                    }
                }

                fun renderResolvedRows() {
                    resolveRowsBox.removeAllViews()
                    resolveChecks.clear()
                    hasResolveRows = resolvedGroups.any { it.ips.isNotEmpty() }
                    if (!hasResolveRows) {
                        resolveRowsBox.addView(resolveResultPlaceholder)
                    } else {
                        for (group in resolvedGroups) {
                            if (group.ips.isEmpty()) continue
                            resolveRowsBox.addView(TextView(context).apply {
                                text = group.domain
                                textSize = 14f
                                setPadding(0, pad / 5, 0, 0)
                            })
                            for (entry in group.ips) {
                                val check = CheckBox(context).apply {
                                    text = entry.ip
                                    tag = entry.ip
                                    isChecked = entry.checked
                                    textSize = 14f
                                    setOnCheckedChangeListener { _, checked -> entry.checked = checked }
                                }
                                resolveChecks += check
                                resolveRowsBox.addView(check, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                            }
                        }
                    }
                    fillResolvedButton.isEnabled = resolveChecks.any { it.isChecked }
                }

                fun renderLatestResolveHints() {
                    resolveStatusBox.removeAllViews()
                    latestResolveHints.forEach { message ->
                        resolveStatusBox.addView(TextView(context).apply {
                            text = message
                            textSize = 13f
                            setPadding(0, pad / 5, 0, pad / 5)
                        })
                    }
                    hasResolveStatus = latestResolveHints.isNotEmpty()
                }

                fun updateResolvedTimeLabel() {
                    if (lastResolvedAt <= 0L || !hasResolveRows) {
                        resolveTimeLabel.visibility = View.GONE
                    } else {
                        val minutes = ((System.currentTimeMillis() - lastResolvedAt).coerceAtLeast(0L) / 60000L).toInt()
                        resolveTimeLabel.text = context.getString(R.string.iptest_resolve_parsed_ago, minutes)
                        resolveTimeLabel.visibility = View.VISIBLE
                    }
                }

                fun persistResolvedSnapshot() {
                    writeResolvedSnapshot(prefs, resolvedGroups, latestResolveHints, lastResolvedAt)
                }

                val clearResultsButton = TextView(context).apply {
                    setText(R.string.iptest_resolve_clear)
                    textSize = 13f
                    isClickable = true
                    isFocusable = true
                    isEnabled = false
                    setPadding((12 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
                    setOnClickListener {
                        if (!hasResolveRows && !hasResolveStatus) return@setOnClickListener
                        resolvedGroups.clear()
                        latestResolveHints.clear()
                        lastResolvedAt = 0L
                        renderResolvedRows()
                        renderLatestResolveHints()
                        updateResolvedTimeLabel()
                        isEnabled = false
                        persistResolvedSnapshot()
                    }
                }
                val resolveResultHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(resolveResultLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(resolveTimeLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(clearResultsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                renderResolvedRows()
                renderLatestResolveHints()
                clearResultsButton.isEnabled = hasResolveRows || hasResolveStatus
                updateResolvedTimeLabel()

                val resolveButton = MaterialButton(context).apply {
                    setText(R.string.iptest_resolve_button)
                    isAllCaps = false
                    setOnClickListener {
                        val callback = resolveDomains ?: return@setOnClickListener
                        val domains = resolveInput.text.toString().trim()
                        if (domains.isEmpty()) {
                            android.widget.Toast.makeText(context, R.string.iptest_resolve_empty, android.widget.Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        try {
                            prefs.edit().putString("ip_resolve_domains", domains).apply()
                        } catch (_: Exception) {
                        }
                        isEnabled = false
                        setText(R.string.iptest_resolve_running)
                        resolving = true
                        scope.launch {
                            val report = try {
                                callback(domains)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            resolving = false
                            isEnabled = true
                            setText(R.string.iptest_resolve_button)
                            latestResolveHints.clear()
                            if (report == null) {
                                latestResolveHints += context.getString(R.string.iptest_resolve_error)
                                android.widget.Toast.makeText(context, R.string.iptest_resolve_error, android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                if (report.invalid.isNotEmpty()) {
                                    latestResolveHints += context.getString(R.string.iptest_resolve_invalid, report.invalid.joinToString("、"))
                                }
                                val knownAddresses = resolvedGroups.flatMap { group -> group.ips.map { it.ip } }.toMutableSet()
                                for (entry in report.resolved) {
                                    if (entry.error.isNotEmpty()) {
                                        val reason = when (entry.error) {
                                            "hosts_no_ipv4" -> context.getString(R.string.iptest_resolve_hosts_no_ipv4)
                                            "no_ipv4" -> context.getString(R.string.iptest_resolve_no_ipv4)
                                            "timeout" -> context.getString(R.string.iptest_resolve_timeout)
                                            else -> entry.error
                                        }
                                        latestResolveHints += context.getString(R.string.iptest_resolve_failed, entry.domain, reason)
                                        continue
                                    }
                                    val newAddresses = entry.ips.filter { knownAddresses.add(it) }
                                    if (newAddresses.isNotEmpty()) {
                                        resolvedGroups += ResolvedIpGroup(
                                            entry.domain,
                                            newAddresses.map { ResolvedIpEntry(it, true) }.toMutableList(),
                                        )
                                    }
                                    if (entry.omitted > 0) {
                                        latestResolveHints += context.getString(R.string.iptest_resolve_omitted, entry.omitted)
                                    }
                                }
                                if (report.error.isNotEmpty() && report.resolved.isEmpty()) {
                                    latestResolveHints += if (report.error == "no valid domains") {
                                        context.getString(R.string.iptest_resolve_no_valid_domains)
                                    } else {
                                        report.error
                                    }
                                }
                            }
                            lastResolvedAt = System.currentTimeMillis()
                            renderResolvedRows()
                            renderLatestResolveHints()
                            clearResultsButton.isEnabled = hasResolveRows || hasResolveStatus
                            updateResolvedTimeLabel()
                            persistResolvedSnapshot()
                        }
                    }
                }

                val resolveActions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // CUSTOM.md #156 step 75: no baseline alignment, so the two buttons stay level after the fill button changes
                    isBaselineAligned = false
                    addView(resolveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(fillResolvedButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                                        input.setSelection(input.text?.length ?: 0)
                                    }
                                }
                            }
                        }
                    }
                }

                box.addView(hint, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(inputLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(inputField, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (readConfig != null) {
                    box.addView(readButton, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                box.addView(resolveResultHeader, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(resolveResultField, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(resolveLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(resolveInputField, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(resolveActions, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
                box.addView(directUrlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(directUrlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(nodeUrlLabel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(nodeUrlInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val scroll = ScrollView(context).apply {
                    addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                fun done(mode: String) {
                    if (resolving) {
                        android.widget.Toast.makeText(context, R.string.iptest_resolve_wait, android.widget.Toast.LENGTH_SHORT).show()
                        return
                    }
                    val text = input.text.toString()
                    val node = if (nodes.isEmpty()) "" else (spinner.selectedItem as? String ?: "")

                    val ports = portsInput.text.toString().trim().ifEmpty { DEFAULT_PORTS }
                    val directUrl = directUrlInput.text.toString().trim().ifEmpty { DEFAULT_URL_DIRECT }
                    val nodeUrl = nodeUrlInput.text.toString().trim().ifEmpty { DEFAULT_URL_NODE }
                    val url = if (mode == "direct") directUrl else nodeUrl
                    val target = targetInput.text.toString().trim()
                    val extensions = if (mode == "direct") buildList {
                        if (wsCheck.isChecked) add("ws")
                        if (h3Check.isChecked) add("h3")
                        if (mtuCheck.isChecked) add("mtu")
                    } else emptyList()

                    try {
                        val edit = prefs.edit()
                        // CUSTOM.md #156 step 4: one-shot saved-IP prefill must not replace the next manual dialog's history.
                        if (presetIps == null) edit.putString("ip_text", text)
                        edit.putString("ip_resolve_domains", resolveInput.text.toString())
                        edit.putString("ip_node", node).putString("ip_ports", ports)
                            .putString("ip_url_direct", directUrl).putString("ip_url_node", nodeUrl)
                            .putString("ip_target", target)
                            .putBoolean("ip_ext_ws", wsCheck.isChecked)
                            .putBoolean("ip_ext_h3", h3Check.isChecked)
                            .putBoolean("ip_ext_mtu", mtuCheck.isChecked)
                            .apply()
                    } catch (_: Exception) {
                    }

                    if (!ctx.isCompleted) ctx.resume(IpTestChoice(mode, text, node, ports, url, target, extensions))
                }

                MaterialAlertDialogBuilder(context)
                    .setCustomTitle(titleRow)
                    .setView(scroll)
                    .setPositiveButton(R.string.iptest_btn_node) { _, _ -> done("node") }
                    .setNeutralButton(R.string.iptest_btn_direct) { _, _ -> done("direct") }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener {
                        try {
                            prefs.edit().putString("ip_resolve_domains", resolveInput.text.toString()).apply()
                        } catch (_: Exception) {
                        }
                        persistResolvedSnapshot()
                        val shouldCancelResolve = resolving
                        val cancel = cancelResolve
                        scope.cancel()
                        if (shouldCancelResolve && cancel != null) {
                            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                                try { cancel() } catch (_: Exception) { }
                                if (!ctx.isCompleted) ctx.resume(null)
                            }
                        } else if (!ctx.isCompleted) {
                            ctx.resume(null)
                        }
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
