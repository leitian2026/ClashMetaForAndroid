package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.design.R

/**
 * Owns the IP input's history label and clear action.
 *
 * Kept outside IpTestDialog.ask because ask is a large suspend function and should not grow further.
 */
internal class IpTestInputHistory(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val input: EditText,
    inputLabel: TextView,
    useManualHistory: Boolean,
    density: Float,
) {
    private val inputTimeLabel = TextView(context).apply {
        textSize = 11f
        alpha = 0.65f
        visibility = View.GONE
        setPadding(0, 0, (8 * density).toInt(), 0)
    }
    private val clearInputButton = TextView(context).apply {
        setText(R.string.iptest_resolve_clear)
        textSize = 13f
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        setPadding((12 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
    }
    private val inputTextKey = if (useManualHistory) "ip_text" else "ip_test_preset_text"
    private val inputTimestampKey = if (useManualHistory) "ip_text_changed_at" else "ip_test_preset_changed_at"
    private val handler = Handler(Looper.getMainLooper())
    private var pendingWrite: Runnable? = null
    private var lastObservedText = input.text.toString()
    private var lastInputChangedAt = try {
        if (prefs.getString(inputTextKey, null) == input.text.toString()) prefs.getLong(inputTimestampKey, 0L) else 0L
    } catch (_: Exception) {
        0L
    }

    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(inputLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(inputTimeLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(clearInputButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString().orEmpty()
                if (value != lastObservedText) {
                    lastObservedText = value
                    lastInputChangedAt = if (value.isEmpty()) 0L else System.currentTimeMillis()
                    schedulePersist()
                }
                updateHeader()
            }
        })
        clearInputButton.setOnClickListener {
            if (!input.text.isNullOrEmpty()) input.setText("")
        }
        updateHeader()
    }

    private fun schedulePersist() {
        pendingWrite?.let(handler::removeCallbacks)
        val task = Runnable {
            pendingWrite = null
            persist()
        }
        pendingWrite = task
        handler.postDelayed(task, 1_000L)
    }

    /** Flush only a pending edit; an already-fired debounce has already persisted the current value. */
    fun flush() {
        val task = pendingWrite ?: return
        handler.removeCallbacks(task)
        pendingWrite = null
        persist()
    }

    private fun persist() {
        try {
            prefs.edit()
                .putString(inputTextKey, input.text.toString())
                .putLong(inputTimestampKey, lastInputChangedAt)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun updateHeader() {
        val hasText = !input.text.isNullOrEmpty()
        clearInputButton.visibility = if (hasText) View.VISIBLE else View.GONE
        if (!hasText || lastInputChangedAt <= 0L) {
            inputTimeLabel.visibility = View.GONE
        } else {
            val elapsedMinutes = ((System.currentTimeMillis() - lastInputChangedAt).coerceAtLeast(0L) / 60000L).toInt()
            inputTimeLabel.text = context.getString(R.string.reach_age_minutes, elapsedMinutes)
            inputTimeLabel.visibility = View.VISIBLE
        }
    }
}
