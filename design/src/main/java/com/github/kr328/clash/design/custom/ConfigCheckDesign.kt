package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.content.getSystemService
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
        Toggle, Dns, Copy, Flush, Effective,
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
                binding.checkTextView.text = context.getString(R.string.check_running)
            }
        }
    }

    suspend fun showText(text: String) {
        withContext(Dispatchers.Main) {
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
