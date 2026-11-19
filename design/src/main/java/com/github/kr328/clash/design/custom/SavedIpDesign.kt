package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.widget.PopupMenu
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
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

// CUSTOM (CUSTOM.md #156 step 9): shared operator-name validation for saved-IP management entry points.
internal fun savedIpOperatorDisplayName(context: Context, operator: SavedIpOperator): String = when (operator.id) {
    SavedIpStore.OPERATOR_TELECOM -> context.getString(R.string.saved_ip_operator_telecom)
    SavedIpStore.OPERATOR_UNICOM -> context.getString(R.string.saved_ip_operator_unicom)
    SavedIpStore.OPERATOR_MOBILE -> context.getString(R.string.saved_ip_operator_mobile)
    else -> operator.name
}

internal fun savedIpOperatorNameError(context: Context, name: String, exceptId: String? = null): Int {
    val codePoints = name.codePointCount(0, name.length)
    return when {
        name.isEmpty() -> R.string.saved_ip_operator_name_empty
        codePoints > 12 -> R.string.saved_ip_operator_name_too_long
        SavedIpStore.read(context).operators.any { operator ->
            operator.id != exceptId && savedIpOperatorDisplayName(context, operator).equals(name, ignoreCase = true)
        } -> R.string.saved_ip_operator_name_duplicate
        else -> 0
    }
}

/**
 * CUSTOM.md #156 step 3: local saved-IP page. All list operations are local and remain available when Clash is stopped.
 * Steps 4–6 add in-memory multi-selection, timestamp labels, filters and local batch actions.
 * CUSTOM.md #156 step 6: rendering and list-only operations; no test flow or network behavior is changed.
 */
