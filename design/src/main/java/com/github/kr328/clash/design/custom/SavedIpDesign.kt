package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.github.kr328.clash.design.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/**
 * CUSTOM.md #156 step 3: local saved-IP page. All list operations are local and remain available when Clash is stopped.
 * Multi-selection and batch re-test are added in step 4.
 */
class SavedIpDesign(
    private val context: Context,
    private val onBack: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val colorText = themeColor(android.R.attr.textColorPrimary, Color.BLACK)
    private val colorMuted = themeColor(android.R.attr.textColorSecondary, Color.GRAY)
    private val colorSurface = themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val colorDivider = themeColor(android.R.attr.colorControlHighlight, 0x22000000)

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colorSurface)
    }

    private val toolbar = MaterialToolbar(context).apply {
        title = context.getString(R.string.saved_ip_title)
        setTitleTextColor(colorText)
        setNavigationIcon(R.drawable.ic_baseline_arrow_back)
        setNavigationOnClickListener { onBack() }
    }

    private val operatorList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(24))
    }

    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        addView(operatorList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        actions.addView(actionButton(R.string.saved_ip_add_operator) { addOperator() }, weighted())
        actions.addView(actionButton(R.string.saved_ip_paste) { pasteIps() }, weighted())
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    fun refresh() {
        operatorList.removeAllViews()
        val snapshot = SavedIpStore.read(context)
        val total = snapshot.operators.sumOf { it.ips.size }

        if (total == 0) {
            operatorList.addView(
                TextView(context).apply {
                    setText(R.string.saved_ip_empty)
                    textSize = 14f
                    setTextColor(colorMuted)
                    setPadding(0, dp(8), 0, dp(12))
                },
                fullWidth(),
            )
        }

        for ((index, operator) in snapshot.operators.withIndex()) {
            if (index > 0) addDivider(operatorList)
            addOperatorGroup(operator)
        }
    }

    private fun addOperatorGroup(operator: SavedIpOperator) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = displayName(operator)
        header.addView(
            TextView(context).apply {
                text = name + " (" + operator.ips.size + ")"
                textSize = 16f
                setTextColor(colorText)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        if (operator.id !in SavedIpStore.builtInOperatorIds) {
            header.addView(actionText(R.string.saved_ip_rename) { editOperator(operator) })
            header.addView(actionText(R.string.saved_ip_delete) { confirmDeleteOperator(operator) })
        }
        operatorList.addView(header, fullWidth())

        if (operator.ips.isEmpty()) {
            operatorList.addView(
                TextView(context).apply {
                    setText(R.string.saved_ip_no_ips)
                    textSize = 13f
                    setTextColor(colorMuted)
                    setPadding(0, dp(5), 0, dp(8))
                },
                fullWidth(),
            )
            return
        }

        for (entry in operator.ips) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val mark = TextView(context).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                text = when (entry.status) {
                    SavedIpStatus.REACHABLE -> CheckMark.paint(context, "✓")
                    SavedIpStatus.UNREACHABLE -> CheckMark.paint(context, "✗")
                    SavedIpStatus.UNTESTED -> ""
                }
            }
            row.addView(mark, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))

            val address = TextView(context).apply {
                text = entry.address
                textSize = 14f
                setTextColor(colorText)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { copyIp(entry.address) }
            }
            row.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val remove = TextView(context).apply {
                text = "×"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(colorMuted)
                contentDescription = context.getString(R.string.saved_ip_delete)
                background = selectableBackground()
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setOnClickListener { confirmDeleteIp(operator.id, entry.address) }
            }
            row.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            operatorList.addView(row, fullWidth())
        }
    }

    private fun addOperator() {
        showOperatorNameDialog(null)
    }

    private fun editOperator(operator: SavedIpOperator) {
        if (operator.id in SavedIpStore.builtInOperatorIds) return
        showOperatorNameDialog(operator)
    }

    private fun showOperatorNameDialog(existing: SavedIpOperator?) {
        val input = EditText(context).apply {
            setHint(R.string.saved_ip_operator_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            if (existing != null) setText(existing.name)
            setSelection(text.length)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.saved_ip_add_operator_title else R.string.saved_ip_edit_operator_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_add, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val codePoints = name.codePointCount(0, name.length)
                val error = when {
                    name.isEmpty() -> R.string.saved_ip_operator_name_empty
                    codePoints > 12 -> R.string.saved_ip_operator_name_too_long
                    hasDuplicateName(name, existing?.id) -> R.string.saved_ip_operator_name_duplicate
                    else -> 0
                }
                if (error != 0) {
                    input.error = context.getString(error)
                    return@setOnClickListener
                }

                val snapshot = SavedIpStore.read(context)
                val operators = snapshot.operators.toMutableList()
                if (existing == null) {
                    operators += SavedIpOperator("custom_" + UUID.randomUUID().toString(), name)
                } else {
                    val index = operators.indexOfFirst { it.id == existing.id }
                    if (index >= 0) operators[index] = operators[index].copy(name = name)
                }
                SavedIpStore.write(context, snapshot.copy(operators = operators))
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun hasDuplicateName(name: String, exceptId: String?): Boolean {
        return SavedIpStore.read(context).operators.any { operator ->
            operator.id != exceptId && displayName(operator).equals(name, ignoreCase = true)
        }
    }

    private fun confirmDeleteOperator(operator: SavedIpOperator) {
        if (operator.id in SavedIpStore.builtInOperatorIds) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_operator_delete_title)
            .setMessage(context.getString(R.string.saved_ip_operator_delete_message, operator.ips.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                val snapshot = SavedIpStore.read(context)
                SavedIpStore.write(
                    context,
                    snapshot.copy(
                        operators = snapshot.operators.filterNot { it.id == operator.id },
                        lastOperatorId = if (snapshot.lastOperatorId == operator.id) SavedIpStore.OPERATOR_TELECOM else snapshot.lastOperatorId,
                    ),
                )
                refresh()
            }
            .show()
    }

    private fun confirmDeleteIp(operatorId: String, address: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_delete_ip_title)
            .setMessage(context.getString(R.string.saved_ip_delete_ip_message, address))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                val snapshot = SavedIpStore.read(context)
                val operators = snapshot.operators.map { operator ->
                    if (operator.id == operatorId) operator.copy(ips = operator.ips.filterNot { it.address == address })
                    else operator
                }
                SavedIpStore.write(context, snapshot.copy(operators = operators))
                refresh()
            }
            .show()
    }

    private fun pasteIps() {
        val snapshot = SavedIpStore.read(context)
        val input = EditText(context).apply {
            setHint(R.string.saved_ip_paste_hint)
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val ids = HashMap<Int, String>()
        for (operator in snapshot.operators) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                text = displayName(operator)
            }
            ids[button.id] = operator.id
            group.addView(button)
            if (operator.id == snapshot.lastOperatorId) group.check(button.id)
        }
        if (group.checkedRadioButtonId == -1 && group.childCount > 0) group.check(group.getChildAt(0).id)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input, fullWidth())
            addView(
                TextView(context).apply {
                    setText(R.string.saved_ip_operator_label)
                    textSize = 13f
                    setTextColor(colorMuted)
                    setPadding(0, dp(8), 0, 0)
                },
                fullWidth(),
            )
            addView(group, fullWidth())
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_paste_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_add) { _, _ ->
                val operatorId = ids[group.checkedRadioButtonId].orEmpty()
                if (operatorId.isBlank()) return@setPositiveButton
                val extraction = SavedIpParser.extract(input.text.toString())
                if (extraction.addresses.isEmpty()) {
                    Toast.makeText(context, R.string.saved_ip_paste_no_valid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val current = SavedIpStore.read(context)
                val index = current.operators.indexOfFirst { it.id == operatorId }
                if (index < 0) return@setPositiveButton
                val operator = current.operators[index]
                val existing = operator.ips.mapTo(HashSet()) { it.address }
                val entries = operator.ips.toMutableList()
                var added = 0
                var repeated = extraction.duplicates
                for (address in extraction.addresses) {
                    if (existing.add(address)) {
                        entries += SavedIpEntry(address, SavedIpStatus.UNTESTED)
                        added++
                    } else {
                        repeated++
                    }
                }

                val operators = current.operators.toMutableList()
                operators[index] = operator.copy(ips = entries)
                val updated = current.copy(operators = operators, lastOperatorId = operatorId)
                try {
                    SavedIpStore.write(context, updated)
                    Toast.makeText(
                        context,
                        context.getString(R.string.saved_ip_paste_result, added, repeated, extraction.ignored),
                        Toast.LENGTH_LONG,
                    ).show()
                    refresh()
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(context, context.getString(R.string.saved_ip_save_limit, SavedIpStore.MAX_IP_COUNT), Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun copyIp(address: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ip", address))
        Toast.makeText(context, R.string.saved_ip_copied, Toast.LENGTH_SHORT).show()
    }

    private fun displayName(operator: SavedIpOperator): String = when (operator.id) {
        SavedIpStore.OPERATOR_TELECOM -> context.getString(R.string.saved_ip_operator_telecom)
        SavedIpStore.OPERATOR_UNICOM -> context.getString(R.string.saved_ip_operator_unicom)
        SavedIpStore.OPERATOR_MOBILE -> context.getString(R.string.saved_ip_operator_mobile)
        else -> operator.name
    }

    private fun actionButton(text: Int, click: () -> Unit) = MaterialButton(context).apply {
        setText(text)
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun actionText(text: Int, click: () -> Unit) = TextView(context).apply {
        setText(text)
        textSize = 13f
        setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary, colorText))
        gravity = Gravity.CENTER
        background = selectableBackground()
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { click() }
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(context).apply { setBackgroundColor(colorDivider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        parent.addView(View(context), LinearLayout.LayoutParams(1, dp(10)))
    }

    private fun selectableBackground() = android.util.TypedValue().let { value ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        context.getDrawable(value.resourceId)
    }

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            values.getColor(0, fallback)
        } finally {
            values.recycle()
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}
