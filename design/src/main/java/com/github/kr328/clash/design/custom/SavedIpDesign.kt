package com.github.kr328.clash.design.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
    private val onSpeedTest: (String, String, Boolean) -> Unit = { _, _, _ -> },
    private var speedAvailable: Boolean = false,
    private var speedBusy: Boolean = false,
    savedState: Bundle? = null,
) : Design<SavedIpDesign.Request>(context) {
    enum class Request { Unused }
    private val savedIpUiPreferences = context.getSharedPreferences("custom_saved_ip_ui", Context.MODE_PRIVATE)
    // CUSTOM #156: this page-only preference is read once when the page is created.
    private var showDirectSpeed = SavedIpDirectSpeedSetting.read(context)
    private var lastPersistedUiState = savedIpUiPreferences.getString(KEY_PERSISTENT_UI_STATE, null)
    private val filterState = if (savedState == null) {
        SavedIpFilterState.restorePersistent(lastPersistedUiState)
    } else {
        SavedIpFilterState.restore(savedState)
    }
    private val density = context.resources.displayMetrics.density
    private val colorText = savedIpThemeColor(context, android.R.attr.textColorPrimary, Color.BLACK)
    // Match the explicit color source used by the config-check page's Material text buttons.
    private val colorLink = savedIpThemeColor(context, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
    private val colorMuted = savedIpThemeColor(context, android.R.attr.textColorSecondary, Color.GRAY)
    private val colorSurface = savedIpThemeColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val colorDivider = savedIpThemeColor(context, android.R.attr.colorControlHighlight, 0x22000000)

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
        setPadding(savedIpDp(density, 16), savedIpDp(density, 8), savedIpDp(density, 16), savedIpDp(density, 24))
        clipToPadding = false
        addView(operatorContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    // CUSTOM.md #163: overlay only; the original ScrollView and table hierarchy remain unchanged.
    private val stickyHeaderContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(colorSurface)
        elevation = savedIpDp(density, 4).toFloat()
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
        setPadding(savedIpDp(density, 12), savedIpDp(density, 8), savedIpDp(density, 42), savedIpDp(density, 8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = savedIpDp(density, 8).toFloat()
            setColor(colorSurface)
            setStroke(savedIpDp(density, 1), colorDivider)
        }
    }
    private val searchClearButton = TextView(context).apply {
        text = "×"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(colorMuted)
        contentDescription = context.getString(R.string.saved_ip_search_clear)
        background = savedIpSelectableBackground(context)
        setOnClickListener {
            searchInput.text?.clear()
            renderRows()
        }
    }
    private val searchBox = FrameLayout(context).apply {
        visibility = View.GONE
        addView(searchInput, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, savedIpDp(density, 40)))
        addView(searchClearButton, FrameLayout.LayoutParams(savedIpDp(density, 40), savedIpDp(density, 40), Gravity.END or Gravity.CENTER_VERTICAL))
    }
    private var snapshotCache = SavedIpStore.emptySnapshot()
    private var hasLoadedSnapshot = false
    private var configuredIpSources: Map<String, Set<String>> = emptyMap()
    private var expandedOperatorId: String?
        get() = filterState.expandedOperatorId
        set(value) { filterState.expandedOperatorId = value }

    // CUSTOM (CUSTOM.md #159): speed sorting is isolated and persisted by operator ID.
    // CUSTOM #156: hide direct-speed sorting without rewriting the persisted choice.
    private fun currentSort(operatorId: String): SavedIpSort = SavedIpSort.valueOf(
        effectiveSavedIpSortName(filterState.sortFor(operatorId).name, showDirectSpeed),
    )
    private fun setCurrentSort(operatorId: String, value: SavedIpSort) = filterState.setSort(operatorId, value)

    // CUSTOM (CUSTOM.md #164): the former filter toolbar is removed; search moves into the title row.
    private val selectionActions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(savedIpDp(density, 8), savedIpDp(density, 4), savedIpDp(density, 8), savedIpDp(density, 4))
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
    private val retestButton = savedIpActionButton(context, colorLink, density, R.string.saved_ip_retest) { retestSelected() }
    private val deleteSelectedButton = savedIpActionButton(context, colorLink, density, R.string.saved_ip_delete_selected) { deleteSelected() }
    private val cancelSelectionButton = savedIpActionButton(context, colorLink, density, R.string.saved_ip_cancel_selection) { clearSelection() }
    private val selectUnreachableButton = savedIpActionButton(context, colorLink, density, R.string.saved_ip_select_unreachable) { selectUnreachable() }
    private val selectOldestButton = savedIpActionButton(context, colorLink, density, R.string.saved_ip_select_oldest_20) { selectOldest20() }
    private val copyHostsButton = savedIpActionButton(context, colorLink, density, R.string.saved_ip_copy_hosts) { copySelectedHosts() }

    private val searchIndicator = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorLink)
        }
        visibility = View.GONE
    }
    private val moreButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_baseline_more_vert)
        contentDescription = context.getString(R.string.saved_ip_show_direct_speed)
        background = savedIpSelectableBackground(context)
        setPadding(savedIpDp(density, 10), savedIpDp(density, 10), savedIpDp(density, 10), savedIpDp(density, 10))
        setOnClickListener { showSavedIpMoreMenu() }
    }
    private val searchIconButton = FrameLayout(context).apply {
        background = savedIpSelectableBackground(context)
        contentDescription = context.getString(R.string.saved_ip_search_hint)
        addView(TextView(context).apply {
            text = "🔍"
            textSize = 18f
            gravity = Gravity.CENTER
            isClickable = false
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(searchIndicator, FrameLayout.LayoutParams(savedIpDp(density, 6), savedIpDp(density, 6), Gravity.TOP or Gravity.END).apply {
            topMargin = savedIpDp(density, 7)
            marginEnd = savedIpDp(density, 7)
        })
        setOnClickListener { expandSearch() }
    }
    private val searchCloseButton = TextView(context).apply {
        text = "×"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(colorLink)
        contentDescription = context.getString(R.string.saved_ip_search_close)
        background = savedIpSelectableBackground(context)
        visibility = View.GONE
        setOnClickListener { collapseSearch() }
    }

    private val env = object : SavedIpEnv {
        override val context: Context get() = this@SavedIpDesign.context
        override val root: View get() = this@SavedIpDesign.root
        override var snapshotCache: SavedIpSnapshot
            get() = this@SavedIpDesign.snapshotCache
            set(value) { this@SavedIpDesign.snapshotCache = value }
        override var selectedOperatorId: String?
            get() = selection.selectedOperatorId
            set(value) { selection.selectedOperatorId = value }
        override val selectedAddresses: MutableSet<String> get() = selection.selectedAddresses
        override val filterState: SavedIpFilterState get() = this@SavedIpDesign.filterState
        override var expandedOperatorId: String?
            get() = this@SavedIpDesign.expandedOperatorId
            set(value) { this@SavedIpDesign.expandedOperatorId = value }
        override val canRetest: Boolean get() = this@SavedIpDesign.canRetest
        override val selectionActions: LinearLayout get() = this@SavedIpDesign.selectionActions
        override val selectedCountView: TextView get() = this@SavedIpDesign.selectedCountView
        override val retestButton: TextView get() = this@SavedIpDesign.retestButton
        override val deleteSelectedButton: TextView get() = this@SavedIpDesign.deleteSelectedButton
        override val selectUnreachableButton: TextView get() = this@SavedIpDesign.selectUnreachableButton
        override val selectOldestButton: TextView get() = this@SavedIpDesign.selectOldestButton
        override val copyHostsButton: TextView get() = this@SavedIpDesign.copyHostsButton
        override var failureDetailDialog: android.app.Dialog?
            get() = deleteUndo.failureDetailDialog
            set(value) { deleteUndo.failureDetailDialog = value }
        override val savedIpUiPreferences get() = this@SavedIpDesign.savedIpUiPreferences
        override var showDirectSpeed: Boolean
            get() = this@SavedIpDesign.showDirectSpeed
            set(value) { this@SavedIpDesign.showDirectSpeed = value }
        override val density get() = this@SavedIpDesign.density
        override val colorSurface get() = this@SavedIpDesign.colorSurface
        override val colorText get() = this@SavedIpDesign.colorText
        override val colorLink get() = this@SavedIpDesign.colorLink
        override val colorMuted get() = this@SavedIpDesign.colorMuted
        override val colorDivider get() = this@SavedIpDesign.colorDivider
        override val searchInput get() = this@SavedIpDesign.searchInput
        override val searchBox get() = this@SavedIpDesign.searchBox
        override val searchIconButton get() = this@SavedIpDesign.searchIconButton
        override val searchCloseButton get() = this@SavedIpDesign.searchCloseButton
        override val searchIndicator get() = this@SavedIpDesign.searchIndicator
        override val searchClearButton get() = this@SavedIpDesign.searchClearButton
        override val moreButton: View get() = this@SavedIpDesign.moreButton
        override val operatorContent get() = this@SavedIpDesign.operatorContent
        override val operatorList get() = this@SavedIpDesign.operatorList
        override val stickyHeaderContainer get() = this@SavedIpDesign.stickyHeaderContainer
        override val savedTableRenderers get() = renderState.savedTableRenderers
        override var configuredIpSources: Map<String, Set<String>>
            get() = this@SavedIpDesign.configuredIpSources
            set(value) { this@SavedIpDesign.configuredIpSources = value }
        override var renderedDataVersion: Long
            get() = renderState.renderedDataVersion
            set(value) { renderState.renderedDataVersion = value }
        override var renderedQuery: String?
            get() = search.renderedQuery
            set(value) { search.renderedQuery = value }
        override var visibleSavedColumns: Set<String>
            get() = filterMenus.visibleSavedColumns
            set(value) { filterMenus.visibleSavedColumns = value }
        override var speedAvailable: Boolean
            get() = this@SavedIpDesign.speedAvailable
            set(value) { this@SavedIpDesign.speedAvailable = value }
        override var speedBusy: Boolean
            get() = this@SavedIpDesign.speedBusy
            set(value) { this@SavedIpDesign.speedBusy = value }
        override val onSpeedTest get() = this@SavedIpDesign.onSpeedTest

        override fun refresh() = this@SavedIpDesign.refresh()
        override fun renderRows() = renderer.renderRows()
        override fun renderOperator(operatorId: String, showEmptyState: Boolean) = renderer.renderOperator(operatorId, showEmptyState)
        override fun savedTableFor(operator: SavedIpOperator) = renderer.savedTableFor(operator)
        override fun visibleEntries(operator: SavedIpOperator, applySort: Boolean) = filterMenus.visibleEntries(operator, applySort)
        override fun currentSort(operatorId: String) = this@SavedIpDesign.currentSort(operatorId)
        override fun setCurrentSort(operatorId: String, value: SavedIpSort) = this@SavedIpDesign.setCurrentSort(operatorId, value)
        override fun collapseSearch() = search.collapseSearch()
        override fun expandSearch() = search.expandSearch()
        override fun updateSearchUi() = search.updateSearchUi()
        override fun cancelPendingSearchRender() = search.cancelPendingSearchRender()
        override fun dismissFailureDetailDialog() = deleteUndo.dismissFailureDetailDialog()
        override fun clearSelectionState() = selection.clearSelectionState()
        override fun toggleSelection(operatorId: String, address: String) = selection.toggleSelection(operatorId, address)
        override fun copyIp(address: String) = this@SavedIpDesign.copyIp(address)
        override fun confirmDeleteUnreachable(operator: SavedIpOperator) = deleteUndo.confirmDeleteUnreachable(operator)
        override fun editOperator(operator: SavedIpOperator) = deleteUndo.editOperator(operator)
        override fun confirmDeleteOperator(operator: SavedIpOperator) = deleteUndo.confirmDeleteOperator(operator)
        override fun chooseDeleteOlder(operator: SavedIpOperator) = deleteUndo.chooseDeleteOlder(operator)
        override fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean = deleteUndo.writeSnapshot(transform)
    }
    private val search = SavedIpSearch(env)
    private val selection = SavedIpSelection(env)
    private var selectedOperatorId: String?
        get() = selection.selectedOperatorId
        set(value) { selection.selectedOperatorId = value }
    private val selectedAddresses: LinkedHashSet<String> get() = selection.selectedAddresses
    private val deleteUndo = SavedIpDeleteUndo(env)
    private val filterMenus = SavedIpFilterMenus(env)
    private val visibleSavedColumns: Set<String> get() = filterMenus.visibleSavedColumns
    private val renderState = SavedIpRenderState()
    private var renderedDataVersion: Long
        get() = renderState.renderedDataVersion
        set(value) { renderState.renderedDataVersion = value }
    private val savedTableRenderers get() = renderState.savedTableRenderers
    private val renderer = SavedIpRenderer(env, renderState, selection, filterMenus)

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
        toolbar.addView(moreButton, androidx.appcompat.widget.Toolbar.LayoutParams(savedIpDp(density, 40), savedIpDp(density, 48), Gravity.END))
        toolbar.addView(searchIconButton, androidx.appcompat.widget.Toolbar.LayoutParams(savedIpDp(density, 40), savedIpDp(density, 48), Gravity.END).apply { marginEnd = savedIpDp(density, 40) })
        toolbar.addView(searchBox, androidx.appcompat.widget.Toolbar.LayoutParams(savedIpDp(density, 180), savedIpDp(density, 40), Gravity.END).apply { marginEnd = savedIpDp(density, 80) })
        toolbar.addView(searchCloseButton, androidx.appcompat.widget.Toolbar.LayoutParams(savedIpDp(density, 40), savedIpDp(density, 48), Gravity.END).apply { marginEnd = savedIpDp(density, 40) })

        searchInput.setPadding(savedIpDp(density, 12), savedIpDp(density, 8), savedIpDp(density, 12), savedIpDp(density, 8))
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

        selectionMainActions.addView(selectedCountView, savedIpWeighted(density))
        selectionMainActions.addView(retestButton, savedIpWeighted(density, 2))
        selectionMainActions.addView(deleteSelectedButton, savedIpWeighted(density, 2))
        selectionMainActions.addView(cancelSelectionButton, savedIpWeighted(density, 2))
        selectionQuickActions.addView(selectUnreachableButton, savedIpWeighted(density, 2))
        selectionQuickActions.addView(selectOldestButton, savedIpWeighted(density, 2))
        selectionQuickActions.addView(copyHostsButton, savedIpWeighted(density, 2))
        selectionActions.addView(selectionMainActions, savedIpFullWidth())
        selectionActions.addView(selectionQuickActions, savedIpFullWidth())
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(operatorListFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        operatorList.setOnScrollChangeListener { _, _, _, _, _ -> updateStickyHeader() }
        // CUSTOM (CUSTOM.md #165): do not rebuild the table on popup window focus changes.
        // Initial storage loading is started by SavedIpActivity on Dispatchers.IO after this page is attached.
    }

    // CUSTOM.md #156 step 24: update the retest gate when an existing page is brought forward.
    fun setCanRetest(value: Boolean) {
        canRetest = value
        updateSelectionActions()
    }

    // CUSTOM.md #158: update saved download buttons from the process-wide event source.

    // CUSTOM #158: update only the active download percentage in the already-bound cell.

    // CUSTOM #158/#159: update one speed cell and move existing row views when its order changes.

    fun setSpeedState(available: Boolean, busy: Boolean) = renderer.setSpeedState(available, busy)

    fun updateSpeedProgress(operatorId: String, address: String, direct: Boolean, percent: Int?) =
        renderer.updateSpeedProgress(operatorId, address, direct, percent)

    fun updateSpeedResult(operatorId: String, address: String, direct: Boolean, speed: Double, short: Boolean) =
        renderer.updateSpeedResult(operatorId, address, direct, speed, short)

    fun setConfigIpSources(value: Map<String, Set<String>>) = renderer.setConfigIpSources(value)

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

    fun refreshIfDataChanged(): Boolean {
        if (!hasLoadedSnapshot) return false
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

    /** Apply the background-loaded first snapshot, rejecting it if a write happened meanwhile. */
    fun setInitialSnapshot(snapshot: SavedIpSnapshot, version: Long) {
        if (version != SavedIpStore.version()) {
            refresh()
        } else {
            refresh(snapshot, version)
        }
    }

    fun refresh() {
        val (snapshot, version) = SavedIpStore.readVersioned(context)
        refresh(snapshot, version)
    }

    private fun refresh(snapshot: SavedIpSnapshot, version: Long) {
        snapshotCache = snapshot
        renderedDataVersion = version
        hasLoadedSnapshot = true
        filterMenus.clearSortCaches()
        savedTableRenderers.clear()
        SavedIpStore.takeReadError()?.let { // CUSTOM.md #156 step 13
            Toast.makeText(context, context.getString(R.string.saved_ip_read_failed, it), Toast.LENGTH_LONG).show()
        }
        reconcileTableFilters(snapshotCache)
        reconcileSelection(snapshotCache)
        updateSearchUi()
        renderRows()
    }

    // CUSTOM.md #163: each expanded operator is rendered as one shared table. Search deliberately
    // expands every matching operator, preserving the existing search behavior.
    // CUSTOM (CUSTOM.md #165): each operator owns one section View. Local filter/selection
    // changes replace only that section; page-wide changes still call renderRows().

    private companion object {
        // Saved-table filterable columns: entry colo, ASN, registered country, exit country,
        // port, exit IP, target colo and target response. Values come from IpTestView's cell mapping.
        const val KEY_PERSISTENT_UI_STATE = "persistent_filter_state"
    }

    // CUSTOM (CUSTOM.md #164): one mutually exclusive status choice per operator; "全部" clears it.

    // CUSTOM.md #160 (r291): scrollable anchored popup; checkboxes stay open for multi-selection.

    // CUSTOM (CUSTOM.md #160): reuse the existing scrollable checkbox popup for each saved-table column.

    // CUSTOM (CUSTOM.md #164): reconcile each operator's selections only against that operator's values.

    // CUSTOM (CUSTOM.md #159/#164): each speed column has its own fastest / slowest menu.

    // CUSTOM #156: direct-speed visibility applies only to this saved-IP page.

    fun handleBackPressed(): Boolean {
        if (filterState.searchExpanded) {
            collapseSearch()
            return true
        }
        return selection.handleBackPressed()
    }

    // CUSTOM.md #156 step 4: enter / toggle selection only inside one operator group.
    // CUSTOM (CUSTOM.md #163 r330): keep active row toggles synchronous; rebuild only when
    // the checkbox column must appear on entry or disappear after the final item is cleared.

    private fun renderRows() = renderer.renderRows()
    private fun renderOperator(operatorId: String, showEmptyState: Boolean = true) = renderer.renderOperator(operatorId, showEmptyState)
    private fun savedTableFor(operator: SavedIpOperator): Pair<IpTestView, IntArray> = renderer.savedTableFor(operator)
    private fun updateStickyHeader() = renderer.updateStickyHeader()

    private fun scheduleSearchRender() = search.scheduleSearchRender()
    fun cancelPendingSearchRender() = search.cancelPendingSearchRender()
    private fun expandSearch() = search.expandSearch()
    private fun collapseSearch() = search.collapseSearch()
    private fun updateSearchUi() = search.updateSearchUi()
    private fun updateSearchIndicator() = search.updateSearchIndicator()

    private fun showSavedColumnsDialog() = filterMenus.showSavedColumnsDialog()
    private fun visibleEntries(operator: SavedIpOperator, applySort: Boolean = false): List<SavedIpEntry> =
        filterMenus.visibleEntries(operator, applySort)
    private fun dismissFilterPopups() = filterMenus.dismiss()
    private fun showTableColumnPopup(anchor: View, operator: SavedIpOperator, columnIndex: Int) =
        filterMenus.showTableColumnPopup(anchor, operator, columnIndex)
    private fun reconcileTableFilters(snapshot: SavedIpSnapshot) = filterMenus.reconcileTableFilters(snapshot)
    private fun filterSavedIpEntriesByColumns(operator: SavedIpOperator, entries: List<SavedIpEntry>) =
        filterMenus.filterSavedIpEntriesByColumns(operator, entries)
    private fun showSpeedSortMenu(anchor: View, columnName: String, operatorId: String) =
        filterMenus.showSpeedSortMenu(anchor, columnName, operatorId)
    private fun showSavedIpMoreMenu() = filterMenus.showSavedIpMoreMenu()

    private fun toggleSelection(operatorId: String, address: String): Boolean = selection.toggleSelection(operatorId, address)
    private fun reconcileSelection(snapshot: SavedIpSnapshot) = selection.reconcileSelection(snapshot)
    private fun updateSelectionActions() = selection.updateSelectionActions()
    private fun selectUnreachable() = selection.selectUnreachable()
    private fun selectOldest20() = selection.selectOldest20()
    private fun clearSelectionState() = selection.clearSelectionState()
    private fun clearSelection() = selection.clearSelection()
    private fun chooseSelectAge(operator: SavedIpOperator) = selection.chooseSelectAge(operator)
    private fun selectGroupMatches(operator: SavedIpOperator, matching: List<SavedIpEntry>) =
        selection.selectGroupMatches(operator, matching)

    private fun invalidateUndo() = deleteUndo.invalidateUndo()
    private fun showDeleteUndo(operatorId: String, entries: List<SavedIpEntry>) = deleteUndo.showDeleteUndo(operatorId, entries)
    private fun restoreDeleted(undo: SavedIpDeleteUndo.SavedIpUndo) = deleteUndo.restoreDeleted(undo)
    private fun dismissFailureDetailDialog() = deleteUndo.dismissFailureDetailDialog()
    fun disposeUndo() {
        cancelPendingSearchRender()
        deleteUndo.disposeUndo()
    }
    private fun deleteSelected() = deleteUndo.deleteSelected()
    private fun confirmDeleteOperator(operator: SavedIpOperator) = deleteUndo.confirmDeleteOperator(operator)
    private fun chooseDeleteOlder(operator: SavedIpOperator) = deleteUndo.chooseDeleteOlder(operator)
    private fun confirmDeleteOlder(operator: SavedIpOperator, days: Int, cutoff: Long) = deleteUndo.confirmDeleteOlder(operator, days, cutoff)
    private fun confirmDeleteUnreachable(operator: SavedIpOperator) = deleteUndo.confirmDeleteUnreachable(operator)
    private fun confirmDeleteIp(operatorId: String, address: String) = deleteUndo.confirmDeleteIp(operatorId, address)
    private fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean = deleteUndo.writeSnapshot(transform)
    private fun editOperator(operator: SavedIpOperator) = deleteUndo.editOperator(operator)
    private fun showOperatorNameDialog(existing: SavedIpOperator?) = deleteUndo.showOperatorNameDialog(existing)

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

    // CUSTOM.md #156 step 8: centralize error handling for every saved-list mutation.

    private fun copySelectedHosts() {
        val operatorId = selectedOperatorId ?: return
        if (selectedAddresses.isEmpty()) return
        val operator = snapshotCache.operators.firstOrNull { it.id == operatorId } ?: return
        val addresses = sortSavedIpEntries(SavedIpStore.sortEntries(operator.ips), currentSort(operatorId))
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

}
