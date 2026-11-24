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
import android.widget.ImageView
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
    private var renderedDataVersion = -1L
    private var hasLoadedSnapshot = false
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearchRender: Runnable? = null
    private var renderedQuery: String? = null
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
        override val snapshotCache: SavedIpSnapshot get() = this@SavedIpDesign.snapshotCache
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
        override val searchInput get() = this@SavedIpDesign.searchInput
        override val moreButton: View get() = this@SavedIpDesign.moreButton
        override val savedTableRenderers get() = this@SavedIpDesign.savedTableRenderers
        override val configuredIpSources get() = this@SavedIpDesign.configuredIpSources
        override val renderedDataVersion get() = this@SavedIpDesign.renderedDataVersion
        override var visibleSavedColumns: Set<String>
            get() = filterMenus.visibleSavedColumns
            set(value) { filterMenus.visibleSavedColumns = value }

        override fun refresh() = this@SavedIpDesign.refresh()
        override fun renderRows() = this@SavedIpDesign.renderRows()
        override fun renderOperator(operatorId: String, showEmptyState: Boolean) = this@SavedIpDesign.renderOperator(operatorId, showEmptyState)
        override fun visibleEntries(operator: SavedIpOperator, applySort: Boolean) = this@SavedIpDesign.visibleEntries(operator, applySort)
        override fun savedTableFor(operator: SavedIpOperator) = this@SavedIpDesign.savedTableFor(operator)
        override fun currentSort(operatorId: String) = this@SavedIpDesign.currentSort(operatorId)
        override fun setCurrentSort(operatorId: String, value: SavedIpSort) = this@SavedIpDesign.setCurrentSort(operatorId, value)
        override fun collapseSearch() = this@SavedIpDesign.collapseSearch()
        override fun updateSearchUi() = this@SavedIpDesign.updateSearchUi()
        override fun clearSelectionState() = selection.clearSelectionState()
        override fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean = deleteUndo.writeSnapshot(transform)
    }
    private val selection = SavedIpSelection(env)
    private var selectedOperatorId: String?
        get() = selection.selectedOperatorId
        set(value) { selection.selectedOperatorId = value }
    private val selectedAddresses: LinkedHashSet<String>
        get() = selection.selectedAddresses
    private val deleteUndo = SavedIpDeleteUndo(env)
    private val filterMenus = SavedIpFilterMenus(env)
    private var visibleSavedColumns: Set<String>
        get() = filterMenus.visibleSavedColumns
        set(value) { filterMenus.visibleSavedColumns = value }


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
    fun setSpeedState(available: Boolean, busy: Boolean) {
        if (speedAvailable == available && speedBusy == busy) return
        speedAvailable = available
        speedBusy = busy
        // CUSTOM #158: update currently bound buttons without rebuilding the table.
        savedTableRenderers.values.forEach { (renderer, _) ->
            renderer.setSavedSpeedAvailability(speedAvailable, speedBusy)
        }
    }

    // CUSTOM #158: update only the active download percentage in the already-bound cell.
    fun updateSpeedProgress(operatorId: String, address: String, direct: Boolean, percent: Int?) {
        savedTableRenderers[operatorId]?.first?.updateSavedSpeedProgress(address, direct, percent)
    }

    // CUSTOM #158/#159: update one speed cell and move existing row views when its order changes.
    fun updateSpeedResult(operatorId: String, address: String, direct: Boolean, speed: Double, short: Boolean) {
        val (snapshot, version) = SavedIpStore.readVersioned(context)
        snapshotCache = snapshot
        renderedDataVersion = version
        val operator = snapshotCache.operators.firstOrNull { it.id == operatorId } ?: return
        filterMenus.invalidateSortCache(operatorId)
        val renderer = savedTableRenderers[operatorId]?.first ?: return
        renderer.updateSavedSpeed(address, direct, speed, short, speedAvailable, speedBusy)
        val updatedColumn = if (direct) "SPEED" else "SPEED_NODE"
        if ((operatorId == expandedOperatorId || operatorId == selectedOperatorId) &&
            currentSort(operatorId).columnName == updatedColumn
        ) {
            val query = searchInput.text?.toString()?.trim().orEmpty()
            val visible = visibleEntries(operator, applySort = true)
            val ordered = if (query.isEmpty()) visible else {
                visible.filter { entry ->
                    entry.address.contains(query, ignoreCase = true) ||
                        SAVED_SEARCH_COLUMNS.any { column ->
                            column in visibleSavedColumns &&
                                renderer.savedTableFilterValue(entry, column).contains(query, ignoreCase = true)
                        }
                }
            }
            renderer.moveSavedRow(address, ordered)
        }
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
            setPadding(0, savedIpDp(density, 12), 0, savedIpDp(density, 16))
        }, savedIpFullWidth())
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
        val expanded = query.isNotEmpty() || expandedOperatorId == operator.id || selectedOperatorId == operator.id
        // Keep status filters, column filters and search active during multi-selection.
        // Calculate speed sorting only for the explicitly expanded operator group.
        val filtered = visibleEntries(operator, applySort = operator.id == expandedOperatorId || selectedOperatorId == operator.id)
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
            val section = LinearLayout(context).apply {
                tag = operator.id
                orientation = LinearLayout.VERTICAL
            }
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, savedIpDp(density, 10), 0, savedIpDp(density, 8))
            }
            bindOperatorRow(group, operator, entries, expanded)
            section.addView(group, savedIpFullWidth())
            if (expanded) {
                if (entries.isNotEmpty()) {
                    bindSavedTable(section, operator, entries)
                } else {
                    bindSavedTable(section, operator, emptyList())
                    section.addView(TextView(context).apply {
                        setText(R.string.saved_ip_filter_no_match)
                        textSize = 13f
                        setTextColor(colorMuted)
                        setPadding(savedIpDp(density, 48), savedIpDp(density, 8), 0, savedIpDp(density, 12))
                    }, savedIpFullWidth())
                }
            }
            val targetOrder = snapshotCache.operators.indexOfFirst { it.id == operatorId }
            var insertAt = operatorContent.childCount
            for (i in 0 until operatorContent.childCount) {
                val childId = operatorContent.getChildAt(i).tag as? String ?: continue
                val childOrder = snapshotCache.operators.indexOfFirst { it.id == childId }
                if (childOrder > targetOrder) { insertAt = i; break }
            }
            operatorContent.addView(section, insertAt, savedIpFullWidth())
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
        const val KEY_PERSISTENT_UI_STATE = "persistent_filter_state"
        val SAVED_TABLE_TAG = Any()
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
            minimumHeight = savedIpDp(density, 56)
        }
        val arrow = TextView(context).apply {
            text = if (expanded) "▾" else "▸"
            textSize = 18f
            setTextColor(colorLink)
            gravity = Gravity.CENTER
        }
        header.addView(arrow, LinearLayout.LayoutParams(savedIpDp(density, 48), savedIpDp(density, 48)))
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(context).apply {
            text = context.getString(R.string.saved_ip_group_title, savedIpDisplayName(context, operator), entries.size)
            textSize = 15f
            setTextColor(colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            background = savedIpSelectableBackground(context)
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
        header.addView(menuButton, LinearLayout.LayoutParams(savedIpDp(density, 48), savedIpDp(density, 48)))
        container.addView(header, savedIpFullWidth())
        container.setOnClickListener {
            expandedOperatorId = if (expandedOperatorId == operator.id) null else operator.id
            renderRows()
        }
        container.addView(View(context).apply { setBackgroundColor(colorDivider) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, savedIpDp(density, 1)))
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
        setPadding(savedIpDp(density, 7), savedIpDp(density, 4), savedIpDp(density, 7), savedIpDp(density, 4))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = savedIpDp(density, 8).toFloat()
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
                showDirectSpeed = showDirectSpeed,
            )
            val width = (operatorList.width - operatorList.paddingLeft - operatorList.paddingRight)
                .takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels - savedIpDp(density, 32))
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
        // CUSTOM #156: hidden direct-speed filter state remains stored but is not shown in the table header.
        val selectedFilters = filterState.selectedTableFilters[operator.id].orEmpty()
            .filterKeys { showDirectSpeed || it != "SPEED" }
        // CUSTOM.md #166: calculate from the complete operator list, before UI filters are applied.
        val recommendedIps = IpRecommend.forSaved(operator.ips, System.currentTimeMillis())
        val speedSortLabel = when {
            currentSort(operator.id) == SavedIpSort.DEFAULT -> ""
            currentSort(operator.id).isFastest -> "按最快"
            else -> "按最慢"
        }
        val onHeaderClick: (Int, View) -> Unit = { columnIndex, anchor ->
            val columnName = renderer.savedTableColumnName(columnIndex)
            if (columnName == "SPEED" || columnName == "SPEED_NODE") showSpeedSortMenu(anchor, columnName, operator.id)
            else showTableColumnPopup(anchor, operator, columnIndex)
        }
        fun makeTable(rows: List<SavedIpEntry>, tableRenderer: IpTestView): View = tableRenderer.savedTableView(
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
                    setPadding(savedIpDp(density, 16), savedIpDp(density, 8), savedIpDp(density, 16), savedIpDp(density, 8))
                }
                val scroll = ScrollView(context).apply {
                    isVerticalScrollBarEnabled = true
                    addView(message, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                dismissFailureDetailDialog()
                val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(
                    scroll,
                    savedIpDp(density, 12),
                    savedIpDp(density, 8),
                    savedIpDp(density, 12),
                    savedIpDp(density, 8),
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
            speedSortColumnName = currentSort(operator.id).columnName,
            onHeaderClick = onHeaderClick,
            recommendedIps = recommendedIps,
            speedAvailable = speedAvailable,
            speedBusy = speedBusy,
            onSpeedTest = { address, direct -> onSpeedTest(operator.id, address, direct) },
        )

        val table = makeTable(entries, renderer).apply { tag = SAVED_TABLE_TAG }
        stickyHeaderTables[operator.id] = table
        container.addView(table, savedIpFullWidth())

        // Keep a separate header-only renderer so the main renderer retains row handles for in-place updates.
        val stickyRenderer = IpTestView(
            context, direct = false, target = false, savedTable = true,
            visibleSavedColumns = visibleSavedColumns, showDirectSpeed = showDirectSpeed,
        )
        val stickyCopy = makeTable(emptyList(), stickyRenderer)
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
            stickyHeaderContainer.addView(copy, savedIpFullWidth())
            stickyHeaderOperatorId = activeOperatorId
        }
        stickyHeaderContainer.visibility = View.VISIBLE
        val sourceScroll = stickyHeaderSourceScrolls[activeOperatorId]
        val copyScroll = stickyHeaderCopyScrolls[activeOperatorId]
        if (sourceScroll != null && copyScroll != null && copyScroll.scrollX != sourceScroll.scrollX) {
            copyScroll.scrollTo(sourceScroll.scrollX, 0)
        }
    }





    // CUSTOM.md #160 (r291): scrollable anchored popup; checkboxes stay open for multi-selection.


    // CUSTOM (CUSTOM.md #160): reuse the existing scrollable checkbox popup for each saved-table column.


    // CUSTOM (CUSTOM.md #164): reconcile each operator's selections only against that operator's values.




    // CUSTOM (CUSTOM.md #159/#164): each speed column has its own fastest / slowest menu.


    // CUSTOM #156: direct-speed visibility applies only to this saved-IP page.


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
        return selection.handleBackPressed()
    }

    // CUSTOM.md #156 step 4: enter / toggle selection only inside one operator group.
    // CUSTOM (CUSTOM.md #163 r330): keep active row toggles synchronous; rebuild only when
    // the checkbox column must appear on entry or disappear after the final item is cleared.




















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
