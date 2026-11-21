package com.github.kr328.clash.design.custom

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

/**
 * CUSTOM #43: show which entered addresses are already saved.
 * Read the saved snapshot once per dialog; input edits only compare against this cached set.
 */
internal object IpTestSavedIpHint {
    fun create(context: Context, input: TextInputEditText): View {
        val density = context.resources.displayMetrics.density
        val saved = SavedIpStore.read(context).operators
            .asSequence()
            .flatMap { it.ips.asSequence() }
            .map { normalize(it.address) }
            .filter { it.isNotEmpty() }
            .toHashSet()

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val container = this
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    val matches = s?.toString().orEmpty()
                        .split(Regex("[\\s,，;；、]+"))
                        .asSequence()
                        .map(::normalize)
                        .filter { it.isNotEmpty() && it in saved }
                        .distinct()
                        .toList()
                    container.removeAllViews()
                    if (matches.isEmpty()) {
                        container.visibility = View.GONE
                        return
                    }
                    matches.forEach { address ->
                        container.addView(TextView(context).apply {
                            text = context.getString(R.string.iptest_saved_ip_exists, address)
                            textSize = 12f
                            alpha = 0.75f
                            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ))
                    }
                    container.visibility = View.VISIBLE
                }
            }
            input.addTextChangedListener(watcher)
            watcher.afterTextChanged(input.text)
        }
    }

    private fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("[") && value.contains(']')) {
            value = value.substring(1, value.indexOf(']'))
        } else if (value.count { it == ':' } == 1 && value.substringAfterLast(':').all(Char::isDigit)) {
            value = value.substringBeforeLast(':')
        }
        return value.lowercase(Locale.ROOT)
    }
}
