package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignConfigCheckBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #22): the "config check" page. Really connects once per tap, to see whether the fields
// written in the config work. The last domain typed is remembered in this page's own preferences.
class ConfigCheckDesign(context: Context) : Design<ConfigCheckDesign.Request>(context) {
    enum class Request {
        Toggle, Copy,
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

    /** What the user typed, trimmed; also remembered for next time. */
    suspend fun domain(): String {
        return withContext(Dispatchers.Main) {
            val text = binding.checkDomainView.text.toString().trim()

            try {
                prefs.edit().putString("domain", text).apply()
            } catch (_: Exception) {
            }

            text
        }
    }

    suspend fun setRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.checkToggleView.setText(if (running) R.string.check_cancel else R.string.check_start)

            if (running) {
                binding.checkTextView.text = context.getString(R.string.check_running)
            }
        }
    }

    suspend fun showText(text: String) {
        withContext(Dispatchers.Main) {
            binding.checkTextView.text = text
            shown = text
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
            prefs.getString("domain", null)?.let { binding.checkDomainView.setText(it) }
        } catch (_: Exception) {
        }
    }
}
