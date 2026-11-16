package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.model.DnsCheckReport
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
    suspend fun askIpTest(nodes: List<String>): IpTestChoice? {
        return IpTestDialog.ask(context, prefs, nodes)
    }

    // CUSTOM (CUSTOM.md #52): the IP test page is a table (IpTestView) with the live status under it. The activity
    // starts it with ipBegin, then gives it a row (ipRows) and lines (ipLog) as the addresses are done.
    // A tap on "测速" of a row (onSpeedTest) or on its "取消" (onSpeedCancel) goes to the activity.
    var onSpeedTest: ((String) -> Unit)? = null
    var onSpeedCancel: (() -> Unit)? = null

    private var ipView: IpTestView? = null

    suspend fun ipBegin(direct: Boolean, head: String, sub: String, warnings: List<String>) {
        withContext(Dispatchers.Main) {
            val view = IpTestView(context, direct)

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

            shown = view.plain()
        }
    }

    suspend fun ipInfo(head: String, sub: String, warnings: List<String>) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                it.setInfo(head, sub, warnings)
                shown = it.plain()
            }
        }
    }

    suspend fun ipRows(rows: List<com.github.kr328.clash.core.model.IpTestRow>) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                it.setRows(rows)
                shown = it.plain()
            }
        }
    }

    suspend fun ipColos(colos: Map<String, String>, countries: Map<String, String>) {
        withContext(Dispatchers.Main) { ipView?.setColos(colos, countries) }
    }

    /** [ip]: the row whose download is running (null: none). */
    suspend fun ipSpeedBusy(ip: String?) {
        withContext(Dispatchers.Main) { ipView?.setBusy(false, ip) }
    }

    suspend fun ipLog(text: String) {
        withContext(Dispatchers.Main) {
            ipView?.let {
                it.log(text)
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
