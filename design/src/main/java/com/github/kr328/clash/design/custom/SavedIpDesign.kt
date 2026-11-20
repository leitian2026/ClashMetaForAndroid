package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
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
    savedState: Bundle? = null,
) : Design<SavedIpDesign.Request>(context) {
    enum class Request { Unused }
    private val filterState = SavedIpFilterState.restore(savedState)
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
        setNavigationOnClickListener { if (!handleBackPressed()) onBack() }
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
        setPadding(dp(12), dp(8), dp(42), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(colorSurface)
            setStroke(dp(1), colorDivider)
        }
    }
    private val searchClearButton = TextView(context).apply {
        text = "×"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(colorMuted)
        contentDescription = context.getString(R.string.saved_ip_search_clear)
        background = selectableBackground()
        setOnClickListener { searchInput.text?.clear() }
    }
    private val searchBox = FrameLayout(context).apply {
        visibility = View.GONE
        addView(searchInput, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        addView(searchClearButton, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
    }
    private var snapshotCache = SavedIpStore.emptySnapshot()
    private var expandedOperatorId: String?
        get() = filterState.expandedOperatorId
        set(value) { filterState.expandedOperatorId = value }

    // CUSTOM.md #156: status, country, sort and view state are page-local and restored with the Activity.
    private val selectedCountryCodes get() = filterState.selectedCountryCodes
    private var currentFilter: IpFilter
        get() = filterState.currentFilter
        set(value) { filterState.currentFilter = value }
    private var currentSort: SavedIpSort
        get() = filterState.sort
        set(value) { filterState.sort = value }
    private var selectedOperatorId: String? = null
    private val selectedAddresses = LinkedHashSet<String>()

    private data class SavedIpTimeLabel(
        val full: String,
        val compact: String,
        val isDate: Boolean,
    )

    // CUSTOM.md #159 (r291): compact toolbar with anchored dropdown menus.
    private val filterToolbar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
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

    private val searchIndicator = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorLink)
        }
        visibility = View.GONE
    }
    private val searchIconButton = FrameLayout(context).apply {
        background = selectableBackground()
        contentDescription = context.getString(R.string.saved_ip_search_hint)
        addView(TextView(context).apply {
            text = "🔍"
            textSize = 18f
            gravity = Gravity.CENTER
            isClickable = false
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(searchIndicator, FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7)
            marginEnd = dp(7)
        })
        setOnClickListener { expandSearch() }
    }
    private val searchCloseButton = TextView(context).apply {
        text = "×"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(colorLink)
        contentDescription = context.getString(R.string.saved_ip_search_close)
        background = selectableBackground()
        visibility = View.GONE
        setOnClickListener { collapseSearch() }
    }
    private val statusFilterButton = actionText(R.string.saved_ip_filter_all) { showStatusMenu(it) }
    private val countryFilterButton = actionText(R.string.saved_ip_filter_country) { showCountryPopup(it) }
    private val sortButton = actionText(R.string.saved_ip_sort_button) { showSortMenu(it) }
    private var countryPopup: PopupWindow? = null

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

        filterToolbar.addView(searchIconButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        filterToolbar.addView(searchBox, LinearLayout.LayoutParams(0, dp(40), 2f))
        filterToolbar.addView(searchCloseButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        filterToolbar.addView(statusFilterButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        filterToolbar.addView(countryFilterButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        filterToolbar.addView(sortButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(filterToolbar, fullWidth())

        searchInput.setPadding(dp(12), dp(8), dp(12), dp(8))
        searchInput.setText(filterState.query)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterState.query = s?.toString().orEmpty()
                updateSearchIndicator()
                renderRows()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateSearchUi()

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
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) renderRows()
            }

            override fun onViewAttachedToWindow(view: View) {
                view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
            }

            override fun onViewDetachedFromWindow(view: View) {
                view.viewTreeObserver.let { observer ->
                    if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
                }
            }
        })
        refresh()
    }

    // CUSTOM.md #156 step 24: update the retest gate when an existing page is brought forward.
    fun setCanRetest(value: Boolean) {
        canRetest = value
        updateSelectionActions()
    }

    fun saveState(outState: Bundle) {
        filterState.query = searchInput.text?.toString().orEmpty()
        filterState.save(outState)
    }

    fun refresh() {
        snapshotCache = SavedIpStore.read(context)
        SavedIpStore.takeReadError()?.let { // CUSTOM.md #156 step 13
            Toast.makeText(context, context.getString(R.string.saved_ip_read_failed, it), Toast.LENGTH_LONG).show()
        }
        reconcileCountryFilter(snapshotCache)
        reconcileSelection(snapshotCache)
        updateFilterToolbar()
        renderRows()
    }

    // Search and expansion only rebuild the lightweight row model. RecyclerView creates/binds
    // views for the viewport instead of inflating every saved IP at once.
    private fun renderRows() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val rows = ArrayList<SavedIpListRow>()
        for (operator in snapshotCache.operators) {
            // While selecting a group, show that whole group regardless of the active filter or search text.
            val entries = if (selectedOperatorId == operator.id) {
                // Multi-selection deliberately shows the complete selected operator, bypassing every filter.
                sortSavedIpEntries(SavedIpStore.sortEntries(operator.ips), currentSort)
            } else {
                visibleEntries(operator).filter { query.isEmpty() || it.address.contains(query, ignoreCase = true) }
            }
            if (entries.isEmpty()) continue
            val expanded = query.isNotEmpty() || expandedOperatorId == operator.id || selectedOperatorId == operator.id
            rows += SavedIpListRow.Operator(
                operator,
                entries,
                expanded,
                if (selectedOperatorId == operator.id) selectedAddresses.toSet() else emptySet(),
            )
            if (expanded) entries.forEach { rows += SavedIpListRow.Ip(operator, it) }
        }
        if (rows.isEmpty()) {
            rows += SavedIpListRow.Message(
                if (snapshotCache.operators.none { it.ips.isNotEmpty() }) R.string.saved_ip_empty
                else R.string.saved_ip_filter_no_match,
            )
        }
        listAdapter.replaceRows(rows)
    }

    private sealed class SavedIpListRow {
        data class Operator(
            val operator: SavedIpOperator,
            val entries: List<SavedIpEntry>,
            val expanded: Boolean,
            // Included in row equality so selection changes still rebind checkbox state.
            val selectedAddresses: Set<String>,
        ) : SavedIpListRow()
        data class Ip(val operator: SavedIpOperator, val entry: SavedIpEntry) : SavedIpListRow()
        data class Message(val text: Int) : SavedIpListRow()
    }

    private inner class SavedIpAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = ArrayList<SavedIpListRow>()

        fun replaceRows(updated: List<SavedIpListRow>) {
            if (rows == updated) return
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
        val reachable = row.entries.count { it.status == SavedIpStatus.REACHABLE }
        val unreachable = row.entries.count { it.status == SavedIpStatus.UNREACHABLE }
        val untested = row.entries.count { it.status == SavedIpStatus.UNTESTED }
        val allUnreachable = operator.ips.any { it.status == SavedIpStatus.UNREACHABLE }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        val arrow = TextView(context).apply {
            text = if (row.expanded) "▾" else "▸"
            textSize = 18f
            setTextColor(colorLink)
            gravity = Gravity.CENTER
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        title.addView(TextView(context).apply {
            text = context.getString(R.string.saved_ip_group_title, displayName(operator), row.entries.size)
            textSize = 15f
            setTextColor(colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, fullWidth())
        val statusTags = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusTags.addView(groupStatusTag(R.string.saved_ip_status_reachable, reachable, Color.rgb(46, 125, 50)))
        statusTags.addView(groupStatusTag(R.string.saved_ip_status_unreachable, unreachable, Color.rgb(198, 40, 40)))
        statusTags.addView(groupStatusTag(R.string.saved_ip_status_untested, untested, Color.rgb(239, 143, 0)))
        title.addView(statusTags, fullWidth())
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuButton = TextView(context).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(colorLink)
            background = selectableBackground()
            contentDescription = context.getString(R.string.saved_ip_group_menu)
            setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    menu.add(0, 1, 0, R.string.saved_ip_delete_unreachable).isEnabled = allUnreachable
                    menu.add(0, 4, 1, R.string.saved_ip_delete_older)
                    menu.add(0, 5, 2, R.string.saved_ip_select_age)
                    if (operator.id !in SavedIpStore.builtInOperatorIds) {
                        menu.add(0, 2, 3, R.string.saved_ip_rename)
                        menu.add(0, 3, 4, R.string.saved_ip_delete)
                    }
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> confirmDeleteUnreachable(operator)
                            2 -> editOperator(operator)
                            3 -> confirmDeleteOperator(operator)
                            4 -> chooseDeleteOlder(operator)
                            5 -> chooseSelectAge(operator)
                        }
                        true
                    }
                    show()
                }
            }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        container.addView(header, fullWidth())
        container.setOnClickListener {
            expandedOperatorId = if (expandedOperatorId == operator.id) null else operator.id
            renderRows()
        }
        container.addView(View(context).apply { setBackgroundColor(colorDivider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun groupStatusTag(label: Int, count: Int, color: Int): TextView =
        TextView(context).apply {
            text = "${context.getString(label)} $count"
            textSize = 10f
            setTextColor(color)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(24, Color.red(color), Color.green(color), Color.blue(color)))
            }
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
        val showDelete = selectedOperatorId == null
        val selectionWidth = if (selectedOperatorId == operator.id) dp(30) else 0
        val deleteWidth = if (showDelete) dp(56) else 0
        fun updateAddressMaxWidth() {
            address.maxWidth = (row.width - dp(18 + 4) - selectionWidth - deleteWidth).coerceAtLeast(dp(24))
        }
        val fallbackRowWidth = (context.resources.displayMetrics.widthPixels - dp(32)) / 2 - dp(8)
        address.maxWidth = (fallbackRowWidth - dp(18 + 4) - selectionWidth - deleteWidth).coerceAtLeast(dp(24))
        row.addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // CUSTOM.md #156 第 44 步：测量双列单元格后限制 IP 文本宽度，删除按钮紧跟文字，不占用行尾空白。
        row.post { updateAddressMaxWidth() }
        if (showDelete) {
            row.addView(TextView(context).apply {
                text = "×"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(colorLink)
                contentDescription = context.getString(R.string.saved_ip_delete)
                background = selectableBackground()
                minWidth = dp(48)
                minHeight = dp(48)
                setPadding(0, 0, 0, 0)
                setOnClickListener { confirmDeleteIp(operator.id, entry.address) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(8)
            })
        }
        item.addView(row, fullWidth())
        val detail = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (selectedOperatorId == operator.id) 40 else 18), 0, dp(3), 0)
        }
        // CUSTOM.md #158: show the saved test metrics instead of repeating the reachability status label.
        val metricsText = TextView(context).apply {
            val latency = if (entry.avg > 0) "${entry.avg}ms" else "-"
            val speed = if (entry.speed > 0.0) {
                String.format(Locale.US, "%.1f", entry.speed) + " MB/s"
            } else {
                "-"
            }
            text = context.getString(R.string.saved_ip_metrics, latency, speed)
            textSize = 11f
            setSingleLine(true)
            setTextColor(colorMuted)
        }
        detail.addView(metricsText)
        if (entry.lastTestedAt > 0L) {
            val label = savedIpTimeLabel(entry.lastTestedAt)
            detail.addView(View(context), LinearLayout.LayoutParams(dp(4), 1))
            detail.addView(TextView(context).apply {
                text = savedIpElapsedTime(entry.lastTestedAt)
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
        val filtered = operator.ips.filter { entry -> filterState.matchesStatus(entry.status) }
        val countryFiltered = filterSavedIpEntriesByCountry(filtered, selectedCountryCodes)
        return sortSavedIpEntries(SavedIpStore.sortEntries(countryFiltered), currentSort)
    }

    // CUSTOM.md #160 (r291): scrollable anchored popup; checkboxes stay open for multi-selection.
    private fun showCountryPopup(anchor: View) {
        if (filterState.searchExpanded) collapseSearch()
        if (countryPopup?.isShowing == true) {
            countryPopup?.dismiss()
            countryPopup = null
            return
        }
        val codes = savedIpCountryCodes(snapshotCache.operators)
        if (codes.isEmpty()) return

        val choices = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        codes.forEach { code ->
            choices.addView(CheckBox(context).apply {
                text = "${CountryFlag.of(code)} ${CountryName.label(code)}"
                textSize = 14f
                isChecked = code in selectedCountryCodes
                minHeight = dp(44)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedCountryCodes.add(code) else selectedCountryCodes.remove(code)
                    clearSelectionState()
                    updateFilterToolbar()
                    renderRows()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(choices, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popupWidth = maxOf(anchor.width, dp(220)).coerceAtMost(
            context.resources.displayMetrics.widthPixels - dp(24),
        )
        val popupHeight = minOf(dp(280), dp(48 * codes.size.coerceAtLeast(1)))
        val popup = PopupWindow(scroll, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(colorSurface))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            setOnDismissListener { if (countryPopup === this) countryPopup = null }
        }
        countryPopup = popup
        popup.showAsDropDown(anchor)
    }

    private fun reconcileCountryFilter(snapshot: SavedIpSnapshot) {
        selectedCountryCodes.retainAll(savedIpCountryCodes(snapshot.operators).toSet())
    }

    private fun showStatusMenu(anchor: View) {
        if (filterState.searchExpanded) collapseSearch()
        countryPopup?.dismiss()
        val entries = snapshotCache.operators.flatMap { it.ips }
        val counts = listOf(
            entries.size,
            entries.count { it.status == SavedIpStatus.REACHABLE },
            entries.count { it.status == SavedIpStatus.UNREACHABLE },
            entries.count { it.status == SavedIpStatus.UNTESTED },
        )
        val filters = listOf(
            IpFilter.ALL to R.string.saved_ip_filter_all_count,
            IpFilter.REACHABLE to R.string.saved_ip_filter_reachable_count,
            IpFilter.UNREACHABLE to R.string.saved_ip_filter_unreachable_count,
            IpFilter.UNTESTED to R.string.saved_ip_filter_untested_count,
        )
        PopupMenu(context, anchor).apply {
            filters.forEachIndexed { index, (filter, label) ->
                menu.add(0, filter.ordinal, index, context.getString(label, counts[index])).isCheckable = true
            }
            menu.setGroupCheckable(0, true, true)
            menu.findItem(currentFilter.ordinal)?.isChecked = true
            setOnMenuItemClickListener { item ->
                val filter = IpFilter.values().getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                currentFilter = filter
                clearSelectionState()
                updateFilterToolbar()
                renderRows()
                true
            }
            show()
        }
    }

    private fun showSortMenu(anchor: View) {
        if (filterState.searchExpanded) collapseSearch()
        countryPopup?.dismiss()
        val options = listOf(
            SavedIpSort.DEFAULT to R.string.saved_ip_sort_default,
            SavedIpSort.AVG_ASCENDING to R.string.saved_ip_sort_latency,
            SavedIpSort.SPEED_DESCENDING to R.string.saved_ip_sort_speed,
        )
        PopupMenu(context, anchor).apply {
            options.forEachIndexed { index, (sort, label) ->
                menu.add(0, sort.ordinal, index, label).isCheckable = true
            }
            menu.setGroupCheckable(0, true, true)
            menu.findItem(currentSort.ordinal)?.isChecked = true
            setOnMenuItemClickListener { item ->
                currentSort = SavedIpSort.values().getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                updateFilterToolbar()
                renderRows()
                true
            }
            show()
        }
    }

    private fun expandSearch() {
        filterState.searchExpanded = true
        updateFilterToolbar()
        searchInput.requestFocus()
        searchInput.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun collapseSearch() {
        if (!filterState.searchExpanded) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        filterState.searchExpanded = false
        searchInput.clearFocus()
        updateFilterToolbar()
    }

    private fun updateSearchUi() {
        val expanded = filterState.searchExpanded
        searchIconButton.visibility = if (expanded) View.GONE else View.VISIBLE
        searchBox.visibility = if (expanded) View.VISIBLE else View.GONE
        searchCloseButton.visibility = if (expanded) View.VISIBLE else View.GONE
        val params = searchBox.layoutParams as? LinearLayout.LayoutParams
        if (params != null) {
            params.weight = if (expanded) 2f else 0f
            searchBox.layoutParams = params
        }
        updateSearchIndicator()
    }

    private fun updateSearchIndicator() {
        val query = searchInput.text?.toString().orEmpty()
        searchIndicator.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE
        searchClearButton.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
    }

    fun handleBackPressed(): Boolean {
        if (filterState.searchExpanded) {
            collapseSearch()
            return true
        }
        return false
    }

    private fun updateFilterToolbar() {
        statusFilterButton.text = context.getString(when (currentFilter) {
            IpFilter.ALL -> R.string.saved_ip_filter_all
            IpFilter.REACHABLE -> R.string.saved_ip_filter_reachable
            IpFilter.UNREACHABLE -> R.string.saved_ip_filter_unreachable
            IpFilter.UNTESTED -> R.string.saved_ip_filter_untested
        }) + " ▾"
        statusFilterButton.setTextColor(when (currentFilter) {
            IpFilter.ALL -> colorText
            IpFilter.REACHABLE -> Color.rgb(46, 125, 50)
            IpFilter.UNREACHABLE -> Color.rgb(198, 40, 40)
            IpFilter.UNTESTED -> Color.rgb(239, 143, 0)
        })
        countryFilterButton.text = if (selectedCountryCodes.isEmpty()) {
            context.getString(R.string.saved_ip_filter_country) + " ▾"
        } else context.getString(R.string.saved_ip_filter_country_selected, selectedCountryCodes.size) + " ▾"
        sortButton.text = context.getString(when (currentSort) {
            SavedIpSort.DEFAULT -> R.string.saved_ip_sort_button
            SavedIpSort.AVG_ASCENDING -> R.string.saved_ip_sort_latency
            SavedIpSort.SPEED_DESCENDING -> R.string.saved_ip_sort_speed
        }) + " ▾"
        updateSearchUi()
        countryFilterButton.isEnabled = savedIpCountryCodes(snapshotCache.operators).isNotEmpty()
        countryFilterButton.alpha = if (countryFilterButton.isEnabled) 1f else 0.35f
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

    // CUSTOM.md #156 第 45 步：精确到秒的已保存 IP 相对测试时间；仅在列表行绑定时计算。
    private fun savedIpElapsedTime(at: Long): String {
        val seconds = ((System.currentTimeMillis() - at) / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60L -> context.getString(R.string.saved_ip_elapsed_seconds, seconds.toInt())
            seconds < 3600L -> context.getString(
                R.string.saved_ip_elapsed_minutes_seconds,
                (seconds / 60L).toInt(),
                (seconds % 60L).toInt(),
            )
            seconds < 86400L -> context.getString(
                R.string.saved_ip_elapsed_hours_minutes,
                (seconds / 3600L).toInt(),
                ((seconds % 3600L) / 60L).toInt(),
            )
            else -> context.getString(
                R.string.saved_ip_elapsed_days_hours,
                (seconds / 86400L).toInt(),
                ((seconds % 86400L) / 3600L).toInt(),
            )
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

    private fun chooseDeleteOlder(operator: SavedIpOperator) {
        val days = intArrayOf(1, 3, 7, 30)
        val labels = arrayOf(
            context.getString(R.string.saved_ip_days_1),
            context.getString(R.string.saved_ip_days_3),
            context.getString(R.string.saved_ip_days_7),
            context.getString(R.string.saved_ip_days_30),
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_delete_older_title)
            .setItems(labels) { _, which ->
                if (which in days.indices) {
                    val cutoff = System.currentTimeMillis() - days[which].toLong() * 24L * 60L * 60L * 1000L
                    confirmDeleteOlder(operator, days[which], cutoff)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteOlder(operator: SavedIpOperator, days: Int, cutoff: Long) {
        val current = snapshotCache.operators.firstOrNull { it.id == operator.id } ?: operator
        val count = current.ips.count { it.lastTestedAt > 0L && it.lastTestedAt < cutoff }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_delete_older_confirm_title)
            .setMessage(context.getString(R.string.saved_ip_delete_older_message, displayName(operator), count, days))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                var deleted = 0
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { saved ->
                        if (saved.id == operator.id) {
                            val remaining = saved.ips.filterNot { it.lastTestedAt > 0L && it.lastTestedAt < cutoff }
                            deleted = saved.ips.size - remaining.size
                            saved.copy(ips = remaining)
                        } else saved
                    })
                }) return@setPositiveButton
                if (selectedOperatorId == operator.id) clearSelectionState()
                refresh()
                Toast.makeText(
                    context,
                    context.getString(R.string.saved_ip_delete_older_done, deleted),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .show()
    }

    private fun chooseSelectAge(operator: SavedIpOperator) {
        val labels = arrayOf(
            context.getString(R.string.saved_ip_select_untested),
            context.getString(R.string.saved_ip_select_over_1_hour),
            context.getString(R.string.saved_ip_select_over_6_hours),
            context.getString(R.string.saved_ip_select_over_24_hours),
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_select_age_title)
            .setItems(labels) { _, which ->
                val now = System.currentTimeMillis()
                val matching = when (which) {
                    0 -> operator.ips.filter { it.status == SavedIpStatus.UNTESTED || it.lastTestedAt <= 0L }
                        .sortedBy { it.addedAt }
                    1, 2, 3 -> {
                        val hours = when (which) {
                            1 -> 1L
                            2 -> 6L
                            else -> 24L
                        }
                        val cutoff = now - hours * 60L * 60L * 1000L
                        operator.ips.filter { it.lastTestedAt > 0L && it.lastTestedAt < cutoff }
                            .sortedBy { it.lastTestedAt }
                    }
                    else -> emptyList()
                }
                selectGroupMatches(operator, matching)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectGroupMatches(operator: SavedIpOperator, matching: List<SavedIpEntry>) {
        if (matching.isEmpty()) {
            Toast.makeText(context, R.string.saved_ip_select_age_none, Toast.LENGTH_SHORT).show()
            return
        }
        selectedOperatorId = operator.id
        selectedAddresses.clear()
        val selected = matching.take(20)
        selectedAddresses.addAll(selected.map { it.address })
        refresh()
        if (matching.size > 20) {
            Toast.makeText(
                context,
                context.getString(R.string.saved_ip_select_age_overflow, matching.size),
                Toast.LENGTH_LONG,
            ).show()
        }
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
