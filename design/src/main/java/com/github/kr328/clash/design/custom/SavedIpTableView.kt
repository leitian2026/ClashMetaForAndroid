package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
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
internal class SavedTableState(context: Context, visibleSavedColumns: Set<String>?) {
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
    fun isSavedTableFilterable(key: Key): Boolean = key in setOf(IpTestView.Key.COLO, IpTestView.Key.ASN, IpTestView.Key.GEO, IpTestView.Key.EXIT, IpTestView.Key.PORTS, IpTestView.Key.EXIT_IP, IpTestView.Key.TCOLO, IpTestView.Key.TARGET)


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
        onHeaderClick: (Int, View) -> Unit = { _, _ -> }

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
