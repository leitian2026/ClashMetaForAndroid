package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.core.model.DnsCheckReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.json.JSONArray
import org.json.JSONObject
import com.github.kr328.clash.core.model.EffectiveConfig
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignConfigCheckBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import java.util.UUID

// CUSTOM (CUSTOM.md #22): the "config check" page. Really connects once per tap, to see whether the fields
// written in the config work. The last domain typed is remembered in this page's own preferences.
data class IpTestRestore(
    val direct: Boolean,
    val head: String,
    val sub: String,
    val warnings: List<String>,
    val target: Boolean,
    val domain: String,
    val node: String,
    val ports: String,
    val url: String,
    val report: IpTestReport,
    val total: Int,
    val tested: Int,
    val logs: List<String>,
)

class ConfigCheckDesign(context: Context) : Design<ConfigCheckDesign.Request>(context) {
    enum class Request {
        Toggle, Dns, Copy, Flush, Effective, IpTest,
    }

    private val binding = DesignConfigCheckBinding
        .inflate(context.layoutInflater, context.root, false)

    private val prefs = context.getSharedPreferences("custom_config_check", Context.MODE_PRIVATE)

    override val root: View
        get() = binding.root

    private var shown: String = ""

    fun request(request: Request) {
        requests.trySend(request)
    }

    // CUSTOM.md #26: the domains tested before, newest first, at most HISTORY_MAX; shown as a drop-down under the field
    private val history = ArrayList<String>()

