package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.model.IpTestReport
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

    // [dns]: the running test is the DNS check (CUSTOM.md #25); the button that started it turns into "cancel"
    suspend fun setRunning(running: Boolean, dns: Boolean = false) {
        withContext(Dispatchers.Main) {
            binding.checkToggleView.setText(if (running && !dns) R.string.check_cancel else R.string.check_start)
            binding.checkDnsView.setText(if (running && dns) R.string.check_cancel else R.string.check_dns_start)

            if (running) {
                textMode() // CUSTOM.md #52
                binding.checkTextView.text = context.getString(R.string.check_running)
            }
        }
    }

    // CUSTOM.md #43: the button of "test other IPs" turns into "cancel" while its test runs
    suspend fun setIpRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.checkIpView.setText(if (running) R.string.check_cancel else R.string.iptest_button)

            ipView?.setBusy(running, null) // CUSTOM.md #52: no download while the round runs
        }
    }

    /** CUSTOM.md #43: the dialog of "test other IPs"; null when cancelled. */
    suspend fun askIpTest(nodes: List<String>, readConfig: (suspend () -> IpTestReport?)? = null): IpTestChoice? {
        return IpTestDialog.ask(context, prefs, nodes, readConfig) // CUSTOM.md #69: readConfig = "read the IPs of the config"
    }

    // CUSTOM (CUSTOM.md #52): the IP test page is a table (IpTestView) with the live status under it. The activity
    // starts it with ipBegin, then gives it a row (ipRows) and lines (ipLog) as the addresses are done.
    // A tap on "测速" of a row (onSpeedTest) or on its "取消" (onSpeedCancel) goes to the activity.
    var onSpeedTest: ((String) -> Unit)? = null
    var onSpeedCancel: (() -> Unit)? = null

    private var ipView: IpTestView? = null
    private var ipSaved: IpTestRestore? = readIpTest()

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

    private fun persistIpTest() {
        val saved = ipSaved ?: return
        try {
            val json = JSONObject()
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
            prefs.edit().putString("ip_test_last", json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun JSONArray.toStringList(): List<String> = List(length()) { optString(it) }

    suspend fun restoreIpTest(): IpTestRestore? {
        return withContext(Dispatchers.Main) {
            val saved = ipSaved ?: return@withContext null
            createIpView(saved.direct, saved.target, saved.head, saved.sub, saved.warnings)
            ipView?.let { view ->
                view.setColos(saved.report.colos, saved.report.coloCountries)
                view.setRows(saved.report.rows)
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
            persistIpTest()
        }
    }

    private fun createIpView(direct: Boolean, target: Boolean, head: String, sub: String, warnings: List<String>) {
        val view = IpTestView(context, direct, target)

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
        ipView = view

        binding.checkIpContainer.removeAllViews()
        binding.checkIpContainer.addView(view.root)
        binding.checkIpContainer.visibility = View.VISIBLE
        binding.checkTextView.visibility = View.GONE
        linksOn = false
    }

    suspend fun ipBegin(direct: Boolean, head: String, sub: String, warnings: List<String>, target: Boolean = false) {
        withContext(Dispatchers.Main) {
            createIpView(direct, target, head, sub, warnings)
            ipSaved = IpTestRestore(
                direct, head, sub, warnings, target, "", "", "", "",
                IpTestReport(), 0, 0, emptyList(),
            )
            persistIpTest()
            shown = ipView?.plain().orEmpty()
        }
    }

    suspend fun ipInfo(head: String, sub: String, warnings: List<String>) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                it.setInfo(head, sub, warnings)
                ipSaved = ipSaved?.copy(head = head, sub = sub, warnings = warnings)
                persistIpTest()
                shown = it.plain()
            }
        }
    }

    suspend fun ipRows(rows: List<com.github.kr328.clash.core.model.IpTestRow>) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                it.setRows(rows)
                ipSaved = ipSaved?.copy(report = (ipSaved?.report ?: IpTestReport()).copy(rows = rows))
                persistIpTest()
                shown = it.plain()
            }
        }
    }

    suspend fun ipColos(colos: Map<String, String>, countries: Map<String, String>) {
        withContext(Dispatchers.Main) {
            ipView?.setColos(colos, countries)
            ipSaved = ipSaved?.copy(report = (ipSaved?.report ?: IpTestReport()).copy(colos = colos, coloCountries = countries))
            persistIpTest()
        }
    }

    /** [total] is the number of IPs in this round; [tested] counts completed probes. */
    suspend fun ipProgress(total: Int, tested: Int) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                it.setProgress(total, tested)
                ipSaved = ipSaved?.copy(total = total, tested = tested)
                persistIpTest()
                shown = it.plain()
            }
        }
    }

    /** [ip]: the row whose download is running (null: none). */
    suspend fun ipSpeedBusy(ip: String?) {
        withContext(Dispatchers.Main) { ipView?.setBusy(false, ip) }
    }

    suspend fun ipLog(text: String) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                it.log(text)
                ipSaved = ipSaved?.copy(logs = it.savedLogs())
                persistIpTest()
                shown = it.plain()
            }
        }
    }

    // back to the plain text view (any page but the IP test)
    private fun textMode() {
        ipView = null
        binding.checkIpContainer.visibility = View.GONE
        binding.checkIpContainer.removeAllViews()
        binding.checkTextView.visibility = View.VISIBLE
    }

    suspend fun showText(text: String) {
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
        withContext(Dispatchers.Main) {
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
                ?.setPrimaryClip(ClipData.newPlainText("config_check", shown))
        }

        showToast(R.string.copied, ToastDuration.Short)
    }

    init {
        binding.self = this

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
