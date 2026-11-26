package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.design.R
import com.google.android.material.button.MaterialButton
import java.util.Locale

internal data class SavedSpeedButtons(var direct: SavedSpeedCell? = null, var node: SavedSpeedCell? = null)
internal data class SavedSpeedCell(
    val container: FrameLayout, val button: ProgressMaterialButton, val cellWidth: Int,
    val label: String, var speed: Double, var short: Boolean,
)
internal class SavedTableState(private val context: Context, visibleSavedColumns: Set<String>?) {
    val visibleSavedColumns: Set<String>? = visibleSavedColumns
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()
    val savedSpeedButtonHeight by lazy {
        val sample = "经节点\n88.8"
        val paint = TextView(context).apply { textSize = 14f }.paint
        StaticLayout.Builder.obtain(sample, 0, sample.length, paint, dp(80)).setIncludePad(true)
            .setLineSpacing(dp(2).toFloat(), 1f).build().height + dp(8)
    }
    val savedSpeedShortButtonHeight by lazy {
        val sample = SpannableStringBuilder("经节点\n88.8\n不足上限")
        val hintStart = sample.length - "不足上限".length
        sample.setSpan(android.text.style.AbsoluteSizeSpan(11, true), hintStart, sample.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val paint = TextView(context).apply { textSize = 14f }.paint
        StaticLayout.Builder.obtain(sample, 0, sample.length, paint, dp(80)).setIncludePad(true)
            .setLineSpacing(dp(2).toFloat(), 1f).build().height + dp(8)
    }
    val savedSpeedButtons = HashMap<String, SavedSpeedButtons>()
    val savedRowViews = LinkedHashMap<String, Pair<View, View>>()
    val savedRowBaseHeights = HashMap<String, Int>()
    var savedRowsLeft: LinearLayout? = null
    var savedRowsGrid: LinearLayout? = null
    val savedTableKeys = listOf(
        // The IP column is rendered separately in the fixed left pane; these are the first two
        // columns in the horizontally scrolling grid, in direct-then-node order.
        IpTestView.Key.SPEED, IpTestView.Key.SPEED_NODE, IpTestView.Key.COLO, IpTestView.Key.ASN, IpTestView.Key.GEO, IpTestView.Key.EXIT, IpTestView.Key.PORTS, IpTestView.Key.LOSS,
        IpTestView.Key.MIN, IpTestView.Key.MAX, IpTestView.Key.AVG, IpTestView.Key.JITTER, IpTestView.Key.EXIT_IP, IpTestView.Key.TCOLO, IpTestView.Key.TARGET, IpTestView.Key.PHASE,
    )
}
internal class ProgressMaterialButton(context: Context, colorLink: Int) : MaterialButton(
    context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x35, Color.red(colorLink), Color.green(colorLink), Color.blue(colorLink))
    }
    private val fillPath = Path()
    private val fillBounds = RectF()
    var progressLevel: Int = 0
        set(value) {
            val next = value.coerceIn(0, 10000)
            if (field == next) return
            field = next
            invalidate()
        }
    override fun onDraw(canvas: Canvas) {
        if (progressLevel > 0 && width > 0 && height > 0) {
            val inset = strokeWidth.coerceAtLeast(0).toFloat() / 2f
            fillBounds.set(inset, inset, width - inset, height - inset)
            val radius = (cornerRadius.toFloat() - inset).coerceAtLeast(0f)
            fillPath.reset()
            fillPath.addRoundRect(fillBounds, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(fillPath)
            val right = fillBounds.left + fillBounds.width() * (progressLevel / 10000f)
            canvas.drawRect(fillBounds.left, fillBounds.top, right, fillBounds.bottom, fillPaint)
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }
}


internal class SavedIpTableView(
    private val env: IpTestEnv,
    private val cells: IpTestCells,
    private val columnWidths: IpTestColumnWidths,
    private val state: SavedTableState,
) {
    private fun dp(v: Int): Int = (v * env.context.resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    fun isSavedTableFilterable(key: IpTestView.Key): Boolean = key in setOf(IpTestView.Key.COLO, IpTestView.Key.ASN, IpTestView.Key.GEO, IpTestView.Key.EXIT, IpTestView.Key.PORTS, IpTestView.Key.EXIT_IP, IpTestView.Key.TCOLO, IpTestView.Key.TARGET)


    fun isSavedSpeedColumn(index: Int): Boolean = env.keys.getOrNull(index) == IpTestView.Key.SPEED || env.keys.getOrNull(index) == IpTestView.Key.SPEED_NODE


    fun savedTableColumnOptions(): List<Pair<String, String>> =
        state.savedTableKeys.map { it.name to columnWidths.title(it) }


    fun savedTableColumnName(columnIndex: Int): String = env.keys.getOrNull(columnIndex)?.name.orEmpty()


    fun savedTableFilterOptions(entries: List<SavedIpEntry>, columnName: String): List<String> =
        entries.asSequence().map { savedTableFilterValue(it, columnName) }.filter { it.isNotBlank() && it != "-" }.distinct().sorted().toList()

    fun savedTableFilterValue(entry: SavedIpEntry, columnName: String): String {
        val key = env.keys.firstOrNull { it.name == columnName } ?: return ""
        if (!isSavedTableFilterable(key)) return ""
        val row = entry.result ?: return ""
        val mapped = when (key) {
            IpTestView.Key.COLO -> IpTestView.Key.COLO
            IpTestView.Key.ASN -> IpTestView.Key.ASN
            IpTestView.Key.GEO -> IpTestView.Key.GEO
            IpTestView.Key.EXIT -> if (entry.testDirect) IpTestView.Key.LAND else IpTestView.Key.EXIT
            IpTestView.Key.PORTS -> if (entry.testDirect) IpTestView.Key.PORTS else IpTestView.Key.PORT
            IpTestView.Key.EXIT_IP -> if (entry.testDirect) null else IpTestView.Key.EXIT_IP
            IpTestView.Key.TCOLO -> if (entry.testTarget) IpTestView.Key.TCOLO else null
            IpTestView.Key.TARGET -> if (entry.testTarget) IpTestView.Key.TARGET else null
            else -> null
        } ?: return ""
        if (key == IpTestView.Key.EXIT && row.country.isEmpty()) return ""
        return when (mapped) {
            IpTestView.Key.COLO -> coloLabel(row.colo, env.colos, env.coloCountries).takeIf { it != "-" }.orEmpty()
            IpTestView.Key.ASN -> row.asn
            IpTestView.Key.GEO -> if (row.ok) CountryName.label(row.geo).takeIf { it != "-" }.orEmpty() else ""
            IpTestView.Key.LAND -> if (row.ok) CountryName.label(row.country).takeIf { it != "-" }.orEmpty() else ""
            IpTestView.Key.EXIT -> if (row.ok) CountryName.label(row.country).takeIf { it != "-" && it != "？" }.orEmpty() else ""
            IpTestView.Key.PORT -> row.port
            IpTestView.Key.PORTS -> portsPlain(row).takeIf { it != "-" }.orEmpty()
            IpTestView.Key.EXIT_IP -> if (row.ok && row.exitIp.isNotBlank()) row.exitIp + "\n" + row.exitAsn.ifEmpty { "-" } else ""
            IpTestView.Key.TCOLO -> coloLabel(row.targetColo, env.colos, env.coloCountries).takeIf { it != "-" }.orEmpty()
            IpTestView.Key.TARGET -> (if (!row.ok && row.reason.length > 40) handshakeFailureText(env.context, row.reason.take(40)) + "…" else targetText(env.context, row)).takeIf { it != "-" }.orEmpty()
            else -> ""
        }.trim()
    }

    fun savedHeaderCell(title: String, width: Int, selected: Set<String>, summaryOverride: String? = null, showSpeedUnit: Boolean = false, onClick: (View) -> Unit): View {
        val titleView = TextView(env.context).apply {
            text = title; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(env.colorMuted); setPadding(dp(4), dp(2), dp(4), 0)
        }
        val unitView = TextView(env.context).apply {
            text = env.context.getString(R.string.saved_ip_speed_unit)
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(env.colorMuted)
            minHeight = dp(16); setPadding(dp(3), 0, dp(3), 0)
        }
        val selectedView = TextView(env.context).apply {
            // CUSTOM (CUSTOM.md #160): filter summary; speed columns use this line for sort direction.
            text = summaryOverride ?: savedIpFilterSummary(selected)
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(env.colorLink); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; setPadding(dp(3), 0, dp(3), dp(2))
            minHeight = dp(16)
            visibility = if (summaryOverride.isNullOrEmpty() && selected.isEmpty()) View.INVISIBLE else View.VISIBLE
        }
        return LinearLayout(env.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val selectable = android.util.TypedValue()
            if (env.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true) && selectable.resourceId != 0) setBackgroundResource(selectable.resourceId)
            addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (showSpeedUnit) addView(unitView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(selectedView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tag = selectedView
            isClickable = true; minimumWidth = width; setOnClickListener { if (isEnabled) onClick(this) }
        }
    }

    fun savedTableView(
        entries: List<SavedIpEntry>,
        widths: IntArray,
        scrollX: Int,
        onScroll: (Int) -> Unit,
        isSelectionMode: () -> Boolean,
        isSelected: (String) -> Boolean,
        onCopy: (String) -> Unit,
        onDetail: (String, String) -> Unit,
        onFailureDetail: (String, String) -> Unit = onDetail,
        onLongPress: (String) -> Boolean,
        selectedFilters: Map<String, Set<String>> = emptyMap(),
        speedSortLabel: String = "",
        speedSortColumnName: String? = null,
        onHeaderClick: (Int, View) -> Unit = { _, _ -> },
        configIpSources: Map<String, List<com.github.kr328.clash.core.model.IpTestConfigIp>> = emptyMap(),
        recommendedIps: Set<String> = emptySet(),
        speedAvailable: Boolean = true,
        speedBusy: Boolean = false,
        onSpeedTest: (String, Boolean) -> Unit = { _, _ -> },
    ): View {
        val gridWidth = widths.drop(1).sum()
        val selectionMode = isSelectionMode()
        val checkboxWidth = if (selectionMode) dp(40) else 0
        val ipColumnWidth = widths[0] + checkboxWidth
        val left = LinearLayout(env.context).apply { orientation = LinearLayout.VERTICAL }
        val grid = LinearLayout(env.context).apply { orientation = LinearLayout.VERTICAL }
        state.savedSpeedButtons.clear()
        state.savedRowViews.clear()
        state.savedRowBaseHeights.clear()
        val leftHeader = this.cells.headCell(env.context.getString(R.string.iptest_col_ip), ipColumnWidth)
        val headers = env.keys.mapIndexed { index, key ->
            val isSpeedColumn = key == IpTestView.Key.SPEED || key == IpTestView.Key.SPEED_NODE
            savedHeaderCell(
                columnWidths.savedHeaderTitle(key),
                widths[index + 1],
                selectedFilters[key.name].orEmpty(),
                if (isSpeedColumn && key.name == speedSortColumnName) speedSortLabel else null,
                showSpeedUnit = isSpeedColumn,
            ) { anchor ->
                if (isSavedTableFilterable(key) || isSpeedColumn) onHeaderClick(index, anchor)
            }.apply { isEnabled = isSavedTableFilterable(key) || isSpeedColumn }
        }
        val headerHeight = maxOf(
            env.headH,
            columnWidths.heightOf(leftHeader, ipColumnWidth),
            headers.mapIndexed { index, cell -> columnWidths.heightOf(cell, widths[index + 1]) }.maxOrNull() ?: 0,
        ) + dp(6)
        left.addView(leftHeader, lp(ipColumnWidth, headerHeight))
        val headerRow = LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(env.colorSurface)
        }
        headers.forEachIndexed { index, cell ->
            cell.setBackgroundColor(Color.TRANSPARENT)
            headerRow.addView(cell, lp(widths[index + 1], headerHeight))
        }
        grid.addView(headerRow, lp(gridWidth, headerHeight))

        val now = System.currentTimeMillis()
        entries.forEachIndexed { rowIndex, entry ->
            val result = entry.result
            // CUSTOM.md #156: the config source is the green second line below; drop the stored inHosts so ipDetails() does not repeat it.
            val row = (result ?: IpTestRow(ip = entry.address, ok = true)).copy(inHosts = false)
            val renderer = IpTestView(env.context, entry.testDirect, entry.testTarget)
            renderer.colos = env.colos
            renderer.coloCountries = env.coloCountries
            renderer.recommendedIps = recommendedIps
            renderer.onCopy = { address ->
                if (isSelectionMode()) {
                    onLongPress(address)
                    Unit
                } else onCopy(address)
            }
            renderer.onDetail = onDetail
            val ipView = renderer.ipCell(row, showRecommendation = false, showFailureDetail = false)
            // CUSTOM (CUSTOM.md #156): configuration source is the second line; test details follow it.
            val configSources = configIpSources[entry.address].orEmpty()
            if (configSources.isNotEmpty()) {
                // CUSTOM (changes.md #156): capsules and source names share the same 12sp text size.
                (ipView as? LinearLayout)?.addView(
                    SavedIpSourcePills.create(env.context, configSources, env.night, ::dp),
                    1,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            val timeText = SavedIpTimeText.text(env.context, entry.lastTestedAt, now)
            if (entry.address in recommendedIps || timeText.isNotEmpty() || entry.failStreak >= 3) {
                val label = SpannableStringBuilder()
                if (entry.address in recommendedIps) {
                    val start = label.length
                    label.append("推荐")
                    label.setSpan(ForegroundColorSpan(env.colorWarn), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    label.setSpan(android.text.style.AbsoluteSizeSpan(13, true), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (timeText.isNotEmpty()) label.append("　")
                }
                label.append(timeText)
                if (entry.failStreak >= 3) {
                    if (label.isNotEmpty()) label.append("　")
                    val start = label.length
                    label.append("连续不通 ${entry.failStreak} 次")
                    label.setSpan(
                        ForegroundColorSpan(env.colorBad),
                        start,
                        label.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                (ipView as? LinearLayout)?.addView(
                    TextView(env.context).apply {
                        text = label; textSize = 13f; maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(if (now - entry.lastTestedAt > 7L * 24L * 60L * 60L * 1000L) env.colorWarn else env.colorMuted)
                    },
                    if (configSources.isNotEmpty()) 2 else 1,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            if (!row.ok) {
                val summary = savedFailureSummary(env.context, row)
                val rawReason = row.reason.ifBlank { row.code.ifBlank { "unknown error" } }
                val endpoint = if (row.port.isNotBlank()) "${entry.address}:${row.port}" else entry.address
                (ipView as? LinearLayout)?.addView(
                    TextView(env.context).apply {
                        text = summary
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(env.colorBad)
                        setOnClickListener {
                            onFailureDetail(entry.address, "$endpoint connect error: $rawReason")
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }

            val selected = isSelected(entry.address)
            val selectedBackground = Color.argb(
                36, Color.red(env.colorLink), Color.green(env.colorLink), Color.blue(env.colorLink),
            )
            val rowBackground = if (selected) selectedBackground else env.colorSurface
            var selectionCheckBox: CheckBox? = null
            val leftRow = LinearLayout(env.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(rowBackground)
                if (selectionMode) {
                    val checkBox = CheckBox(env.context).apply {
                        isChecked = selected
                        contentDescription = entry.address
                    }
                    selectionCheckBox = checkBox
                    addView(checkBox, LinearLayout.LayoutParams(checkboxWidth, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                addView(ipView, LinearLayout.LayoutParams(widths[0], ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val cells = env.keys.mapIndexed { index, key ->
                val directMode = entry.testDirect
                val targetMode = entry.testTarget
                val mapped = when (key) {
                    IpTestView.Key.COLO -> IpTestView.Key.COLO
                    IpTestView.Key.ASN -> IpTestView.Key.ASN
                    IpTestView.Key.GEO -> IpTestView.Key.GEO
                    IpTestView.Key.EXIT -> if (directMode) IpTestView.Key.LAND else IpTestView.Key.EXIT
                    IpTestView.Key.PORTS -> if (directMode) IpTestView.Key.PORTS else IpTestView.Key.PORT
                    IpTestView.Key.LOSS -> IpTestView.Key.LOSS
                    IpTestView.Key.MIN -> IpTestView.Key.MIN
                    IpTestView.Key.MAX -> IpTestView.Key.MAX
                    IpTestView.Key.AVG -> IpTestView.Key.AVG
                    IpTestView.Key.JITTER -> IpTestView.Key.JITTER
                    IpTestView.Key.EXIT_IP -> if (directMode) null else IpTestView.Key.EXIT_IP
                    IpTestView.Key.TCOLO -> if (targetMode) IpTestView.Key.TCOLO else null
                    IpTestView.Key.TARGET -> if (targetMode) IpTestView.Key.TARGET else null
                    IpTestView.Key.PHASE -> IpTestView.Key.PHASE
                    IpTestView.Key.SPEED -> IpTestView.Key.SPEED
                    IpTestView.Key.SPEED_NODE -> IpTestView.Key.SPEED_NODE
                    else -> null
                }
                val missingExit = key == IpTestView.Key.EXIT && row.country.isEmpty()
                when {
                    key == IpTestView.Key.SPEED -> savedSpeedCell(
                        env.context.getString(R.string.saved_ip_speed_direct_short), entry.speedDirect, entry.speedDirectShort,
                        speedAvailable, speedBusy, widths[index + 1],
                    ) { onSpeedTest(entry.address, true) }
                        .also { state.savedSpeedButtons.getOrPut(entry.address) { SavedSpeedButtons() }.direct = it }.container
                    key == IpTestView.Key.SPEED_NODE -> savedSpeedCell(
                        env.context.getString(R.string.saved_ip_speed_node_short), entry.speedNode, entry.speedNodeShort,
                        speedAvailable, speedBusy, widths[index + 1],
                    ) { onSpeedTest(entry.address, false) }
                        .also { state.savedSpeedButtons.getOrPut(entry.address) { SavedSpeedButtons() }.node = it }.container
                    result == null || mapped == null || missingExit -> this.cells.textCell("-", env.colorMuted)
                    mapped == IpTestView.Key.PHASE -> this.cells.textCell(phaseText(env.context, row, directMode), env.colorText)
                    mapped == IpTestView.Key.PORT -> this.cells.textCell(row.port.ifEmpty { "-" }, if (row.port.isEmpty()) env.colorMuted else env.colorText)
                    mapped == IpTestView.Key.LOSS && row.sent <= 0 -> this.cells.textCell("-", env.colorMuted)
                    mapped in listOf(IpTestView.Key.MIN, IpTestView.Key.MAX, IpTestView.Key.AVG) && when (mapped) {
                        IpTestView.Key.MIN -> row.min <= 0
                        IpTestView.Key.MAX -> row.max <= 0
                        else -> row.avg <= 0
                    } -> this.cells.textCell("-", env.colorMuted)
                    mapped == IpTestView.Key.JITTER && row.sent <= 0 -> this.cells.textCell("-", env.colorMuted)
                    else -> renderer.cellOf(mapped, row, widths[index + 1])
                }
            }
            val baseHeight = maxOf(
                env.rowH,
                columnWidths.heightOf(ipView, widths[0]),
                cells.mapIndexedNotNull { index, cell ->
                    if (env.keys[index] == IpTestView.Key.SPEED || env.keys[index] == IpTestView.Key.SPEED_NODE) null
                    else columnWidths.heightOf(cell, widths[index + 1])
                }.maxOrNull() ?: 0,
            )
            state.savedRowBaseHeights[entry.address] = baseHeight
            val height = maxOf(
                baseHeight,
                cells.mapIndexed { index, cell -> columnWidths.heightOf(cell, widths[index + 1]) }.maxOrNull() ?: 0,
            ) + dp(8)
            val line = LinearLayout(env.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(rowBackground)
            }
            cells.forEachIndexed { index, cell -> line.addView(cell, lp(widths[index + 1], height)) }
            val leftRowContainer = env.rowWithDivider(leftRow, rowBackground, rowIndex < entries.lastIndex)
            val gridRowContainer = env.rowWithDivider(line, rowBackground, rowIndex < entries.lastIndex)
            left.addView(leftRowContainer, lp(ipColumnWidth, height))
            grid.addView(gridRowContainer, lp(gridWidth, height))
            state.savedRowViews[entry.address] = leftRowContainer to gridRowContainer

            // CUSTOM.md #163: preserve row-wide tap and long-press selection behavior.
            SavedIpRowSelection.attach(
                leftRow, line, leftRowContainer, gridRowContainer, selectionMode, selectionCheckBox,
                isSelectionMode, { onLongPress(entry.address) }, selectedBackground, env.colorSurface,
            )
        }

        state.savedRowsLeft = left
        state.savedRowsGrid = grid
        val scroll = HorizontalScrollView(env.context).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(grid, ViewGroup.LayoutParams(gridWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnScrollChangeListener { _, x, _, _, _ -> onScroll(x) }
        }
        // CUSTOM (CUSTOM.md #165): restore the horizontal position before the first frame is drawn,
        // so a rebuilt table never shows at the far left for one frame and then jumps back.
        val restoreScrollListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if (view.width <= 0) return
                view.removeOnLayoutChangeListener(this)
                view.scrollTo(scrollX, 0)
            }
        }
        scroll.addOnLayoutChangeListener(restoreScrollListener)
        return LinearLayout(env.context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(ipColumnWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun setSavedSpeedAvailability(available: Boolean, busy: Boolean) {
        state.savedSpeedButtons.values.forEach { buttons ->
            buttons.direct?.button?.isEnabled = available && !busy
            buttons.node?.button?.isEnabled = available && !busy
        }
    }

    fun updateSavedSpeed(address: String, direct: Boolean, speed: Double, short: Boolean, available: Boolean, busy: Boolean) {
        val cell = state.savedSpeedButtons[address]?.let { if (direct) it.direct else it.node } ?: return
        cell.speed = speed
        cell.short = short
        cell.button.progressLevel = 0
        val button = cell.button
        val showShort = speed > 0.0 && short
        val buttonHeight = if (showShort) state.savedSpeedShortButtonHeight else state.savedSpeedButtonHeight
        val label = env.context.getString(if (direct) R.string.saved_ip_speed_direct_short else R.string.saved_ip_speed_node_short)
        button.text = savedSpeedText(label, speed, showShort, cell.cellWidth)
        button.maxLines = if (showShort) 3 else 2
        button.minHeight = buttonHeight
        button.maxHeight = buttonHeight
        (button.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.height = buttonHeight
            button.layoutParams = it
        }
        button.isEnabled = available && !busy
        updateSavedRowHeight(address)
    }

    fun updateSavedSpeedProgress(address: String, direct: Boolean, percent: Int?) {
        val cell = state.savedSpeedButtons[address]?.let { if (direct) it.direct else it.node } ?: return
        cell.button.progressLevel = (percent ?: 0).coerceIn(0, 100) * 100
        cell.button.text = if (percent == null) {
            savedSpeedText(cell.label, cell.speed, cell.speed > 0.0 && cell.short, cell.cellWidth)
        } else {
            cell.label + "\n" + percent.coerceIn(0, 100) + "%"
        }
    }

    fun updateSavedRowHeight(address: String) {
        val baseHeight = state.savedRowBaseHeights[address] ?: return
        val buttons = state.savedSpeedButtons[address] ?: return
        val speedHeight = maxOf(
            buttons.direct?.button?.layoutParams?.height ?: 0,
            buttons.node?.button?.layoutParams?.height ?: 0,
        ) + dp(4)
        val rowHeight = maxOf(baseHeight, speedHeight) + dp(8)
        val (leftRow, gridRow) = state.savedRowViews[address] ?: return
        leftRow.layoutParams = leftRow.layoutParams.apply { height = rowHeight }
        gridRow.layoutParams = gridRow.layoutParams.apply { height = rowHeight }
        leftRow.requestLayout()
        gridRow.requestLayout()
    }

    fun moveSavedRow(address: String, orderedEntries: List<SavedIpEntry>) {
        val left = state.savedRowsLeft ?: return
        val grid = state.savedRowsGrid ?: return
        val (leftRow, gridRow) = state.savedRowViews[address] ?: return
        val targetRowIndex = orderedEntries.indexOfFirst { it.address == address }
        if (targetRowIndex < 0) return
        val currentRowIndex = left.indexOfChild(leftRow) - 1
        if (currentRowIndex == targetRowIndex) return
        left.removeView(leftRow)
        grid.removeView(gridRow)
        val targetChildIndex = targetRowIndex + 1 // index zero is the fixed header
        left.addView(leftRow, targetChildIndex.coerceAtMost(left.childCount))
        grid.addView(gridRow, targetChildIndex.coerceAtMost(grid.childCount))
    }

    fun savedSpeedText(label: String, speed: Double, showShort: Boolean, cellWidth: Int): CharSequence {
        val value = SpannableStringBuilder("$label\n" + if (speed > 0.0) String.format(Locale.US, "%.1f", speed) else " ")
        if (showShort) {
            val hint = env.context.getString(R.string.saved_ip_speed_short)
            val shownHint = hint
            val start = value.length
            value.append("\n").append(shownHint)
            value.setSpan(ForegroundColorSpan(env.colorWarn), start, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            value.setSpan(android.text.style.AbsoluteSizeSpan(11, true), start, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return value
    }

    fun savedSpeedCell(
        label: String,
        speed: Double,
        short: Boolean,
        available: Boolean,
        busy: Boolean,
        cellWidth: Int,
        onClick: () -> Unit,
    ): SavedSpeedCell {
        val showShort = speed > 0.0 && short
        val buttonHeight = if (showShort) state.savedSpeedShortButtonHeight else state.savedSpeedButtonHeight
        val button = ProgressMaterialButton(env.context, env.colorLink).apply {
            text = savedSpeedText(label, speed, showShort, cellWidth)
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
            isAllCaps = false
            gravity = Gravity.CENTER
            maxLines = if (showShort) 3 else 2
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
            minHeight = buttonHeight
            maxHeight = buttonHeight
            insetTop = 0
            insetBottom = 0
            setPadding(dp(3), dp(2), dp(3), dp(2))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(env.colorMuted)
            strokeWidth = dp(1)
            isEnabled = available && !busy
            setOnClickListener { if (isEnabled) onClick() }
        }
        val container = FrameLayout(env.context).apply {
            val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, buttonHeight, Gravity.CENTER).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
            addView(button, FrameLayout.LayoutParams(params))
        }
        return SavedSpeedCell(container, button, cellWidth, label, speed, short)
    }
}