    private val historyAdapter = ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, ArrayList())

    private fun refreshHistory() {
        historyAdapter.clear()
        historyAdapter.addAll(history)
        historyAdapter.notifyDataSetChanged()
    }

    /** What the user typed, trimmed; also remembered for next time (and put on top of the history). */
    suspend fun domain(): String {
        return withContext(Dispatchers.Main) {
            val text = binding.checkDomainView.text.toString().trim()

            try {
                val edit = prefs.edit().putString("domain", text)

                if (text.isNotEmpty()) {
                    history.remove(text)
                    history.add(0, text)

                    while (history.size > HISTORY_MAX) history.removeAt(history.size - 1)

                    refreshHistory()
                    edit.putString("history", history.joinToString("\n"))
                }

                edit.apply()
            } catch (_: Exception) {
            }

            text
        }
    }

    /**
     * CUSTOM.md #26: asks what to clear. 0 = nothing (cancelled), 1 = the DNS cache, 2 = the DNS cache and the
     * fake-ip map (which also closes every connection).
     */
    suspend fun askFlush(): Int {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Int> { ctx ->
                val items = arrayOf(
                    context.getString(R.string.check_flush_dns),
                    context.getString(R.string.check_flush_fakeip),
                )

                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.check_flush_title)
                    .setItems(items) { _, which -> ctx.resume(which + 1) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(0) }
            }
        }
    }

    private enum class RunningTask { NONE, ECH, DNS, IP }

    // CUSTOM.md #101: state-driven button availability; only the active test can be cancelled.
    private suspend fun setTaskRunning(task: RunningTask) {
        withContext(Dispatchers.Main) {
            val running = task != RunningTask.NONE
            binding.checkToggleView.setText(if (task == RunningTask.ECH) R.string.check_cancel else R.string.check_start)
            binding.checkDnsView.setText(if (task == RunningTask.DNS) R.string.check_cancel else R.string.check_dns_start)
            binding.checkIpView.setText(if (task == RunningTask.IP) R.string.check_cancel else R.string.iptest_button)
            binding.checkToggleView.isEnabled = !running || task == RunningTask.ECH
            binding.checkDnsView.isEnabled = !running || task == RunningTask.DNS
            binding.checkIpView.isEnabled = !running || task == RunningTask.IP
            binding.checkEffectiveView.isEnabled = !running
            binding.checkFlushView.isEnabled = !running
            binding.checkHintButton.isEnabled = !running
            binding.checkCopyView.isEnabled = !running
            binding.checkCopyView.alpha = if (running) 0.38f else 1f
            if (task == RunningTask.ECH || task == RunningTask.DNS) {
                flushIpTest()
                textMode()
                binding.checkTextView.text = context.getString(R.string.check_running)
            }
        }
    }

    suspend fun setRunning(running: Boolean, dns: Boolean = false) {
        setTaskRunning(if (!running) RunningTask.NONE else if (dns) RunningTask.DNS else RunningTask.ECH)
    }

    suspend fun setIpRunning(running: Boolean) {
        setTaskRunning(if (running) RunningTask.IP else RunningTask.NONE)
        withContext(Dispatchers.Main) {
            ipView?.setBusy(running, null)
            updateSaveBatchAction()
        }
    }

    /** CUSTOM.md #43: the dialog of "test other IPs"; null when cancelled. */
    suspend fun askIpTest(
        nodes: List<String>,
        readConfig: (suspend () -> IpTestReport?)? = null,
        presetIps: String? = null,
        resolveDomains: (suspend (String) -> IpTestReport?)? = null, // CUSTOM.md step 67
        cancelResolve: (suspend () -> Unit)? = null, // CUSTOM.md step 67
        normalizeIps: (suspend (String) -> IpTestReport?)? = null, // CUSTOM.md #156 step 46
    ): IpTestChoice? {
        return IpTestDialog.ask(context, prefs, nodes, readConfig, presetIps, resolveDomains, cancelResolve, normalizeIps) // CUSTOM.md #69, #156; step 67
    }

    // CUSTOM (CUSTOM.md #52): the IP test page is a table (IpTestView) with the live status under it. The activity
    // starts it with ipBegin, then gives it a row (ipRows) and lines (ipLog) as the addresses are done.
    // A tap on "测速" of a row (onSpeedTest) or on its "取消" (onSpeedCancel) goes to the activity.
    var onSpeedTest: ((String) -> Unit)? = null
    var onSpeedCancel: (() -> Unit)? = null
    var onSaveIpBatch: (() -> Unit)? = null // CUSTOM.md #156 step 2
    var onOpenSavedIps: (() -> Unit)? = null // CUSTOM.md #156 step 3

    // CUSTOM.md #156 step 3: the adjacent layout button opens the local saved-IP activity.
    fun openSavedIps() {
        onOpenSavedIps?.invoke()
    }

    private var ipView: IpTestView? = null
    private var saveBatchBusy = false
    private var ipSaveAvailable = false
    private var ipSaved: IpTestRestore? = readIpTest()

    // CUSTOM.md #156 step 10: keep the save action visible in every subview; Activity reports task transitions.
    private fun updateSaveBatchAction() {
        binding.checkSaveBatchButton.visibility = View.VISIBLE
        binding.checkSaveBatchButton.isEnabled = !saveBatchBusy
        binding.checkSaveBatchButton.alpha = if (saveBatchBusy) 0.35f else 1f
    }

    // CUSTOM.md #156 step 10: event-driven busy state; no timer or polling.
    suspend fun setSaveBatchBusy(busy: Boolean) {
        withContext(Dispatchers.Main) {
            saveBatchBusy = busy
            updateSaveBatchAction()
        }
    }

    private fun readIpTest(): IpTestRestore? {
        return try {
            val value = prefs.getString("ip_test_last", null) ?: return null
            val json = JSONObject(value)
            IpTestRestore(
                direct = json.getBoolean("direct"),
                head = json.getString("head"),
                sub = json.optString("sub"),
                warnings = json.getJSONArray("warnings").toStringList(),
                target = json.getBoolean("target"),
                domain = json.optString("domain"),
                node = json.optString("node"),
                ports = json.optString("ports"),
                url = json.optString("url"),
                report = Json.decodeFromString(IpTestReport.serializer(), json.getString("report")),
                total = json.optInt("total"),
                tested = json.optInt("tested"),
                logs = json.getJSONArray("logs").toStringList(),
            )
        } catch (_: Exception) {
            null
        }
    }

    // CUSTOM.md #87: snapshot on Main, then serialize and write the preference off Main.
    // A version guard prevents an older concurrent snapshot from overwriting a newer one.
    private val persistMutex = Mutex()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null
    private var persistVersion = 0L
    private var persistedVersion = 0L

    // CUSTOM.md #87: coalesce live table/log updates into one write after the latest change.
    private fun schedulePersistIpTest() {
        persistJob?.cancel()
        persistJob = persistScope.launch {
            delay(2_000)
            persistIpTest()
        }
    }

    suspend fun flushIpTest() {
        withContext(Dispatchers.Main.immediate) {
            persistJob?.cancel()
            persistJob = null
        }
        persistIpTest()
    }

    fun dispose() {
        persistJob?.cancel()
        persistJob = persistScope.launch {
            persistIpTest()
            persistScope.cancel()
        }
    }

    private suspend fun persistIpTest() {
        val versioned = withContext(Dispatchers.Main.immediate) {
            val saved = ipSaved ?: return@withContext null
            val snapshot = saved.copy(
                logs = (ipView?.savedLogs() ?: saved.logs).toList(),
                warnings = saved.warnings.toList(),
                report = saved.report.copy(
                    rows = saved.report.rows.toList(),
                    colos = saved.report.colos.toMap(),
                    coloCountries = saved.report.coloCountries.toMap(),
                ),
            )
            (++persistVersion) to snapshot
        } ?: return

        persistMutex.withLock {
            if (versioned.first <= persistedVersion) return@withLock

            try {
                val value = withContext(Dispatchers.Default) {
                    val saved = versioned.second
                    JSONObject()
                        .put("direct", saved.direct)
                        .put("head", saved.head)
                        .put("sub", saved.sub)
                        .put("warnings", JSONArray(saved.warnings))
                        .put("target", saved.target)
                        .put("domain", saved.domain)
                        .put("node", saved.node)
                        .put("ports", saved.ports)
                        .put("url", saved.url)
                        .put("report", Json.encodeToString(IpTestReport.serializer(), saved.report))
                        .put("total", saved.total)
                        .put("tested", saved.tested)
                        .put("logs", JSONArray(saved.logs))
                        .toString()
                }

                withContext(Dispatchers.IO) {
                    prefs.edit().putString("ip_test_last", value).apply()
                }
                persistedVersion = versioned.first
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the page and the running test usable if local persistence fails.
            }
        }
    }

    private fun JSONArray.toStringList(): List<String> = List(length()) { optString(it) }

    suspend fun restoreIpTest(): IpTestRestore? {
        flushIpTest()
        return withContext(Dispatchers.Main) {
            val saved = ipSaved ?: return@withContext null
            createIpView(saved.direct, saved.target, saved.head, saved.sub, saved.warnings)
            ipView?.let { view ->
                view.setColos(saved.report.colos, saved.report.coloCountries)
                view.setRows(saved.report.rows)
                ipSaveAvailable = saved.report.rows.isNotEmpty() // CUSTOM.md #156 step 7: restore only when completed rows exist
                updateSaveBatchAction()
                view.setProgress(saved.total, saved.tested)
                view.setLogs(saved.logs)
                shown = view.plain()
            }
            saved
        }
    }

    suspend fun ipSaveContext(domain: String, node: String, ports: String, url: String) {
        withContext(Dispatchers.Main) {
            ipSaved = ipSaved?.copy(domain = domain, node = node, ports = ports, url = url)
            flushIpTest()
        }
    }

    // CUSTOM (CUSTOM.md #94): true while the table on the page is the through-node test with a target address
    private var ipTarget = false

    private fun createIpView(direct: Boolean, target: Boolean, head: String, sub: String, warnings: List<String>) {
        val view = IpTestView(context, direct, target)

        ipTarget = target

        view.onCopy = { text ->
            context.getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("ip_test", text))

            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        view.onSpeed = { ip -> onSpeedTest?.invoke(ip) }
        view.onSpeedCancel = { onSpeedCancel?.invoke() }

        view.onDetail = { title, text ->
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(R.string.close, null)
                .show()
        }

        view.setInfo(head, sub, warnings)
        ipSaveAvailable = false // CUSTOM.md #156 step 7: a new round starts with the toolbar action hidden
        ipView = view

        binding.checkIpContainer.removeAllViews()
        binding.checkIpContainer.addView(view.root)
        binding.checkIpContainer.visibility = View.VISIBLE
        binding.checkTextView.visibility = View.GONE
        updateSaveBatchAction()
        linksOn = false
    }

    suspend fun ipBegin(direct: Boolean, head: String, sub: String, warnings: List<String>, target: Boolean = false) {
        withContext(Dispatchers.Main) {
            createIpView(direct, target, head, sub, warnings)
            ipSaved = IpTestRestore(
                direct, head, sub, warnings, target, "", "", "", "",
                IpTestReport(), 0, 0, emptyList(),
            )
            flushIpTest()
            shown = ipView?.plain().orEmpty()
        }
    }

    // CUSTOM (CUSTOM.md #156 step 2/10): choose an operator and merge completed rows into its saved-IP list.
    // Empty rows are allowed so the same dialog can be used for the later paste-IP step.
    fun showSaveIpBatch(rows: List<IpTestRow>, preferredOperatorId: String? = null) {
        SaveIpBatchDialog(context, rows, preferredOperatorId) { ipSaved }.show()
    }

    suspend fun ipSaveAvailable(available: Boolean) {
        withContext(Dispatchers.Main) {
            ipSaveAvailable = available
            updateSaveBatchAction()
        }
    }

    suspend fun ipInfo(head: String, sub: String, warnings: List<String>) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                val current = ipSaved
                if (current != null && (current.head != head || current.sub != sub || current.warnings != warnings)) {
                    it.setInfo(head, sub, warnings)
                    ipSaved = current.copy(head = head, sub = sub, warnings = warnings.toList())
                    schedulePersistIpTest()
                }
            }
        }
    }

    suspend fun ipRows(rows: List<com.github.kr328.clash.core.model.IpTestRow>) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                val current = ipSaved
                if (current != null && current.report.rows != rows) {
                    it.setRows(rows)
                    ipSaved = current.copy(report = current.report.copy(rows = rows.toList()))
                    schedulePersistIpTest()
                } else {
                    it.setRows(rows)
                }
                ipSaveAvailable = rows.isNotEmpty() // each row delivered here is a completed probe result
                updateSaveBatchAction()
            }
        }
    }

    suspend fun ipColos(colos: Map<String, String>, countries: Map<String, String>) {
        withContext(Dispatchers.Main) {
            val current = ipSaved
            if (current == null) {
                ipView?.setColos(colos, countries)
            } else if (current.report.colos != colos || current.report.coloCountries != countries) {
                ipView?.setColos(colos, countries)
                ipSaved = current.copy(report = current.report.copy(colos = colos.toMap(), coloCountries = countries.toMap()))
                schedulePersistIpTest()
            }
        }
    }

    /** [total] is the number of IPs in this round; [tested] counts completed probes. */
    suspend fun ipProgress(total: Int, tested: Int) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                val current = ipSaved
                it.setProgress(total, tested)
                if (current != null && (current.total != total || current.tested != tested)) {
                    ipSaved = current.copy(total = total, tested = tested)
                    schedulePersistIpTest()
                }
            }
        }
    }

    /** [ip]: the row whose download is running (null: none). */
    suspend fun ipSpeedBusy(ip: String?) {
        withContext(Dispatchers.Main) { ipView?.setBusy(false, ip) }
    }

    fun ipSpeedProgress(ip: String, direct: Boolean, percent: Int?) {
        ipView?.setDownloadProgress(ip, direct, percent)
    }

    suspend fun ipOwnerLookup(ip: String?) {
        withContext(Dispatchers.Main) { ipView?.setOwnerLookup(ip) }
    }

    suspend fun ipLog(text: String) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                it.log(text)
                schedulePersistIpTest()
            }
        }
    }

    // back to the plain text view (any page but the IP test)
    private fun textMode() {
        ipView = null
        ipTarget = false
        updateSaveBatchAction()
        binding.checkIpContainer.visibility = View.GONE
        binding.checkIpContainer.removeAllViews()
        binding.checkTextView.visibility = View.VISIBLE
    }

    suspend fun showText(text: String) {
        flushIpTest()
        withContext(Dispatchers.Main) {
            textMode() // CUSTOM.md #52
            if (linksOn) {
                // back from the colored page (CUSTOM.md #30): the text can be selected again
                binding.checkTextView.setTextIsSelectable(true)
                linksOn = false
            }

            binding.checkTextView.text = text
            shown = text
        }
    }

    // CUSTOM.md #30: the page of the effective config is colored and has lines that open on a tap, so its taps go to
    // the links in the text instead of selecting; the copy button still copies the whole text
    private var linksOn = false

    suspend fun showEffective(config: EffectiveConfig) {
        flushIpTest()
        withContext(Dispatchers.Main) {
            textMode() // CUSTOM.md #52

            val view = EffectiveText(context, config)

            binding.checkTextView.movementMethod = LinkMovementMethod.getInstance()
            linksOn = true

            // a tap on a fold or on the defaults line changes what is shown and draws the text again
            lateinit var draw: () -> Unit

            draw = {
                binding.checkTextView.text = view.render(draw)
                shown = view.plain()
            }

            draw()
        }
    }

    // CUSTOM.md #26 (r32): the DNS check page has lines that open on a tap (a server's details, the fold of the
    // rest), so like the effective config its taps go to the links; the copy button copies the whole text
    suspend fun showDns(report: DnsCheckReport) {
        if (ipView == null) flushIpTest()
        withContext(Dispatchers.Main) {
            // CUSTOM.md #156 step 42: keep the IP table and append DNS timings to its shared diagnostics fold.
            // DNS remains a separate result group; it is never presented as an individual IP's DNS latency.
            ipView?.let { current ->
                current.setDnsReport(report)
                shown = current.plain()
                return@withContext
            }

            textMode() // CUSTOM.md #52

            val view = DnsCheckView(context, report)

            binding.checkTextView.movementMethod = LinkMovementMethod.getInstance()
            linksOn = true

            lateinit var draw: () -> Unit

            draw = {
                binding.checkTextView.text = view.render(draw)
                shown = view.plain()
            }

            draw()
        }
    }

    suspend fun copyAll() {
        withContext(Dispatchers.Main) {
            context.getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("config_check", ipView?.plain() ?: shown))
        }

        showToast(R.string.copied, ToastDuration.Short)
    }

    init {
        binding.self = this

        // CUSTOM (CUSTOM.md #91): show the complete explanation without changing page state.
        binding.checkSaveBatchButton.setOnClickListener { onSaveIpBatch?.invoke() } // CUSTOM.md #156 step 7/10
        updateSaveBatchAction()

        binding.checkHintButton.setOnClickListener {
            // CUSTOM (CUSTOM.md #94): while the table shows the through-node test, the explanation that used to sit
            // under "目标网址" is the second paragraph here
            val message = if (ipTarget) {
                context.getString(R.string.check_hint) + "\n\n" + context.getString(R.string.iptest_target_note)
            } else {
                context.getString(R.string.check_hint)
            }

            MaterialAlertDialogBuilder(context)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.activityBarLayout.applyFrom(context)

        try {
            prefs.getString("domain", null)?.let { binding.checkDomainView.setText(it, false) }

            prefs.getString("history", null)?.split('\n')?.filter { it.isNotBlank() }?.take(HISTORY_MAX)?.let { history.addAll(it) }
        } catch (_: Exception) {
        }

        // CUSTOM.md #26: the drop-down opens on a tap on the field, so an earlier domain is one tap away
        binding.checkDomainView.setAdapter(historyAdapter)
        binding.checkDomainView.threshold = 1
        refreshHistory()
        binding.checkDomainView.setOnClickListener { if (history.isNotEmpty()) binding.checkDomainView.showDropDown() }
    }

    companion object {
        private const val HISTORY_MAX = 8
    }
}
