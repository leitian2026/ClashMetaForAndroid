package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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
    private val savedIpUiPreferences = context.getSharedPreferences("custom_saved_ip_ui", Context.MODE_PRIVATE)
    private var lastPersistedUiState = savedIpUiPreferences.getString(KEY_PERSISTENT_UI_STATE, null)
    private val filterState = if (savedState == null) {
        SavedIpFilterState.restorePersistent(lastPersistedUiState)
    } else {
        SavedIpFilterState.restore(savedState)
    }
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

    // CUSTOM.md #163: render one complete table per expanded operator (maximum 300 newly saved IPs).
    private val operatorContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val operatorList = ScrollView(context).apply {
        setPadding(dp(16), dp(8), dp(16), dp(24))
        clipToPadding = false
        addView(operatorContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    // CUSTOM.md #163: overlay only; the original ScrollView and table hierarchy remain unchanged.
    private val stickyHeaderContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(colorSurface)
        elevation = dp(4).toFloat()
    }
    private val operatorListFrame = FrameLayout(context).apply {
        addView(operatorList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(stickyHeaderContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
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
        setOnClickListener {
            searchInput.text?.clear()
            renderRows()
        }
    }
    private val searchBox = FrameLayout(context).apply {
        visibility = View.GONE
        addView(searchInput, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        addView(searchClearButton, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
    }
    private var snapshotCache = SavedIpStore.emptySnapshot()
    private var renderedDataVersion = -1L
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearchRender: Runnable? = null
    private var renderedQuery: String? = null
    private var configuredIpSources: Map<String, Set<String>> = emptyMap()
    private var expandedOperatorId: String?
        get() = filterState.expandedOperatorId
        set(value) { filterState.expandedOperatorId = value }

    // CUSTOM (CUSTOM.md #164): status and table filters are isolated per operator.
    private var currentSort: SavedIpSort
        get() = filterState.sort
        set(value) { filterState.sort = value }
    private var selectedOperatorId: String? = null
    private val selectedAddresses = LinkedHashSet<String>()
    private data class SavedIpUndo(val operatorId: String, val entries: List<SavedIpEntry>)
    private var pendingUndo: SavedIpUndo? = null
    private var undoSnackbar: Snackbar? = null
    private var failureDetailDialog: android.app.Dialog? = null
    private val savedTableColumnOptions = IpTestView(context, direct = false, target = false, savedTable = true).savedTableColumnOptions()
    private val allSavedColumnNames = savedTableColumnOptions.mapTo(LinkedHashSet()) { it.first }
    private var visibleSavedColumns: Set<String> = savedIpUiPreferences.getStringSet(KEY_VISIBLE_SAVED_COLUMNS, null)
        ?.filter { it in allSavedColumnNames }?.toSet() ?: allSavedColumnNames
    private val savedTableRenderers = HashMap<String, Pair<IpTestView, IntArray>>()
    // Must be initialized before init{} calls refresh() -> renderRows().
    private val operatorSections = HashMap<String, LinearLayout>()
    private val stickyHeaderCopies = HashMap<String, View>()
    private val stickyHeaderTables = HashMap<String, View>()
    private val stickyHeaderSourceScrolls = HashMap<String, HorizontalScrollView>()
    private val stickyHeaderCopyScrolls = HashMap<String, HorizontalScrollView>()
    private var stickyHeaderOperatorId: String? = null
    private var savedTableScrollX = 0

    // CUSTOM (CUSTOM.md #164): the former filter toolbar is removed; search moves into the title row.
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
    private val copyHostsButton = actionButton(R.string.saved_ip_copy_hosts) { copySelectedHosts() }

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
    private var tableFilterPopup: PopupWindow? = null
    private var activeTableFilterOperatorId: String? = null
    private var activeTableFilterColumn: String = ""

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

        // CUSTOM (CUSTOM.md #164): keep search on the same title row at the toolbar's end.
        toolbar.addView(searchIconButton, androidx.appcompat.widget.Toolbar.LayoutParams(dp(40), dp(48), Gravity.END))
        toolbar.addView(searchBox, androidx.appcompat.widget.Toolbar.LayoutParams(dp(180), dp(40), Gravity.END).apply { marginEnd = dp(40) })
        toolbar.addView(searchCloseButton, androidx.appcompat.widget.Toolbar.LayoutParams(dp(40), dp(48), Gravity.END))

        searchInput.setPadding(dp(12), dp(8), dp(12), dp(8))
        searchInput.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchInput.setText(filterState.query)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterState.query = s?.toString().orEmpty()
                updateSearchIndicator()
                scheduleSearchRender()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
                renderRows()
                true
            } else false
        }
        updateSearchUi()

        // CUSTOM.md #156 step 12: operator creation and IP pasting are managed from the config-check save dialog.

        selectionMainActions.addView(selectedCountView, weighted())
        selectionMainActions.addView(retestButton, weighted(2))
        selectionMainActions.addView(deleteSelectedButton, weighted(2))
        selectionMainActions.addView(cancelSelectionButton, weighted(2))
        selectionQuickActions.addView(selectUnreachableButton, weighted(2))
        selectionQuickActions.addView(selectOldestButton, weighted(2))
        selectionQuickActions.addView(copyHostsButton, weighted(2))
        selectionActions.addView(selectionMainActions, fullWidth())
        selectionActions.addView(selectionQuickActions, fullWidth())
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(operatorListFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        operatorList.setOnScrollChangeListener { _, _, _, _, _ -> updateStickyHeader() }
        // CUSTOM (CUSTOM.md #165): do not rebuild the table on popup window focus changes.
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

    fun savePersistentUiState() {
        filterState.query = searchInput.text?.toString().orEmpty()
        val current = filterState.persistentJson()
        if (current != lastPersistedUiState) {
            savedIpUiPreferences.edit().putString(KEY_PERSISTENT_UI_STATE, current).apply()
            lastPersistedUiState = current
        }
    }

    fun setConfigIpSources(value: Map<String, Set<String>>) {
        if (configuredIpSources == value) return
        val changedIps = (configuredIpSources.keys + value.keys).filterTo(HashSet()) { ip ->
            configuredIpSources[ip] != value[ip]
        }
        configuredIpSources = value
        if (changedIps.isEmpty()) return
        val affectedOperatorIds = snapshotCache.operators
            .filter { operator -> operator.ips.any { it.address in changedIps } }
            .map { it.id }
        affectedOperatorIds.forEach { operatorId -> renderOperator(operatorId) }
    }

    fun refreshIfDataChanged(): Boolean {
        if (SavedIpStore.version() != renderedDataVersion) {
            refresh()
            return true
        }
        if (searchInput.text?.toString().orEmpty() != renderedQuery) {
            renderRows()
            return true
        }
        return false
    }

    fun refresh() {
        snapshotCache = SavedIpStore.read(context)
        renderedDataVersion = SavedIpStore.version()
        savedTableRenderers.clear()
        SavedIpStore.takeReadError()?.let { // CUSTOM.md #156 step 13
            Toast.makeText(context, context.getString(R.string.saved_ip_read_failed, it), Toast.LENGTH_LONG).show()
        }
        reconcileTableFilters(snapshotCache)
        reconcileSelection(snapshotCache)
        updateSearchUi()
        renderRows()
    }

    private fun scheduleSearchRender() {
        cancelPendingSearchRender()
        val task = Runnable {
            pendingSearchRender = null
            renderRows()
        }
        pendingSearchRender = task
        searchHandler.postDelayed(task, 300L)
    }

    fun cancelPendingSearchRender() {
        pendingSearchRender?.let(searchHandler::removeCallbacks)
        pendingSearchRender = null
    }

    // CUSTOM.md #163: each expanded operator is rendered as one shared table. Search deliberately
    // expands every matching operator, preserving the existing search behavior.
    // CUSTOM (CUSTOM.md #165): each operator owns one section View. Local filter/selection
    // changes replace only that section; page-wide changes still call renderRows().
    private fun renderRows() {
        cancelPendingSearchRender()
        dismissFailureDetailDialog()
        renderedQuery = searchInput.text?.toString().orEmpty()
        operatorContent.removeAllViews()
        operatorSections.clear()
        stickyHeaderCopies.clear()
        stickyHeaderTables.clear()
        stickyHeaderSourceScrolls.clear()
        stickyHeaderCopyScrolls.clear()
        stickyHeaderContainer.removeAllViews()
        stickyHeaderContainer.visibility = View.GONE
        stickyHeaderOperatorId = null
        snapshotCache.operators.forEach { renderOperator(it.id, showEmptyState = false) }
        if (operatorContent.childCount == 0) addEmptyState()
        updateStickyHeader()
        operatorList.post { updateStickyHeader() }
    }

    private fun addEmptyState() {
        operatorContent.addView(TextView(context).apply {
            tag = EMPTY_STATE_TAG
            setText(if (snapshotCache.operators.none { it.ips.isNotEmpty() }) R.string.saved_ip_empty else R.string.saved_ip_filter_no_match)
            textSize = 14f
            setTextColor(colorMuted)
            setPadding(0, dp(12), 0, dp(16))
        }, fullWidth())
    }

    private fun renderOperator(operatorId: String, showEmptyState: Boolean = true) {
        val operator = snapshotCache.operators.firstOrNull { it.id == operatorId } ?: return
        dismissFailureDetailDialog()
        stickyHeaderCopies.remove(operatorId)
        stickyHeaderTables.remove(operatorId)
        stickyHeaderSourceScrolls.remove(operatorId)
        stickyHeaderCopyScrolls.remove(operatorId)
        if (stickyHeaderOperatorId == operatorId) {
            stickyHeaderContainer.removeAllViews()
            stickyHeaderContainer.visibility = View.GONE
            stickyHeaderOperatorId = null
        }
        operatorSections.remove(operatorId)?.let { operatorContent.removeView(it) }
        operatorContent.findViewWithTag<View>(EMPTY_STATE_TAG)?.let { operatorContent.removeView(it) }

        val query = searchInput.text?.toString()?.trim().orEmpty()
        // Keep status filters, column filters and search active during multi-selection.
        // Selection changes only the row markers; it must not broaden the visible IP list.
        val filtered = visibleEntries(operator)
        val entries = if (query.isEmpty()) filtered else {
            val renderer = savedTableFor(operator).first
            filtered.filter { entry ->
                entry.address.contains(query, ignoreCase = true) ||
                    SAVED_SEARCH_COLUMNS.any { column ->
                        column in visibleSavedColumns &&
                            renderer.savedTableFilterValue(entry, column).contains(query, ignoreCase = true)
                    }
            }
        }
        val hasActiveFilters = filterState.selectedStatuses.containsKey(operator.id) ||
            filterState.selectedTableFilters.containsKey(operator.id)
        if (entries.isNotEmpty() || (operator.ips.isNotEmpty() && hasActiveFilters)) {
            val expanded = query.isNotEmpty() || expandedOperatorId == operator.id || selectedOperatorId == operator.id
            val section = LinearLayout(context).apply {
                tag = operator.id
                orientation = LinearLayout.VERTICAL
            }
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(8))
            }
            bindOperatorRow(group, operator, entries, expanded)
            section.addView(group, fullWidth())
            if (expanded) {
                if (entries.isNotEmpty()) {
                    bindSavedTable(section, operator, entries)
                } else {
                    section.addView(TextView(context).apply {
                        setText(R.string.saved_ip_filter_no_match)
                        textSize = 13f
                        setTextColor(colorMuted)
                        setPadding(dp(48), dp(8), 0, dp(12))
                    }, fullWidth())
                }
            }
            val targetOrder = snapshotCache.operators.indexOfFirst { it.id == operatorId }
            var insertAt = operatorContent.childCount
            for (i in 0 until operatorContent.childCount) {
                val childId = operatorContent.getChildAt(i).tag as? String ?: continue
                val childOrder = snapshotCache.operators.indexOfFirst { it.id == childId }
                if (childOrder > targetOrder) { insertAt = i; break }
            }
            operatorContent.addView(section, insertAt, fullWidth())
            operatorSections[operatorId] = section
        }
        if (showEmptyState && operatorContent.childCount == 0) addEmptyState()
        updateStickyHeader()
        operatorList.post { updateStickyHeader() }
    }

    private companion object {
        val EMPTY_STATE_TAG = Any()
        // Saved-table filterable columns: entry colo, ASN, registered country, exit country,
        // port, exit IP, target colo and target response. Values come from IpTestView's cell mapping.
        const val KEY_VISIBLE_SAVED_COLUMNS = "visible_columns"
        const val KEY_PERSISTENT_UI_STATE = "persistent_filter_state"
        val SAVED_TABLE_TAG = Any()
        val SAVED_SEARCH_COLUMNS = arrayOf("COLO", "ASN", "GEO", "EXIT", "PORTS", "EXIT_IP", "TCOLO", "TARGET")
    }

    private fun bindOperatorRow(
        container: LinearLayout,
        operator: SavedIpOperator,
        entries: List<SavedIpEntry>,
        expanded: Boolean,
    ) {
        val reachable = operator.ips.count { it.status == SavedIpStatus.REACHABLE }
        val unreachable = operator.ips.count { it.status == SavedIpStatus.UNREACHABLE }
        val untested = operator.ips.count { it.status == SavedIpStatus.UNTESTED }
        val allUnreachable = operator.ips.any { it.status == SavedIpStatus.UNREACHABLE }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        val arrow = TextView(context).apply {
            text = if (expanded) "▾" else "▸"
            textSize = 18f
            setTextColor(colorLink)
            gravity = Gravity.CENTER
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(context).apply {
            text = context.getString(R.string.saved_ip_group_title, displayName(operator), entries.size)
            textSize = 15f
            setTextColor(colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fastest = operator.ips.asSequence()
            .filter { it.status == SavedIpStatus.REACHABLE && it.avg > 0 }
            .minByOrNull { it.avg }
        if (fastest != null) {
            title.addView(TextView(context).apply {
                text = "最快 ${fastest.address}　${fastest.avg} ms"
                textSize = 11f
                setTextColor(colorMuted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(150)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val statusTags = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusTags.addView(groupStatusTag(operator, null, "全部", operator.ips.size, colorLink))
        statusTags.addView(groupStatusTag(operator, SavedIpStatus.REACHABLE, context.getString(R.string.saved_ip_status_reachable), reachable, Color.rgb(46, 125, 50)))
        statusTags.addView(groupStatusTag(operator, SavedIpStatus.UNREACHABLE, context.getString(R.string.saved_ip_status_unreachable), unreachable, Color.rgb(198, 40, 40)))
        statusTags.addView(groupStatusTag(operator, SavedIpStatus.UNTESTED, context.getString(R.string.saved_ip_status_untested), untested, Color.rgb(239, 143, 0)))
        title.addView(statusTags, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                    menu.add(0, 6, 5, R.string.saved_ip_show_columns)
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
                            6 -> showSavedColumnsDialog()
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

    // CUSTOM (CUSTOM.md #164): one mutually exclusive status choice per operator; "全部" clears it.
    private fun groupStatusTag(
        operator: SavedIpOperator,
        status: SavedIpStatus?,
        label: String,
        count: Int,
        color: Int,
    ): TextView = TextView(context).apply {
        text = "$label $count"
        textSize = 12f
        val selectedStatuses = filterState.selectedStatuses[operator.id].orEmpty()
        val selected = if (status == null) selectedStatuses.isEmpty() else selectedStatuses.singleOrNull() == status
        setTextColor(if (selected) Color.WHITE else color)
        setPadding(dp(7), dp(4), dp(7), dp(4))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(if (selected) color else Color.argb(24, Color.red(color), Color.green(color), Color.blue(color)))
        }
        setOnClickListener {
            if (status == null) {
                filterState.selectedStatuses.remove(operator.id)
            } else {
                filterState.selectedStatuses[operator.id] = linkedSetOf(status)
            }
            val previousSelection = selectedOperatorId
            clearSelectionState()
            renderOperator(operator.id)
            if (previousSelection != null && previousSelection != operator.id) renderOperator(previousSelection)
        }
    }

    private fun savedTableFor(operator: SavedIpOperator): Pair<IpTestView, IntArray> =
        savedTableRenderers.getOrPut(operator.id) {
            val renderer = IpTestView(
                context, direct = false, target = false, savedTable = true,
                visibleSavedColumns = visibleSavedColumns,
            )
            val width = (operatorList.width - operatorList.paddingLeft - operatorList.paddingRight)
                .takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels - dp(32))
            val widths = renderer.savedTableWidths(
                width,
                operator.ips.map { it.result ?: com.github.kr328.clash.core.model.IpTestRow(ip = it.address, ok = true) },
                operator.colos,
                operator.coloCountries,
            )
            renderer to widths
        }

    private fun bindSavedTable(container: LinearLayout, operator: SavedIpOperator, entries: List<SavedIpEntry>) {
        val (renderer, widths) = savedTableFor(operator)
        val selectedFilters = filterState.selectedTableFilters[operator.id].orEmpty()
        // CUSTOM.md #166: calculate from the complete operator list, before UI filters are applied.
        val recommendedIps = IpRecommend.forSaved(operator.ips, System.currentTimeMillis())
        val speedSortLabel = when (currentSort) {
            SavedIpSort.DEFAULT -> ""
            SavedIpSort.SPEED_ASCENDING -> "按最快"
            SavedIpSort.SPEED_DESCENDING -> "按最慢"
        }
        val onHeaderClick: (Int, View) -> Unit = { columnIndex, anchor ->
            if (columnIndex == renderer.savedSpeedColumnIndex()) showSpeedSortMenu(anchor)
            else showTableColumnPopup(anchor, operator, columnIndex)
        }
        fun makeTable(rows: List<SavedIpEntry>): View = renderer.savedTableView(
            entries = rows,
            configIpSources = configuredIpSources,
            widths = widths,
            scrollX = savedTableScrollX,
            onScroll = { savedTableScrollX = it },
            isSelectionMode = { selectedOperatorId == operator.id },
            isSelected = { address -> selectedOperatorId == operator.id && address in selectedAddresses },
            onCopy = ::copyIp,
            onDetail = { title, detail ->
                MaterialAlertDialogBuilder(context).setTitle(title).setMessage(detail)
                    .setPositiveButton(R.string.close, null).show()
            },
            onFailureDetail = { title, detail ->
                val message = TextView(context).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(colorText)
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                val scroll = ScrollView(context).apply {
                    isVerticalScrollBarEnabled = true
                    addView(message, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                dismissFailureDetailDialog()
                val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(
                    scroll,
                    dp(12),
                    dp(8),
                    dp(12),
                    dp(8),
                ).setPositiveButton(R.string.close, null).create()
                failureDetailDialog = dialog
                dialog.setOnDismissListener {
                    if (failureDetailDialog === dialog) failureDetailDialog = null
                }
                dialog.show()
            },
            onLongPress = { address -> toggleSelection(operator.id, address) },
            selectedFilters = selectedFilters,
            speedSortLabel = speedSortLabel,
            onHeaderClick = onHeaderClick,
            recommendedIps = recommendedIps,
        )

        val table = makeTable(entries).apply { tag = SAVED_TABLE_TAG }
        stickyHeaderTables[operator.id] = table
        container.addView(table, fullWidth())

        // One header-only twin per rendered operator. It uses the same renderer, widths and click callbacks.
        val stickyCopy = makeTable(emptyList())
        stickyHeaderCopies[operator.id] = stickyCopy
        val sourceScroll = findHorizontalScroll(table)
        val copyScroll = findHorizontalScroll(stickyCopy)
        if (sourceScroll != null && copyScroll != null) {
            var syncing = false
            sourceScroll.setOnScrollChangeListener { _, x, _, _, _ ->
                savedTableScrollX = x
                if (!syncing && copyScroll.scrollX != x) {
                    syncing = true
                    copyScroll.scrollTo(x, 0)
                    syncing = false
                }
            }
            copyScroll.setOnScrollChangeListener { _, x, _, _, _ ->
                savedTableScrollX = x
                if (!syncing && sourceScroll.scrollX != x) {
                    syncing = true
                    sourceScroll.scrollTo(x, 0)
                    syncing = false
                }
            }
            stickyHeaderSourceScrolls[operator.id] = sourceScroll
            stickyHeaderCopyScrolls[operator.id] = copyScroll
        }
    }

    private fun findHorizontalScroll(view: View): HorizontalScrollView? {
        if (view is HorizontalScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findHorizontalScroll(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun updateStickyHeader() {
        val viewportLocation = IntArray(2)
        operatorList.getLocationOnScreen(viewportLocation)
        val viewportTop = viewportLocation[1]
        var activeOperatorId: String? = null
        for (operator in snapshotCache.operators) {
            // Only expanded operators have a table entry; use the cached reference instead of
            // traversing the section's view tree on every vertical scroll event.
            val table = stickyHeaderTables[operator.id] ?: continue
            if (table.height <= 0) continue
            val tableLocation = IntArray(2)
            table.getLocationOnScreen(tableLocation)
            if (tableLocation[1] < viewportTop && tableLocation[1] + table.height > viewportTop) {
                activeOperatorId = operator.id
                break
            }
        }

        if (activeOperatorId == null) {
            stickyHeaderContainer.visibility = View.GONE
            stickyHeaderOperatorId = null
            return
        }

        val copy = stickyHeaderCopies[activeOperatorId] ?: return
        if (stickyHeaderOperatorId != activeOperatorId || stickyHeaderContainer.childCount == 0) {
            stickyHeaderContainer.removeAllViews()
            (copy.parent as? ViewGroup)?.removeView(copy)
            stickyHeaderContainer.addView(copy, fullWidth())
            stickyHeaderOperatorId = activeOperatorId
        }
        stickyHeaderContainer.visibility = View.VISIBLE
        val sourceScroll = stickyHeaderSourceScrolls[activeOperatorId]
        val copyScroll = stickyHeaderCopyScrolls[activeOperatorId]
        if (sourceScroll != null && copyScroll != null && copyScroll.scrollX != sourceScroll.scrollX) {
            copyScroll.scrollTo(sourceScroll.scrollX, 0)
        }
    }

    private fun showSavedColumnsDialog() {
        val selected = visibleSavedColumns.toMutableSet()
        val labels = savedTableColumnOptions.map { it.second }.toTypedArray()
        val checked = BooleanArray(savedTableColumnOptions.size) { savedTableColumnOptions[it].first in selected }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.saved_ip_show_columns_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val columnName = savedTableColumnOptions[which].first
                if (isChecked) selected.add(columnName) else selected.remove(columnName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val next = selected.intersect(allSavedColumnNames)
                visibleSavedColumns = next
                savedIpUiPreferences.edit()
                    .putStringSet(KEY_VISIBLE_SAVED_COLUMNS, HashSet(next))
                    .apply()
                filterState.selectedTableFilters.keys.toList().forEach { operatorId ->
                    val filters = filterState.selectedTableFilters[operatorId] ?: return@forEach
                    filters.keys.retainAll(next)
                    if (filters.isEmpty()) filterState.selectedTableFilters.remove(operatorId)
                }
                if ("SPEED" !in next) currentSort = SavedIpSort.DEFAULT
                savedTableRenderers.clear()
                refresh()
            }
            .show()
    }

    private fun visibleEntries(operator: SavedIpOperator): List<SavedIpEntry> {
        val selectedStatuses = filterState.selectedStatuses[operator.id].orEmpty()
        val filtered = operator.ips.filter { entry -> selectedStatuses.isEmpty() || entry.status in selectedStatuses }
        val tableFiltered = filterSavedIpEntriesByColumns(operator, filtered)
        val sorted = sortSavedIpEntries(SavedIpStore.sortEntries(tableFiltered), currentSort)
        // CUSTOM (CUSTOM.md #156): pin recommended and configured IPs above the remaining rows,
        // while preserving the active sort order within both groups.
        val recommendedIps = IpRecommend.forSaved(operator.ips, System.currentTimeMillis())
        return sorted.sortedBy { entry ->
            entry.address !in recommendedIps && entry.address !in configuredIpSources
        }
    }

    // CUSTOM.md #160 (r291): scrollable anchored popup; checkboxes stay open for multi-selection.
    private fun dismissFilterPopups() {
        tableFilterPopup?.dismiss()
        tableFilterPopup = null
    }

    // CUSTOM (CUSTOM.md #160): reuse the existing scrollable checkbox popup for each saved-table column.
    private fun showTableColumnPopup(anchor: View, operator: SavedIpOperator, columnIndex: Int) {
        if (filterState.searchExpanded) collapseSearch()
        val renderer = savedTableFor(operator).first
        val columnName = renderer.savedTableColumnName(columnIndex)
        if (columnName.isEmpty()) return
        if (tableFilterPopup?.isShowing == true && activeTableFilterOperatorId == operator.id && activeTableFilterColumn == columnName) {
            tableFilterPopup?.dismiss(); tableFilterPopup = null; return
        }
        dismissFilterPopups()
        val options = renderer.savedTableFilterOptions(operator.ips, columnName)
        if (options.isEmpty()) return
        val operatorFilters = filterState.selectedTableFilters.getOrPut(operator.id) { LinkedHashMap() }
        val selected = operatorFilters.getOrPut(columnName) { LinkedHashSet() }
        var selectionChanged = false
        var selectionClearedOperatorId: String? = null
        val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        options.forEach { value ->
            choices.addView(CheckBox(context).apply {
                text = value; textSize = 14f; isChecked = value in selected; minHeight = dp(44)
                setOnCheckedChangeListener { _, checked ->
                    selectionChanged = true
                    if (checked) selected.add(value) else selected.remove(value)
                    if (selected.isEmpty()) operatorFilters.remove(columnName)
                    if (operatorFilters.isEmpty()) filterState.selectedTableFilters.remove(operator.id)
                    // CUSTOM (CUSTOM.md #165): defer every affected operator rebuild until the popup closes.
                    if (selectedOperatorId != null) selectionClearedOperatorId = selectedOperatorId
                    clearSelectionState(); updateSearchUi()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = true
            addView(choices, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popupWidth = maxOf(anchor.width, dp(220)).coerceAtMost(context.resources.displayMetrics.widthPixels - dp(24))
        val popupHeight = minOf(dp(280), dp(48 * options.size.coerceAtLeast(1)))
        val popup = PopupWindow(scroll, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(colorSurface)); elevation = dp(8).toFloat(); isOutsideTouchable = true
            setOnDismissListener {
                if (selected.isEmpty()) {
                    operatorFilters.remove(columnName)
                    if (operatorFilters.isEmpty()) filterState.selectedTableFilters.remove(operator.id)
                }
                if (tableFilterPopup === this) tableFilterPopup = null
                // Tapping outside without changing any checkbox must not rebuild the whole list.
                if (selectionChanged) {
                    renderOperator(operator.id)
                    selectionClearedOperatorId?.takeIf { it != operator.id }?.let { renderOperator(it) }
                }
            }
        }
        tableFilterPopup = popup; activeTableFilterOperatorId = operator.id; activeTableFilterColumn = columnName
        popup.showAsDropDown(anchor)
    }

    // CUSTOM (CUSTOM.md #164): reconcile each operator's selections only against that operator's values.
    private fun reconcileTableFilters(snapshot: SavedIpSnapshot) {
        val operatorIds = filterState.selectedTableFilters.keys.toList()
        for (operatorId in operatorIds) {
            val operator = snapshot.operators.firstOrNull { it.id == operatorId }
            val filters = filterState.selectedTableFilters[operatorId] ?: continue
            if (operator == null) {
                filterState.selectedTableFilters.remove(operatorId)
                continue
            }
            val renderer = savedTableFor(operator).first
            for (columnName in filters.keys.toList()) {
                filters[columnName]?.retainAll(renderer.savedTableFilterOptions(operator.ips, columnName).toSet())
                if (filters[columnName].isNullOrEmpty()) filters.remove(columnName)
            }
            if (filters.isEmpty()) filterState.selectedTableFilters.remove(operatorId)
        }
        val validIds = snapshot.operators.mapTo(HashSet()) { it.id }
        filterState.selectedStatuses.keys.retainAll(validIds)
    }

    private fun filterSavedIpEntriesByColumns(operator: SavedIpOperator, entries: List<SavedIpEntry>): List<SavedIpEntry> {
        val filters = filterState.selectedTableFilters[operator.id].orEmpty()
        if (filters.isEmpty()) return entries
        val renderer = savedTableFor(operator).first
        return entries.filter { entry -> filters.all { (columnName, values) ->
            values.isEmpty() || renderer.savedTableFilterValue(entry, columnName) in values
        } }
    }

    // CUSTOM (CUSTOM.md #164): only speed is sortable; selecting the active direction clears it.
    private fun showSpeedSortMenu(anchor: View) {
        if (filterState.searchExpanded) collapseSearch()
        dismissFilterPopups()
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, "按最快").isCheckable = true
            menu.add(0, 2, 1, "按最慢").isCheckable = true
            menu.setGroupCheckable(0, true, true)
            when (currentSort) {
                SavedIpSort.SPEED_ASCENDING -> menu.findItem(1)?.isChecked = true
                SavedIpSort.SPEED_DESCENDING -> menu.findItem(2)?.isChecked = true
                SavedIpSort.DEFAULT -> Unit
            }
            setOnMenuItemClickListener { item ->
                val chosen = if (item.itemId == 1) SavedIpSort.SPEED_ASCENDING else SavedIpSort.SPEED_DESCENDING
                currentSort = if (currentSort == chosen) SavedIpSort.DEFAULT else chosen
                renderRows()
                true
            }
            show()
        }
    }

    private fun expandSearch() {
        filterState.searchExpanded = true
        updateSearchUi()
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
        updateSearchUi()
        renderRows()
    }

    private fun updateSearchUi() {
        val expanded = filterState.searchExpanded
        searchIconButton.visibility = if (expanded) View.GONE else View.VISIBLE
        searchBox.visibility = if (expanded) View.VISIBLE else View.GONE
        searchCloseButton.visibility = if (expanded) View.VISIBLE else View.GONE
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

    // CUSTOM.md #156 step 4: enter / toggle selection only inside one operator group.
    // CUSTOM (CUSTOM.md #163 r330): keep active row toggles synchronous; rebuild only when
    // the checkbox column must appear on entry or disappear after the final item is cleared.
    private fun toggleSelection(operatorId: String, address: String): Boolean {
        val currentOperator = selectedOperatorId
        if (currentOperator != null && currentOperator != operatorId) return false
        val enteringSelectionMode = currentOperator == null
        if (enteringSelectionMode) {
            selectedOperatorId = operatorId
            expandedOperatorId = operatorId
        }

        if (address in selectedAddresses) {
            selectedAddresses.remove(address)
        } else {
            if (selectedAddresses.size >= 20) {
                Toast.makeText(context, R.string.saved_ip_max_20, Toast.LENGTH_SHORT).show()
                return false
            }
            selectedAddresses.add(address)
        }

        val leavingSelectionMode = selectedAddresses.isEmpty()
        if (leavingSelectionMode) selectedOperatorId = null
        updateSelectionActions()
        if (enteringSelectionMode || leavingSelectionMode) renderOperator(operatorId)
        return address in selectedAddresses
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
        copyHostsButton.isEnabled = selectedAddresses.isNotEmpty()
        copyHostsButton.alpha = if (copyHostsButton.isEnabled) 1f else 0.35f
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
        updateSelectionActions()
        renderOperator(operatorId)
    }

    private fun clearSelectionState() {
        selectedOperatorId = null
        selectedAddresses.clear()
        updateSelectionActions()
    }

    private fun clearSelection() {
        val operatorId = selectedOperatorId
        clearSelectionState()
        if (operatorId != null) renderOperator(operatorId)
    }

    private fun invalidateUndo() {
        pendingUndo = null
        undoSnackbar?.dismiss()
        undoSnackbar = null
    }

    private fun showDeleteUndo(operatorId: String, entries: List<SavedIpEntry>) {
        if (entries.isEmpty()) return
        invalidateUndo()
        val undo = SavedIpUndo(operatorId, entries.toList())
        pendingUndo = undo
        val snackbar = Snackbar.make(
            root,
            context.getString(R.string.saved_ip_deleted_undo, entries.size),
            Snackbar.LENGTH_LONG,
        ).setAction(R.string.saved_ip_undo) {
            restoreDeleted(undo)
        }
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (pendingUndo === undo) pendingUndo = null
                if (undoSnackbar === transientBottomBar) undoSnackbar = null
            }
        })
        undoSnackbar = snackbar
        snackbar.show()
    }

    private fun restoreDeleted(undo: SavedIpUndo) {
        if (pendingUndo !== undo) return
        pendingUndo = null
        undoSnackbar = null
        val exists = snapshotCache.operators.any { it.id == undo.operatorId }
        if (!exists) return
        if (!writeSnapshot { snapshot ->
            snapshot.copy(operators = snapshot.operators.map { operator ->
                if (operator.id != undo.operatorId) operator else {
                    val addresses = operator.ips.mapTo(HashSet()) { it.address }
                    val restored = undo.entries.filterNot { it.address in addresses }
                    operator.copy(ips = operator.ips + restored)
                }
            })
        }) return
        refresh()
    }

    private fun dismissFailureDetailDialog() {
        failureDetailDialog?.dismiss()
        failureDetailDialog = null
    }

    fun disposeUndo() {
        cancelPendingSearchRender()
        dismissFailureDetailDialog()
        invalidateUndo()
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
                var deletedEntries = emptyList<SavedIpEntry>()
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { operator ->
                        if (operator.id == operatorId) {
                            deletedEntries = operator.ips.filter { it.address in addresses }
                            operator.copy(ips = operator.ips.filterNot { it.address in addresses })
                        } else operator
                    })
                }) return@setPositiveButton
                clearSelectionState()
                refresh()
                showDeleteUndo(operatorId, deletedEntries)
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
        val operator = snapshotCache.operators.firstOrNull { it.id == operatorId } ?: return
        val selectedEntries = operator.ips.filter { it.address in addresses }
        // Estimates follow iptest_probe.go: direct = 10 TLS attempts + one HEAD + five extra
        // default-port handshakes (443 is already covered by the ten attempts); node modes =
        // 10 main probes + country/ASN lookups + one entry-node handshake and one HEAD.
        // ECH lookup is domain-cached; optional WS/H3/MTU extensions are not included.
        val estimatedRequests = selectedEntries.sumOf { entry ->
            val perEntry: Int = if (entry.testDirect) 16 else 15
            perEntry
        }
        if (addresses.size >= 10) {
            MaterialAlertDialogBuilder(context)
                .setMessage(context.getString(R.string.saved_ip_retest_many_confirm, addresses.size, estimatedRequests))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.saved_ip_retest_continue) { _, _ ->
                    startRetest(operatorId, addresses)
                }
                .show()
            return
        }
        startRetest(operatorId, addresses)
    }

    private fun startRetest(operatorId: String, addresses: List<String>) {
        // CUSTOM.md #156: clear the old multi-selection before handing off to ConfigCheckActivity.
        // SavedIpActivity stays on the back stack while the test page is open; retaining this selection
        // makes the next visit look as though the previous batch is still active.
        clearSelectionState()
        renderOperator(operatorId)
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
                var deletedEntries = emptyList<SavedIpEntry>()
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { saved ->
                        if (saved.id == operator.id) {
                            deletedEntries = saved.ips.filter { it.lastTestedAt > 0L && it.lastTestedAt < cutoff }
                            saved.copy(ips = saved.ips.filterNot { it.lastTestedAt > 0L && it.lastTestedAt < cutoff })
                        } else saved
                    })
                }) return@setPositiveButton
                if (selectedOperatorId == operator.id) clearSelectionState()
                refresh()
                showDeleteUndo(operator.id, deletedEntries)
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
        expandedOperatorId = operator.id
        selectedAddresses.clear()
        val selected = matching.take(20)
        selectedAddresses.addAll(selected.map { it.address })
        updateSelectionActions()
        renderOperator(operator.id)
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
                var deletedEntries = emptyList<SavedIpEntry>()
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { current ->
                        if (current.id == operator.id) {
                            deletedEntries = current.ips.filter { it.status == SavedIpStatus.UNREACHABLE }
                            current.copy(ips = current.ips.filterNot { it.status == SavedIpStatus.UNREACHABLE })
                        } else current
                    })
                }) return@setPositiveButton
                if (selectedOperatorId == operator.id) clearSelectionState()
                refresh()
                showDeleteUndo(operator.id, deletedEntries)
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

    private fun copySelectedHosts() {
        val operatorId = selectedOperatorId ?: return
        if (selectedAddresses.isEmpty()) return
        val operator = snapshotCache.operators.firstOrNull { it.id == operatorId } ?: return
        val addresses = sortSavedIpEntries(SavedIpStore.sortEntries(operator.ips), currentSort)
            .asSequence()
            .filter { it.address in selectedAddresses }
            .take(20)
            .map { it.address }
            .toList()
        if (addresses.isEmpty()) return
        val hosts = addresses.joinToString(separator = ", ", prefix = "[", postfix = "]") { "\"$it\"" }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("hosts", hosts))
        Toast.makeText(context, context.getString(R.string.saved_ip_copied_hosts, addresses.size), Toast.LENGTH_SHORT).show()
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

    private fun actionText(text: Int, click: (View) -> Unit) = TextView(context).apply {
        setText(text)
        textSize = 13f
        setTextColor(colorLink)
        gravity = Gravity.CENTER
        minHeight = dp(40)
        background = selectableBackground()
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { click(it) }
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
