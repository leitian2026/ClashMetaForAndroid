package com.github.kr328.clash.design.custom

import android.content.Context
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

// changes.md #175
class SaveIpBatchDialog(
    private val context: Context,
    private val rows: List<IpTestRow>,
    private val preferredOperatorId: String?,
    private val ipSaved: () -> IpTestRestore?,
) {
    private val snapshot = SavedIpStore.read(context)
    private val density = context.resources.displayMetrics.density
    private val pad = (20 * density + 0.5f).toInt()
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, 0, pad, 0)
    }
    private val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var selectedId = preferredOperatorId?.takeIf { id -> snapshot.operators.any { it.id == id } }
        ?: snapshot.lastOperatorId.takeIf { id -> snapshot.operators.any { it.id == id } }
        ?: snapshot.operators.firstOrNull()?.id.orEmpty()
    private var existingAddresses = snapshot.operators.firstOrNull { it.id == selectedId }
        ?.ips?.mapTo(HashSet()) { it.address }.orEmpty()
    private val radioButtons = LinkedHashMap<String, RadioButton>()
    private val operatorRows = LinkedHashMap<String, View>()
    private lateinit var pastedInput: EditText
    private lateinit var onlyPastedButton: com.google.android.material.button.MaterialButton
    private lateinit var onlyReachableButton: android.widget.Button
    private lateinit var saveCountView: TextView
    private lateinit var positive: android.widget.Button
    private lateinit var dialog: androidx.appcompat.app.AlertDialog
    private var refreshSaveAvailability: ((reloadStore: Boolean) -> Unit)? = null
    private var cancelPendingSaveAvailabilityAction: (() -> Unit)? = null
    private var pendingSaveAvailability: Runnable? = null

    fun show() {
        SavedIpStore.takeReadError()?.let {
            Toast.makeText(context, context.getString(R.string.saved_ip_read_failed, it), Toast.LENGTH_LONG).show()
        }
        buildHeader()
        buildOperatorList()
        buildPasteInput()
        buildOperatorActions()
        buildSaveCount()
        buildDialog()
        dialog.show()
    }

    private fun buildHeader() {
        content.addView(TextView(context).apply {
            setText(R.string.saved_ip_no_test_results)
            textSize = 13f
            alpha = 0.8f
            visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(context).apply {
            setText(R.string.saved_ip_operator_label)
            textSize = 13f
            alpha = 0.75f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildOperatorList() {
        snapshot.operators.forEach { appendOperatorChoice(it) }
        content.addView(group)
        snapshot.operators.forEach { operator -> operatorRows[operator.id]?.let { /* rows are attached by append */ } }
    }

    private fun appendOperatorChoice(operator: SavedIpOperator) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val radio = RadioButton(context).apply {
            text = savedIpOperatorDisplayName(context, operator).ifBlank { operator.id }
            isChecked = operator.id == selectedId
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                selectedId = operator.id
                radioButtons.forEach { (id, button) -> button.isChecked = id == selectedId }
                cancelPendingSaveAvailabilityAction?.invoke()
                refreshSaveAvailability?.invoke(true)
            }
        }
        val deleteButton = TextView(context).apply {
            text = context.getString(R.string.saved_ip_delete)
            textSize = 13f
            setTextColor(android.graphics.Color.RED)
            gravity = android.view.Gravity.CENTER
            setPadding((12 * density + 0.5f).toInt(), (8 * density + 0.5f).toInt(),
                (8 * density + 0.5f).toInt(), (8 * density + 0.5f).toInt())
            val backgroundValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, backgroundValue, true)
            if (backgroundValue.resourceId != 0) background = context.getDrawable(backgroundValue.resourceId)
            contentDescription = context.getString(R.string.saved_ip_operator_delete_title)
            setOnClickListener { confirmDeleteOperator(operator) }
        }
        row.addView(radio)
        row.addView(deleteButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        radioButtons[operator.id] = radio
        operatorRows[operator.id] = row
        group.addView(row)
    }

    private fun confirmDeleteOperator(operator: SavedIpOperator) {
        val current = SavedIpStore.read(context).operators.firstOrNull { it.id == operator.id } ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_operator_delete_title)
            .apply {
                if (current.ips.isNotEmpty()) {
                    setMessage(context.getString(R.string.saved_ip_operator_delete_message, current.ips.size))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    SavedIpStore.deleteOperator(context, current.id)
                    val latest = SavedIpStore.read(context)
                    operatorRows.remove(current.id)?.let(group::removeView)
                    radioButtons.remove(current.id)
                    if (selectedId == current.id) {
                        selectedId = latest.operators.firstOrNull()?.id.orEmpty()
                        radioButtons.forEach { (id, button) -> button.isChecked = id == selectedId }
                    }
                    existingAddresses = latest.operators.firstOrNull { it.id == selectedId }
                        ?.ips?.mapTo(HashSet()) { it.address }.orEmpty()
                    refreshSaveAvailability?.invoke(false)
                } catch (e: Exception) {
                    Log.e("ConfigCheckDesign", "Failed to delete saved-IP operator", e)
                    Toast.makeText(context, context.getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun buildPasteInput() {
        pastedInput = EditText(context).apply {
            setHint(R.string.saved_ip_paste_hint)
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        content.addView(pastedInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildOperatorActions() {
        val addOperatorButton = com.google.android.material.button.MaterialButton(context).apply {
            setText(R.string.saved_ip_add_operator)
            isAllCaps = false
            setOnClickListener { showAddOperatorDialog() }
        }
        onlyPastedButton = com.google.android.material.button.MaterialButton(context).apply {
            setText(R.string.saved_ip_only_pasted)
            isAllCaps = false
            setOnClickListener { savePastedOnly() }
        }
        val operatorActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(addOperatorButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * density + 0.5f).toInt()
            })
            addView(onlyPastedButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(operatorActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showAddOperatorDialog() {
        val input = EditText(context).apply {
            setHint(R.string.saved_ip_operator_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        val addDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_add_operator_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_add, null)
            .create()
        addDialog.setOnShowListener {
            addDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val error = savedIpOperatorNameError(context, name)
                if (error != 0) {
                    input.error = context.getString(error)
                    return@setOnClickListener
                }
                val latest = SavedIpStore.read(context)
                val operator = SavedIpOperator("custom_" + UUID.randomUUID().toString(), name)
                try {
                    SavedIpStore.write(context, latest.copy(operators = latest.operators + operator))
                } catch (e: Exception) {
                    Log.e("ConfigCheckDesign", "Failed to add saved-IP operator", e)
                    Toast.makeText(context, context.getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                addDialog.dismiss()
                selectedId = operator.id
                appendOperatorChoice(operator)
                existingAddresses = emptySet()
                radioButtons.forEach { (id, button) -> button.isChecked = id == selectedId }
                refreshSaveAvailability?.invoke(true)
            }
        }
        addDialog.show()
    }

    private fun buildSaveCount() {
        saveCountView = TextView(context).apply {
            textSize = 13f
            alpha = 0.8f
        }
        content.addView(saveCountView)
    }

    private fun buildDialog() {
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_save_title)
            .setView(android.widget.ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.saved_ip_only_reachable, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            onlyReachableButton = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
            onlyReachableButton.setOnClickListener { saveReachableOnly() }
            refreshSaveAvailability = ::refreshAvailability
            installInputListener()
            refreshSaveAvailability?.invoke(false)
            dialog.setOnDismissListener { cancelPendingSaveAvailability() }
            positive.setOnClickListener { saveBatch() }
        }
    }

    private fun savePastedOnly() {
        val operatorId = selectedId
        if (operatorId.isBlank()) return
        try {
            val pasted = SavedIpParser.extract(pastedInput.text.toString())
            if (pasted.addresses.isEmpty()) {
                Toast.makeText(context, R.string.saved_ip_paste_no_valid, Toast.LENGTH_LONG).show()
                return
            }
            val result = SavedIpStore.saveRows(context, operatorId, emptyList(), false,
                pasted.addresses, pasted.duplicates, pasted.ignored)
            showSaveResult(result)
            dialog.dismiss()
        } catch (e: SavedIpLimitException) {
            Log.w("ConfigCheckDesign", "Pasted IP save rejected by the ${SavedIpStore.MAX_IP_COUNT}-entry limit", e)
            Toast.makeText(context, context.getString(R.string.saved_ip_save_limit, SavedIpStore.MAX_IP_COUNT), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("ConfigCheckDesign", "Failed to save pasted IPs", e)
            Toast.makeText(context, context.getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveReachableOnly() {
        val operatorId = selectedId
        if (operatorId.isBlank()) return
        val reachableRows = rows.filter { !it.isDomain && it.ip.isNotBlank() && it.ok }
        if (reachableRows.isEmpty()) {
            Toast.makeText(context, R.string.saved_ip_paste_no_valid, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val saved = ipSaved()
            val result = SavedIpStore.saveRows(context, operatorId, reachableRows, false, emptyList(), 0, 0,
                testDirect = saved?.direct ?: true,
                testTarget = saved?.target ?: false,
                colos = saved?.report?.colos.orEmpty(),
                coloCountries = saved?.report?.coloCountries.orEmpty())
            showSaveResult(result)
            dialog.dismiss()
        } catch (e: SavedIpLimitException) {
            Log.w("ConfigCheckDesign", "Reachable IP save rejected by the ${SavedIpStore.MAX_IP_COUNT}-entry limit", e)
            Toast.makeText(context, context.getString(R.string.saved_ip_save_limit, SavedIpStore.MAX_IP_COUNT), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("ConfigCheckDesign", "Failed to save reachable IPs", e)
            Toast.makeText(context, context.getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshAvailability(reloadStore: Boolean) {
        if (reloadStore) {
            val currentOperator = SavedIpStore.read(context).operators.firstOrNull { it.id == selectedId }
            existingAddresses = currentOperator?.ips?.mapTo(HashSet()) { it.address }.orEmpty()
        }
        val eligibleRows = rows.filter { row -> !row.isDomain && row.ip.isNotBlank() }
        val reachableRows = eligibleRows.filter { it.ok }
        val testAddresses = eligibleRows.mapTo(LinkedHashSet()) { it.ip.trim() }
        val pasted = SavedIpParser.extract(pastedInput.text.toString())
        onlyPastedButton.isEnabled = selectedId.isNotBlank() && pasted.addresses.isNotEmpty()
        onlyReachableButton.isEnabled = selectedId.isNotBlank() && reachableRows.isNotEmpty()
        val newPastedCount = pasted.addresses.count { it !in testAddresses && it !in existingAddresses }
        val eligibleCount = testAddresses.size + newPastedCount
        saveCountView.text = context.getString(R.string.saved_ip_save_count, eligibleCount)
        positive.isEnabled = eligibleCount > 0 && selectedId.isNotBlank()
    }

    private fun installInputListener() {
        cancelPendingSaveAvailabilityAction = ::cancelPendingSaveAvailability
        pastedInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                cancelPendingSaveAvailability()
                val task = Runnable {
                    pendingSaveAvailability = null
                    refreshSaveAvailability?.invoke(false)
                }
                pendingSaveAvailability = task
                pastedInput.postDelayed(task, 200L)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun cancelPendingSaveAvailability() {
        pendingSaveAvailability?.let(pastedInput::removeCallbacks)
        pendingSaveAvailability = null
    }

    private fun saveBatch() {
        cancelPendingSaveAvailability()
        refreshSaveAvailability?.invoke(true)
        if (!positive.isEnabled) return
        val operatorId = selectedId
        if (operatorId.isBlank()) return
        try {
            val pasted = SavedIpParser.extract(pastedInput.text.toString())
            if (rows.isEmpty() && pasted.addresses.isEmpty()) {
                Toast.makeText(context, R.string.saved_ip_paste_no_valid, Toast.LENGTH_LONG).show()
                return
            }
            val saved = ipSaved()
            val result = SavedIpStore.saveRows(context, operatorId, rows, false,
                pasted.addresses, pasted.duplicates, pasted.ignored,
                testDirect = saved?.direct ?: true,
                testTarget = saved?.target ?: false,
                colos = saved?.report?.colos.orEmpty(),
                coloCountries = saved?.report?.coloCountries.orEmpty())
            showSaveResult(result)
            dialog.dismiss()
        } catch (e: SavedIpLimitException) {
            Log.w("ConfigCheckDesign", "Saved IP batch rejected by the ${SavedIpStore.MAX_IP_COUNT}-entry limit", e)
            Toast.makeText(context, context.getString(R.string.saved_ip_save_limit, SavedIpStore.MAX_IP_COUNT), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("ConfigCheckDesign", "Failed to save IP batch", e)
            Toast.makeText(context, context.getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    private fun showSaveResult(result: SavedIpSaveResult) {
        Toast.makeText(context, context.getString(R.string.saved_ip_save_result,
            result.added, result.updated, result.repeatedPasted, result.ignoredPasted, result.skippedDomains),
            Toast.LENGTH_LONG).show()
    }
}
