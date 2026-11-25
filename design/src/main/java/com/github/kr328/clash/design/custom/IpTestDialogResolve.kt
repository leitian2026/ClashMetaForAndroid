package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.design.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class ResolvedIpEntry(val ip: String, var checked: Boolean)
private data class ResolvedIpGroup(
    val domain: String,
    val ips: MutableList<ResolvedIpEntry>,
    val fromHosts: Boolean = false,
    val cacheBypassUnsupported: Boolean = false,
)
private data class ResolvedIpSnapshot(
    val groups: List<ResolvedIpGroup> = emptyList(),
    val hints: List<String> = emptyList(),
    val timestamp: Long = 0L,
)
private fun elapsedMinutes(at: Long): Int =
    ((System.currentTimeMillis() - at).coerceAtLeast(0L) / 60000L).toInt()
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
            if (ips.isNotEmpty()) groups += ResolvedIpGroup(
                domain,
                ips,
                groupJson.optBoolean("fromHosts", false),
                groupJson.optBoolean("cacheBypassUnsupported", false),
            )
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
            groupsJson.put(
                JSONObject()
                    .put("domain", group.domain)
                    .put("ips", ipsJson)
                    .put("fromHosts", group.fromHosts)
                    .put("cacheBypassUnsupported", group.cacheBypassUnsupported),
            )
        }
        val json = JSONObject()
            .put("groups", groupsJson)
            .put("hints", JSONArray(hints))
            .put("timestamp", timestamp)
        prefs.edit().putString(RESOLVED_SNAPSHOT_KEY, json.toString()).apply()
    } catch (_: Exception) {
    }
}

internal class IpTestDialogState(
    private val context: Context,
    val input: TextInputEditText,
    private val normalizeIps: (suspend (String) -> IpTestReport?)?,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val resolveChecks = ArrayList<CheckBox>()
    var resolving = false

    fun selectedResolveIps(): List<String> =
        resolveChecks.filter { it.isChecked }.mapNotNull { it.tag as? String }

    suspend fun fillTestBox(values: List<String>) {
    val report = normalizeIps?.invoke(values.joinToString("\n"))
    if (report == null || report.error.isNotEmpty() || report.rows.isEmpty()) {
        android.widget.Toast.makeText(context, R.string.iptest_dedupe_error, android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    input.setText(report.rows.joinToString("\n") { it.ip })
    input.setSelection(input.text?.length ?: 0)
    if (report.duplicates > 0) {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.iptest_duplicates_removed, report.duplicates),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
}


internal class IpTestDialogResolve(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val state: IpTestDialogState,
    private val resolveDomains: (suspend (String) -> IpTestReport?)?,
    private val density: Float,
    private val pad: Int,
) {
    private val restoredResolved = readResolvedSnapshot(prefs)
    private val resolvedGroups = restoredResolved.groups.map { group ->
        ResolvedIpGroup(
            group.domain,
            group.ips.map { ResolvedIpEntry(it.ip, it.checked) }.toMutableList(),
            group.fromHosts,
            group.cacheBypassUnsupported,
        )
    }.toMutableList()
    private val latestResolveHints = restoredResolved.hints.toMutableList()
    private var lastResolvedAt = restoredResolved.timestamp
    private var hasResolveRows = false
    private var hasResolveStatus = false

    val isResolving: Boolean get() = state.resolving
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
    init {
        resolveRowsBox.addView(resolveResultPlaceholder)
        resolveResultBox.addView(resolveStatusBox)
        resolveResultBox.addView(resolveRowsBox)
    }
    val resolveResultOutlineTemplate = LayoutInflater.from(context)
        .inflate(R.layout.dialog_iptest_outlined_field, null, false) as TextInputLayout
    val resultOutlineBackground = resolveResultOutlineTemplate
        .findViewById<TextInputEditText>(R.id.iptest_outlined_input)
        .background ?: throw IllegalStateException("Outlined field background is missing")
    init {
        resolveResultBox.background = resultOutlineBackground.constantState?.newDrawable()?.mutate()
            ?: resultOutlineBackground.mutate()
    }
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
    val fillResolvedButton = MaterialButton(context).apply {
        setText(R.string.iptest_resolve_fill)
        isAllCaps = false
        isEnabled = false
        setOnClickListener {
            val selected = state.selectedResolveIps()
            if (selected.isEmpty()) {
                android.widget.Toast.makeText(context, R.string.iptest_resolve_none_selected, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            state.scope.launch { state.fillTestBox(selected) }
        }
    }

    fun renderResolvedRows() {
        resolveRowsBox.removeAllViews()
        state.resolveChecks.clear()
        hasResolveRows = resolvedGroups.any { it.ips.isNotEmpty() }
        if (!hasResolveRows) {
            resolveRowsBox.addView(resolveResultPlaceholder)
        } else {
            for (group in resolvedGroups) {
                if (group.ips.isEmpty()) continue
                resolveRowsBox.addView(TextView(context).apply {
                    text = buildList {
                        if (group.fromHosts) add(context.getString(R.string.iptest_resolve_from_hosts))
                        if (group.cacheBypassUnsupported) add(context.getString(R.string.iptest_resolve_cache_unsupported))
                    }.let { labels ->
                        if (labels.isEmpty()) group.domain else "${group.domain} · ${labels.joinToString(" · ")}"
                    }
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
                    state.resolveChecks += check
                    resolveRowsBox.addView(check, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }
        fillResolvedButton.isEnabled = state.resolveChecks.any { it.isChecked }
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
            resolveTimeLabel.text = context.getString(R.string.iptest_resolve_parsed_ago, elapsedMinutes(lastResolvedAt))
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

    init {
        renderResolvedRows()
        renderLatestResolveHints()
        clearResultsButton.isEnabled = hasResolveRows || hasResolveStatus
        updateResolvedTimeLabel()
    }

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
            state.resolving = true
            state.scope.launch {
                val report = try {
                    callback(domains)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                state.resolving = false
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
                        val sourceLabels = buildList {
                            if (entry.fromHosts) add(context.getString(R.string.iptest_resolve_from_hosts))
                            if (entry.cacheBypassUnsupported) add(context.getString(R.string.iptest_resolve_cache_unsupported))
                        }
                        val displayDomain = if (sourceLabels.isEmpty()) {
                            entry.domain
                        } else {
                            "${entry.domain} · ${sourceLabels.joinToString(" · ")}"
                        }
                        if (entry.error.isNotEmpty()) {
                            val reason = when (entry.error) {
                                "hosts_no_ipv4" -> context.getString(R.string.iptest_resolve_hosts_no_ipv4)
                                "no_ipv4" -> context.getString(R.string.iptest_resolve_no_ipv4)
                                "timeout" -> context.getString(R.string.iptest_resolve_timeout)
                                else -> entry.error
                            }
                            latestResolveHints += context.getString(R.string.iptest_resolve_failed, displayDomain, reason)
                            continue
                        }
                        // IPv4 and IPv6 share the same selectable list and snapshot format.
                        val newAddresses = (entry.ips + entry.ipsV6).filter { knownAddresses.add(it) }
                        if (newAddresses.isNotEmpty()) {
                            resolvedGroups += ResolvedIpGroup(
                                entry.domain,
                                newAddresses.map { ResolvedIpEntry(it, true) }.toMutableList(),
                                entry.fromHosts,
                                entry.cacheBypassUnsupported,
                            )
                        } else if (sourceLabels.isNotEmpty()) {
                            latestResolveHints += displayDomain
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


}