class SavedIpDesign(
    context: Context,
    private val onBack: () -> Unit,
    private var canRetest: Boolean,
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

    // CUSTOM.md #156 step 17: RecyclerView virtualizes IP rows; only visible rows are bound.
    private val listAdapter = SavedIpAdapter()
    private val operatorList = RecyclerView(context).apply {
        layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 2).apply {
            // CUSTOM (CUSTOM.md #156 step 22): IP cards use two columns; operator headers and messages span the full width.
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (listAdapter.getItemViewType(position) == 1) 1 else 2
            }
        }
        adapter = listAdapter
        setPadding(dp(16), dp(8), dp(16), dp(24))
        clipToPadding = false
        itemAnimator = null
    }

    private val searchInput = EditText(context).apply {
        setSingleLine(true)
        textSize = 14f
        setHint(R.string.saved_ip_search_hint)
        inputType = InputType.TYPE_CLASS_TEXT
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(colorSurface)
            setStroke(dp(1), colorDivider)
        }
    }
    private var snapshotCache = SavedIpStore.emptySnapshot()
    private var expandedOperatorId: String? = null

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

    // CUSTOM.md #156 step 16: compact overall status summary above the filter chips.
    private val summaryView = TextView(context).apply {
        textSize = 13f
        setTextColor(colorMuted)
        setPadding(dp(16), dp(10), dp(16), dp(6))
    }
    private val filterBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(2), dp(12), dp(8))
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
        root.addView(summaryView, fullWidth())

        filterButtons.forEach { filterBar.addView(it, weighted(3)) }
        root.addView(filterBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        searchInput.setPadding(dp(12), dp(8), dp(12), dp(8))
        root.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            bottomMargin = dp(6)
        })
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderRows()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        // CUSTOM.md #156 step 12: operator creation and IP pasting are managed from the config-check save dialog.

        selectionMainActions.addView(selectedCountView, weighted())
        selectionMainActions.addView(retestButton, weighted(2))
        selectionMainActions.addView(deleteSelectedButton, weighted(2))
        selectionMainActions.addView(cancelSelectionButton, weighted(2))
        selectionQuickActions.addView(selectUnreachableButton, weighted(2))
        selectionQuickActions.addView(selectOldestButton, weighted(2))
        selectionActions.addView(selectionMainActions, fullWidth())
        selectionActions.addView(selectionQuickActions, fullWidth())
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(operatorList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    // CUSTOM.md #156 step 24: update the retest gate when an existing page is brought forward.
    fun setCanRetest(value: Boolean) {
        canRetest = value
        updateSelectionActions()
    }

    fun refresh() {
        snapshotCache = SavedIpStore.read(context)
        SavedIpStore.takeReadError()?.let { // CUSTOM.md #156 step 13
            Toast.makeText(context, context.getString(R.string.saved_ip_read_failed, it), Toast.LENGTH_LONG).show()
        }
        updateFilterButtons(snapshotCache)
        reconcileSelection(snapshotCache)
        val allEntries = snapshotCache.operators.flatMap { it.ips }
        summaryView.text = context.getString(
            R.string.saved_ip_summary,
            allEntries.size,
            allEntries.count { it.status == SavedIpStatus.REACHABLE },
            allEntries.count { it.status == SavedIpStatus.UNREACHABLE },
            allEntries.count { it.status == SavedIpStatus.UNTESTED },
        )
        renderRows()
    }

    // Search and expansion only rebuild the lightweight row model. RecyclerView creates/binds
    // views for the viewport instead of inflating every saved IP at once.
    private fun renderRows() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val rows = ArrayList<SavedIpListRow>()
        for (operator in snapshotCache.operators) {
            val entries = visibleEntries(operator).filter { query.isEmpty() || it.address.contains(query, ignoreCase = true) }
            if (entries.isEmpty()) continue
            val expanded = query.isNotEmpty() || expandedOperatorId == operator.id || selectedOperatorId == operator.id
            rows += SavedIpListRow.Operator(operator, entries, expanded)
            if (expanded) entries.forEach { rows += SavedIpListRow.Ip(operator, it) }
        }
        if (rows.isEmpty()) {
            rows += SavedIpListRow.Message(
                if (snapshotCache.operators.sumOf { it.ips.size } == 0) R.string.saved_ip_empty
                else if (query.isNotEmpty()) R.string.saved_ip_search_no_match
                else R.string.saved_ip_filter_no_match,
            )
        }
        listAdapter.replaceRows(rows)
    }

    private sealed class SavedIpListRow {
        data class Operator(val operator: SavedIpOperator, val entries: List<SavedIpEntry>, val expanded: Boolean) : SavedIpListRow()
        data class Ip(val operator: SavedIpOperator, val entry: SavedIpEntry) : SavedIpListRow()
        data class Message(val text: Int) : SavedIpListRow()
    }

    private inner class SavedIpAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = ArrayList<SavedIpListRow>()

        fun replaceRows(updated: List<SavedIpListRow>) {
            rows.clear()
            rows.addAll(updated)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is SavedIpListRow.Operator -> 0
            is SavedIpListRow.Ip -> 1
            is SavedIpListRow.Message -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (viewType == 1) dp(4) else 0, if (viewType == 0) dp(10) else dp(4), if (viewType == 1) dp(4) else 0, if (viewType == 0) dp(8) else dp(4))
                if (viewType != 2) background = selectableBackground()
            }
            return object : RecyclerView.ViewHolder(container) {}
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val container = holder.itemView as LinearLayout
            container.removeAllViews()
            when (val row = rows[position]) {
                is SavedIpListRow.Operator -> bindOperatorRow(container, row)
                is SavedIpListRow.Ip -> bindIpRow(container, row.operator, row.entry)
                is SavedIpListRow.Message -> container.addView(TextView(context).apply {
                    setText(row.text)
                    textSize = 14f
                    setTextColor(colorMuted)
                    setPadding(0, dp(12), 0, dp(16))
                }, fullWidth())
            }
        }
    }

    private fun bindOperatorRow(container: LinearLayout, row: SavedIpListRow.Operator) {
        val operator = row.operator
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val arrow = TextView(context).apply {
            text = if (row.expanded) "▾" else "▸"
            textSize = 18f
            setTextColor(colorLink)
            gravity = Gravity.CENTER
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(28), dp(34)))
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        title.addView(TextView(context).apply {
            text = context.getString(R.string.saved_ip_group_title, displayName(operator), operator.ips.size)
            textSize = 15f
            setTextColor(colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, fullWidth())
        val reachable = operator.ips.count { it.status == SavedIpStatus.REACHABLE }
        val unreachable = operator.ips.count { it.status == SavedIpStatus.UNREACHABLE }
        val untested = operator.ips.count { it.status == SavedIpStatus.UNTESTED }
        title.addView(TextView(context).apply {
            text = context.getString(R.string.saved_ip_group_counts, reachable, unreachable, untested)
            textSize = 11f
            setTextColor(colorMuted)
        }, fullWidth())
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuButton = TextView(context).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(colorLink)
            background = selectableBackground()
            setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    if (unreachable > 0) menu.add(0, 1, 0, R.string.saved_ip_delete_unreachable)
                    if (operator.id !in SavedIpStore.builtInOperatorIds) {
                        menu.add(0, 2, 1, R.string.saved_ip_rename)
                        menu.add(0, 3, 2, R.string.saved_ip_delete)
                    }
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> confirmDeleteUnreachable(operator)
                            2 -> editOperator(operator)
                            3 -> confirmDeleteOperator(operator)
                        }
                        true
                    }
                    show()
                }
            }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(40), dp(36)))
        container.addView(header, fullWidth())
        container.setOnClickListener {
            expandedOperatorId = if (expandedOperatorId == operator.id) null else operator.id
            renderRows()
        }
        container.addView(View(context).apply { setBackgroundColor(colorDivider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }

    // CUSTOM (CUSTOM.md #156 step 22): compact two-column IP card; preserve copy, selection and delete actions.
    private fun bindIpRow(container: LinearLayout, operator: SavedIpOperator, entry: SavedIpEntry) {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (selectedOperatorId == operator.id) {
            // CUSTOM.md #156 step 24: the checkbox itself toggles the same IP selection as the address.
            row.addView(CheckBox(context).apply {
                isChecked = entry.address in selectedAddresses
                isClickable = true
                isFocusable = false
                setOnClickListener { toggleSelection(operator.id, entry.address) }
            }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val statusMark = FrameLayout(context)
        val dotColor = when (entry.status) {
            SavedIpStatus.REACHABLE -> CheckMark.MARK_DOT_GOOD
            SavedIpStatus.UNREACHABLE -> CheckMark.MARK_DOT_BAD
            SavedIpStatus.UNTESTED -> null
        }
        if (dotColor != null) {
            statusMark.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
            }, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
        }
        row.addView(statusMark, LinearLayout.LayoutParams(dp(18), dp(32)))
        // CUSTOM.md #156 (r217): increase saved-IP address text by one size.
        val address = TextView(context).apply {
            // CUSTOM.md #156 第 43 步：显示测试时已有的 IP 归属国家旗帜，不触发额外查询。
            val flag = CountryFlag.of(entry.countryCode)
            text = if (flag.isEmpty()) entry.address else "$flag ${entry.address}"
            textSize = 14f
            setTextColor(colorText)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(3))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setOnClickListener {
                if (selectedOperatorId == null) copyIp(entry.address)
                else toggleSelection(operator.id, entry.address)
            }
            setOnLongClickListener {
                toggleSelection(operator.id, entry.address)
                true
            }
        }
        row.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selectedOperatorId == null) {
            row.addView(TextView(context).apply {
                text = "×"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(colorLink)
                contentDescription = context.getString(R.string.saved_ip_delete)
                background = selectableBackground()
                setPadding(dp(5), dp(2), dp(5), dp(2))
                setOnClickListener { confirmDeleteIp(operator.id, entry.address) }
            }, LinearLayout.LayoutParams(dp(28), dp(32)).apply {
                // CUSTOM.md #156 step 26: move the delete icon inward toward its own IP card.
                marginEnd = dp(8)
            })
        }
        item.addView(row, fullWidth())
        val detail = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (selectedOperatorId == operator.id) 40 else 18), 0, dp(3), 0)
        }
        val statusText = TextView(context).apply {
            text = when (entry.status) {
                SavedIpStatus.REACHABLE -> context.getString(R.string.saved_ip_status_reachable)
                SavedIpStatus.UNREACHABLE -> context.getString(R.string.saved_ip_status_unreachable)
                SavedIpStatus.UNTESTED -> context.getString(R.string.saved_ip_status_untested)
            }
            textSize = 11f
            setSingleLine(true)
            setTextColor(when (entry.status) {
                SavedIpStatus.REACHABLE -> CheckMark.MARK_DOT_GOOD
                SavedIpStatus.UNREACHABLE -> CheckMark.MARK_DOT_BAD
                SavedIpStatus.UNTESTED -> colorMuted
            })
        }
        detail.addView(statusText)
        if (entry.lastTestedAt > 0L) {
            val label = savedIpTimeLabel(entry.lastTestedAt)
            detail.addView(View(context), LinearLayout.LayoutParams(dp(4), 1))
            detail.addView(TextView(context).apply {
                text = label.compact
                textSize = 11f
                setTextColor(if (label.isDate) Color.rgb(255, 145, 0) else colorMuted)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        item.addView(detail, fullWidth())
        container.addView(item, fullWidth())
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
                renderRows()
                updateFilterButtons(snapshotCache)
            }
        }
    }

    private fun updateFilterButtons(snapshot: SavedIpSnapshot) {
        val entries = snapshot.operators.flatMap { it.ips }
        val counts = listOf(
            entries.size,
            entries.count { it.status == SavedIpStatus.REACHABLE },
            entries.count { it.status == SavedIpStatus.UNREACHABLE },
            entries.count { it.status == SavedIpStatus.UNTESTED },
        )
        val labels = listOf(
            R.string.saved_ip_filter_all_count,
            R.string.saved_ip_filter_reachable_count,
            R.string.saved_ip_filter_unreachable_count,
            R.string.saved_ip_filter_untested_count,
        )
        filterButtons.forEachIndexed { index, button ->
            val selected = currentFilter.ordinal == index
            button.text = context.getString(labels[index], counts[index])
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.paintFlags = button.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            button.setTextColor(if (selected) colorLink else colorMuted)
            button.background = if (selected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(colorDivider)
                }
            } else {
                selectableBackground()
            }
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
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(5), 0, dp(5))
                background = selectableBackground()
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
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

            // CUSTOM.md #156 step 14/16: retain the reachability dot and add a readable status label below the address.
            val statusMark = FrameLayout(context).apply {
                val dotColor = when (entry.status) {
                    SavedIpStatus.REACHABLE -> CheckMark.MARK_DOT_GOOD
                    SavedIpStatus.UNREACHABLE -> CheckMark.MARK_DOT_BAD
                    SavedIpStatus.UNTESTED -> null
                }
                if (dotColor != null) {
                    addView(
                        View(context).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(dotColor)
                            }
                        },
                        FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER),
                    )
                }
            }
            row.addView(statusMark, LinearLayout.LayoutParams(dp(30), dp(36)))

            val address = TextView(context).apply {
                text = entry.address
                textSize = 15f
                setTextColor(colorText)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(3), dp(4), dp(3))
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.MIDDLE
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

            if (selectedOperatorId == null) {
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
                row.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
            }
            item.addView(row, fullWidth())

            val detail = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (selectedOperatorId == operator.id) 64 else 34), 0, dp(8), 0)
            }
            val statusText = TextView(context).apply {
                text = when (entry.status) {
                    SavedIpStatus.REACHABLE -> context.getString(R.string.saved_ip_status_reachable)
                    SavedIpStatus.UNREACHABLE -> context.getString(R.string.saved_ip_status_unreachable)
                    SavedIpStatus.UNTESTED -> context.getString(R.string.saved_ip_status_untested)
                }
                textSize = 12f
                setTextColor(when (entry.status) {
                    SavedIpStatus.REACHABLE -> CheckMark.MARK_DOT_GOOD
                    SavedIpStatus.UNREACHABLE -> CheckMark.MARK_DOT_BAD
                    SavedIpStatus.UNTESTED -> colorMuted
                })
                setSingleLine(true)
            }
            detail.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            if (entry.lastTestedAt > 0L) {
                val label = savedIpTimeLabel(entry.lastTestedAt)
                detail.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
                detail.addView(
                    TextView(context).apply {
                        text = label.full
                        textSize = 12f
                        setTextColor(if (label.isDate) Color.rgb(255, 145, 0) else colorMuted)
                        gravity = Gravity.CENTER_VERTICAL
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        contentDescription = label.full
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            item.addView(detail, fullWidth())
            operatorList.addView(item, fullWidth())
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
        renderRows()
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
        val existing = operator.ips.mapTo(HashSet()) { it.address }
        selectedAddresses.retainAll(existing)
        if (selectedAddresses.isEmpty()) clearSelectionState() else updateSelectionActions()
    }

    private fun updateSelectionActions() {
        val active = selectedOperatorId != null
        selectionActions.visibility = if (active) View.VISIBLE else View.GONE
        selectedCountView.text = context.getString(R.string.saved_ip_selected_count, selectedAddresses.size)
        retestButton.isEnabled = canRetest && selectedAddresses.isNotEmpty()
        retestButton.alpha = if (retestButton.isEnabled) 1f else 0.35f
        deleteSelectedButton.isEnabled = selectedAddresses.isNotEmpty()
        deleteSelectedButton.alpha = if (deleteSelectedButton.isEnabled) 1f else 0.35f
        val operator = selectedOperatorId?.let { id ->
            snapshotCache.operators.firstOrNull { it.id == id }
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
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { operator ->
                        if (operator.id == operatorId) operator.copy(ips = operator.ips.filterNot { it.address in addresses })
                        else operator
                    })
                }) return@setPositiveButton
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
        // CUSTOM.md #156 step 23: clear the old multi-selection before handing off to ConfigCheckActivity.
        // SavedIpActivity stays on the back stack while the test page is open; retaining this selection
        // makes the next visit look as though the previous batch is still active.
        clearSelectionState()
        renderRows()
        onRetest(operatorId, addresses)
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
                val error = savedIpOperatorNameError(context, name, existing?.id)
                if (error != 0) {
                    input.error = context.getString(error)
                    return@setOnClickListener
                }

                if (!writeSnapshot { snapshot ->
                    val operators = snapshot.operators.toMutableList()
                    if (existing == null) {
                        operators += SavedIpOperator("custom_" + UUID.randomUUID().toString(), name)
                    } else {
                        val index = operators.indexOfFirst { it.id == existing.id }
                        if (index >= 0) operators[index] = operators[index].copy(name = name)
                    }
                    snapshot.copy(operators = operators)
                }) return@setOnClickListener
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun confirmDeleteOperator(operator: SavedIpOperator) {
        if (operator.id in SavedIpStore.builtInOperatorIds) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_operator_delete_title)
            .setMessage(context.getString(R.string.saved_ip_operator_delete_message, operator.ips.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(
                        operators = snapshot.operators.filterNot { it.id == operator.id },
                        lastOperatorId = if (snapshot.lastOperatorId == operator.id) SavedIpStore.OPERATOR_TELECOM else snapshot.lastOperatorId,
                    )
                }) return@setPositiveButton
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
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { current ->
                        if (current.id == operator.id) current.copy(
                            ips = current.ips.filterNot { it.status == SavedIpStatus.UNREACHABLE }
                        ) else current
                    })
                }) return@setPositiveButton
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
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { operator ->
                        if (operator.id == operatorId) operator.copy(ips = operator.ips.filterNot { it.address == address })
                        else operator
                    })
                }) return@setPositiveButton
                refresh()
            }
            .show()
    }

    // CUSTOM.md #156 step 8: centralize error handling for every saved-list mutation.
    private fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean {
        return try {
            SavedIpStore.update(context, transform)
            true
        } catch (e: SavedIpLimitException) {
            Log.w("SavedIpDesign", "Saved IP list reached its ${SavedIpStore.MAX_IP_COUNT}-entry limit", e)
            Toast.makeText(context, context.getString(R.string.saved_ip_save_limit, SavedIpStore.MAX_IP_COUNT), Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Log.e("SavedIpDesign", "Failed to write saved IP list", e)
            Toast.makeText(context, context.getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun copyIp(address: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ip", address))
        Toast.makeText(context, R.string.saved_ip_copied, Toast.LENGTH_SHORT).show()
    }

    private fun displayName(operator: SavedIpOperator): String = savedIpOperatorDisplayName(context, operator)

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
