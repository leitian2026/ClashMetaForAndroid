package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.widget.Toast
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/**
 * CUSTOM.md #156 step 3: local saved-IP page. All list operations are local and remain available when Clash is stopped.
 * Steps 4–6 add in-memory multi-selection, timestamp labels, filters and local batch actions.
 * CUSTOM.md #156 step 6: rendering and list-only operations; no test flow or network behavior is changed.
 */
class SavedIpDesign(
    context: Context,
    private val onBack: () -> Unit,
    private val canRetest: Boolean,
    private val onRetest: (String, List<String>) -> Unit,
) : Design<SavedIpDesign.Request>(context) {
    enum class Request { Unused }
    private val density = context.resources.displayMetrics.density
    private val colorText = themeColor(android.R.attr.textColorPrimary, Color.BLACK)
    // Match the explicit color source used by the config-check page's Material text buttons.
    private val colorLink = themeColor(com.google.android.material.R.attr.colorPrimary, Color.BLUE)
    private val colorMuted = themeColor(android.R.attr.textColorSecondary, Color.GRAY)
    private val colorSurface = themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val colorDivider = themeColor(android.R.attr.colorControlHighlight, 0x22000000)

    override val root = LinearLayout(context).apply {
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

    // CUSTOM.md #156 step 4/6: filters and selection are page-local and never persisted.
    private enum class IpFilter { ALL, REACHABLE, UNREACHABLE, UNTESTED }
    private var currentFilter = IpFilter.ALL
    private var selectedOperatorId: String? = null
    private val selectedAddresses = LinkedHashSet<String>()

    private data class SavedIpTimeLabel(
        val full: String,
        val compact: String,
        val isDate: Boolean,
    )

    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(2), dp(12), dp(2))
    }
    private val filterBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(2), dp(12), dp(2))
    }
    private val selectionActions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        visibility = View.GONE
    }
    private val selectionMainActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val selectionQuickActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val selectedCountView = TextView(context).apply {
        textSize = 12f
        setTextColor(colorMuted)
        gravity = Gravity.CENTER
    }
    private val retestButton = actionButton(R.string.saved_ip_retest) { retestSelected() }
    private val deleteSelectedButton = actionButton(R.string.saved_ip_delete_selected) { deleteSelected() }
    private val cancelSelectionButton = actionButton(R.string.saved_ip_cancel_selection) { clearSelection() }
    private val selectUnreachableButton = actionButton(R.string.saved_ip_select_unreachable) { selectUnreachable() }
    private val selectOldestButton = actionButton(R.string.saved_ip_select_oldest_20) { selectOldest20() }

    private val filterButtons = listOf(
        filterButton(R.string.saved_ip_filter_all, IpFilter.ALL),
        filterButton(R.string.saved_ip_filter_reachable, IpFilter.REACHABLE),
        filterButton(R.string.saved_ip_filter_unreachable, IpFilter.UNREACHABLE),
        filterButton(R.string.saved_ip_filter_untested, IpFilter.UNTESTED),
    )

    init {
        // CUSTOM.md #156: keep the custom page clear of the translucent system bars, following the app's inset model.
        surface.addOnPropertyChangedCallback(object : androidx.databinding.Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(
                sender: androidx.databinding.Observable?,
                propertyId: Int,
            ) {
                root.setPadding(0, surface.insets.top, 0, surface.insets.bottom)
            }
        })
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        filterButtons.forEach { filterBar.addView(it, weighted(3)) }
        root.addView(filterBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        actions.addView(actionButton(R.string.saved_ip_add_operator) { addOperator() }, weighted(3))
        actions.addView(actionButton(R.string.saved_ip_paste) { pasteIps() }, weighted(3))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        selectionMainActions.addView(selectedCountView, weighted())
        selectionMainActions.addView(retestButton, weighted(2))
        selectionMainActions.addView(deleteSelectedButton, weighted(2))
        selectionMainActions.addView(cancelSelectionButton, weighted(2))
        selectionQuickActions.addView(selectUnreachableButton, weighted(2))
        selectionQuickActions.addView(selectOldestButton, weighted(2))
        selectionActions.addView(selectionMainActions, fullWidth())
        selectionActions.addView(selectionQuickActions, fullWidth())
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    fun refresh() {
        operatorList.removeAllViews()
        val snapshot = SavedIpStore.read(context)
        updateFilterButtons()
        reconcileSelection(snapshot)
        val total = snapshot.operators.sumOf { it.ips.size }
        val visibleGroups = snapshot.operators.mapNotNull { operator ->
            val entries = visibleEntries(operator)
            if (entries.isEmpty()) null else operator to entries
        }
        val visibleTotal = visibleGroups.sumOf { it.second.size }

        val emptyMessage = when {
            total == 0 -> R.string.saved_ip_empty
            visibleTotal == 0 -> R.string.saved_ip_filter_no_match
            else -> 0
        }
        if (emptyMessage != 0) {
            operatorList.addView(
                TextView(context).apply {
                    setText(emptyMessage)
                    textSize = 14f
                    setTextColor(colorMuted)
                    setPadding(0, dp(8), 0, dp(12))
                },
                fullWidth(),
            )
        }

        visibleGroups.forEachIndexed { index, (operator, entries) ->
            if (index > 0) addDivider(operatorList)
            addOperatorGroup(operator, entries)
        }
    }

    private fun visibleEntries(operator: SavedIpOperator): List<SavedIpEntry> {
        val filtered = operator.ips.filter { entry ->
            when (currentFilter) {
                IpFilter.ALL -> true
                IpFilter.REACHABLE -> entry.status == SavedIpStatus.REACHABLE
                IpFilter.UNREACHABLE -> entry.status == SavedIpStatus.UNREACHABLE
                IpFilter.UNTESTED -> entry.status == SavedIpStatus.UNTESTED
            }
        }
        return SavedIpStore.sortEntries(filtered)
    }

    private fun filterButton(label: Int, filter: IpFilter) = TextView(context).apply {
        setText(label)
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(colorLink)
        background = selectableBackground()
        setOnClickListener {
            if (currentFilter != filter) {
                currentFilter = filter
                clearSelectionState()
                refresh()
            }
        }
    }

    private fun updateFilterButtons() {
        filterButtons.forEachIndexed { index, button ->
            val selected = currentFilter.ordinal == index
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.paintFlags = if (selected) button.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG else button.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            button.setTextColor(colorLink)
        }
    }

    private fun addOperatorGroup(operator: SavedIpOperator, entries: List<SavedIpEntry>) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = displayName(operator)
        header.addView(
            TextView(context).apply {
                text = name + " (" + entries.size + ")"
                textSize = 16f
                setTextColor(colorText)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val unreachableCount = operator.ips.count { it.status == SavedIpStatus.UNREACHABLE }
        val deleteUnreachable = actionText(R.string.saved_ip_delete_unreachable) { confirmDeleteUnreachable(operator) }
        deleteUnreachable.isEnabled = unreachableCount > 0
        deleteUnreachable.alpha = if (unreachableCount > 0) 1f else 0.35f
        header.addView(deleteUnreachable)

        if (operator.id !in SavedIpStore.builtInOperatorIds && selectedOperatorId == null) {
            header.addView(actionText(R.string.saved_ip_rename) { editOperator(operator) })
            header.addView(actionText(R.string.saved_ip_delete) { confirmDeleteOperator(operator) })
        }
        operatorList.addView(header, fullWidth())

        for (entry in entries) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            if (selectedOperatorId == operator.id) {
                row.addView(
                    CheckBox(context).apply {
                        isChecked = entry.address in selectedAddresses
                        isClickable = false
                        isFocusable = false
                    },
                    LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT),
                )
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
                setSingleLine(true)
                ellipsize = null
                setOnClickListener {
                    when (selectedOperatorId) {
                        null -> copyIp(entry.address)
                        operator.id -> toggleSelection(operator.id, entry.address)
                        else -> Unit
                    }
                }
                setOnLongClickListener {
                    toggleSelection(operator.id, entry.address)
                    true
                }
            }
            row.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val remove = TextView(context).apply {
                text = "×"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(colorLink)
                contentDescription = context.getString(R.string.saved_ip_delete)
                background = selectableBackground()
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setOnClickListener { confirmDeleteIp(operator.id, entry.address) }
            }
            if (selectedOperatorId == null) {
                row.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            if (entry.lastTestedAt > 0L) {
                val label = savedIpTimeLabel(entry.lastTestedAt)
                val time = TextView(context).apply {
                    text = label.full
                    textSize = 14f
                    setTextColor(if (label.isDate) Color.rgb(255, 145, 0) else colorText)
                    gravity = Gravity.CENTER_VERTICAL
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(3), dp(8), dp(3), dp(8))
                    contentDescription = label.full
                }
                row.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                // Decide once on the first layout pass; no periodic refresh or timer.
                row.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        view: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int,
                    ) {
                        row.removeOnLayoutChangeListener(this)
                        val addressWidth = address.paint.measureText(entry.address)
                        val fullWidth = time.paint.measureText(label.full)
                        val fixedWidth = row.width - address.width
                        if (addressWidth + fullWidth + fixedWidth > row.width) {
                            time.text = label.compact
                        }
                    }
                })
            }
            operatorList.addView(row, fullWidth())
        }
    }

    private fun savedIpTimeLabel(at: Long): SavedIpTimeLabel {
        val now = System.currentTimeMillis()
        if (at > now) {
            return SavedIpTimeLabel(
                AgeText.reachAgo(context, at, now),
                AgeText.reachAgoCompact(context, at, now),
                false,
            )
        }
        val age = now - at
        if (age >= 15L * 24L * 60L * 60L * 1000L) {
            val tested = Calendar.getInstance().apply { timeInMillis = at }
            val current = Calendar.getInstance().apply { timeInMillis = now }
            val sameYear = tested.get(Calendar.YEAR) == current.get(Calendar.YEAR)
            val pattern = if (sameYear) {
                context.getString(R.string.saved_ip_time_date_same_year)
            } else {
                context.getString(R.string.saved_ip_time_date_other_year)
            }
            val compactPattern = if (sameYear) {
                context.getString(R.string.saved_ip_time_date_same_year_compact)
            } else {
                context.getString(R.string.saved_ip_time_date_other_year_compact)
            }
            val date = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
            val compactDate = SimpleDateFormat(compactPattern, Locale.getDefault()).format(Date(at))
            return SavedIpTimeLabel(date, compactDate, true)
        }
        return SavedIpTimeLabel(
            AgeText.reachAgo(context, at, now),
            AgeText.reachAgoCompact(context, at, now),
            false,
        )
    }

    // CUSTOM.md #156 step 4: enter / toggle selection only inside one operator group.
    private fun toggleSelection(operatorId: String, address: String) {
        val currentOperator = selectedOperatorId
        if (currentOperator != null && currentOperator != operatorId) return
        if (currentOperator == null) selectedOperatorId = operatorId

        if (address in selectedAddresses) {
            selectedAddresses.remove(address)
        } else {
            if (selectedAddresses.size >= 20) {
                Toast.makeText(context, R.string.saved_ip_max_20, Toast.LENGTH_SHORT).show()
                return
            }
            selectedAddresses.add(address)
        }

        if (selectedAddresses.isEmpty()) selectedOperatorId = null
        updateSelectionActions()
        refresh()
    }

    private fun reconcileSelection(snapshot: SavedIpSnapshot) {
        val operatorId = selectedOperatorId ?: run {
            updateSelectionActions()
            return
        }
        val operator = snapshot.operators.firstOrNull { it.id == operatorId }
        if (operator == null) {
            clearSelectionState()
            return
        }
        val existing = visibleEntries(operator).mapTo(HashSet()) { it.address }
        selectedAddresses.retainAll(existing)
        if (selectedAddresses.isEmpty()) clearSelectionState() else updateSelectionActions()
    }

    private fun updateSelectionActions() {
        val active = selectedOperatorId != null
        actions.visibility = if (active) View.GONE else View.VISIBLE
        selectionActions.visibility = if (active) View.VISIBLE else View.GONE
        selectedCountView.text = context.getString(R.string.saved_ip_selected_count, selectedAddresses.size)
        retestButton.isEnabled = canRetest && selectedAddresses.isNotEmpty()
        retestButton.alpha = if (retestButton.isEnabled) 1f else 0.35f
        deleteSelectedButton.isEnabled = selectedAddresses.isNotEmpty()
        deleteSelectedButton.alpha = if (deleteSelectedButton.isEnabled) 1f else 0.35f
        val operator = selectedOperatorId?.let { id ->
            SavedIpStore.read(context).operators.firstOrNull { it.id == id }
        }
        val visible = operator?.let { visibleEntries(it) }.orEmpty()
        selectUnreachableButton.isEnabled = visible.any { it.status == SavedIpStatus.UNREACHABLE }
        selectUnreachableButton.alpha = if (selectUnreachableButton.isEnabled) 1f else 0.35f
        selectOldestButton.isEnabled = visible.isNotEmpty()
        selectOldestButton.alpha = if (selectOldestButton.isEnabled) 1f else 0.35f
    }

    private fun selectUnreachable() {
        val operatorId = selectedOperatorId ?: return
        val operator = SavedIpStore.read(context).operators.firstOrNull { it.id == operatorId } ?: return
        val unreachable = visibleEntries(operator).filter { it.status == SavedIpStatus.UNREACHABLE }
        if (unreachable.isEmpty()) return
        val selected = unreachable.sortedBy { it.lastTestedAt }.take(20)
        selectedAddresses.clear()
        selectedAddresses.addAll(selected.map { it.address })
        if (selectedAddresses.isEmpty()) selectedOperatorId = null
        if (unreachable.size > 20) {
            Toast.makeText(context, context.getString(R.string.saved_ip_select_unreachable_overflow, unreachable.size), Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun selectOldest20() {
        val operatorId = selectedOperatorId ?: return
        val operator = SavedIpStore.read(context).operators.firstOrNull { it.id == operatorId } ?: return
        val selected = visibleEntries(operator).sortedBy { it.lastTestedAt }.take(20)
        selectedAddresses.clear()
        selectedAddresses.addAll(selected.map { it.address })
        if (selectedAddresses.isEmpty()) selectedOperatorId = null
        refresh()
    }

    private fun clearSelectionState() {
        selectedOperatorId = null
        selectedAddresses.clear()
        updateSelectionActions()
    }

    private fun clearSelection() {
        clearSelectionState()
        refresh()
    }

    private fun deleteSelected() {
        val operatorId = selectedOperatorId ?: return
        val addresses = selectedAddresses.toSet()
        if (addresses.isEmpty()) return

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_delete_ip_title)
            .setMessage(context.getString(R.string.saved_ip_delete_selected_message, addresses.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                val snapshot = SavedIpStore.read(context)
                val operators = snapshot.operators.map { operator ->
                    if (operator.id == operatorId) operator.copy(ips = operator.ips.filterNot { it.address in addresses })
                    else operator
                }
                SavedIpStore.write(context, snapshot.copy(operators = operators))
                clearSelectionState()
                refresh()
            }
            .show()
    }

    private fun retestSelected() {
        val operatorId = selectedOperatorId ?: return
        if (!canRetest) return
        val addresses = selectedAddresses.toList()
        if (addresses.isEmpty()) return
        if (addresses.size > 20) {
            Toast.makeText(context, R.string.saved_ip_max_20, Toast.LENGTH_SHORT).show()
            return
        }
        onRetest(operatorId, addresses)
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

    private fun confirmDeleteUnreachable(operator: SavedIpOperator) {
        val count = operator.ips.count { it.status == SavedIpStatus.UNREACHABLE }
        if (count == 0) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_delete_unreachable_title)
            .setMessage(context.getString(R.string.saved_ip_delete_unreachable_message, displayName(operator), count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                val snapshot = SavedIpStore.read(context)
                val operators = snapshot.operators.map { current ->
                    if (current.id == operator.id) current.copy(
                        ips = current.ips.filterNot { it.status == SavedIpStatus.UNREACHABLE }
                    ) else current
                }
                SavedIpStore.write(context, snapshot.copy(operators = operators))
                if (selectedOperatorId == operator.id) clearSelectionState()
                refresh()
                Toast.makeText(context, context.getString(R.string.saved_ip_delete_unreachable_done, count), Toast.LENGTH_SHORT).show()
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
                val addedAt = System.currentTimeMillis()
                var added = 0
                var repeated = extraction.duplicates
                for (address in extraction.addresses) {
                    if (existing.add(address)) {
                        entries += SavedIpEntry(address, SavedIpStatus.UNTESTED, addedAt = addedAt)
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

    private fun actionButton(text: Int, click: () -> Unit) = TextView(context).apply {
        setText(text)
        textSize = 14f
        setTextColor(colorLink)
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(4), 0, dp(4), 0)
        background = selectableBackground()
        setOnClickListener { click() }
    }

    private fun actionText(text: Int, click: () -> Unit) = TextView(context).apply {
        setText(text)
        textSize = 13f
        setTextColor(colorLink)
        gravity = Gravity.CENTER
        minHeight = dp(40)
        background = selectableBackground()
        setPadding(dp(8), dp(4), dp(8), dp(4))
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
    private fun weighted(gap: Int = 0) = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
        if (gap > 0) {
            marginStart = dp(gap)
            marginEnd = dp(gap)
        }
    }
}
