package com.github.kr328.clash.design.custom

import android.graphics.Typeface
import android.text.StaticLayout
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.core.model.IpTestRow
import com.github.kr328.clash.design.R
import com.google.android.material.button.MaterialButton
import java.util.Locale

internal class IpTestColumnWidths(private val env: IpTestEnv, private val cells: IpTestCells) {
    private fun dp(v: Int): Int = (v * env.context.resources.displayMetrics.density + 0.5f).toInt()

    fun heightOf(v: View, w: Int): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        return v.measuredHeight
    }

    fun contentNeed(k: IpTestView.Key): Int {
        var required = width(k)
        env.rows.forEach { row ->
            val cell = cells.cellOf(k, row, Int.MAX_VALUE / 4) as? TextView ?: return@forEach
            val value = cell.text
            if (value.isEmpty()) return@forEach

            var low = dp(32)
            var high = maxOf(
                required,
                cell.paint.measureText(value.toString().replace('\n', ' ')).toInt() +
                    cell.paddingLeft + cell.paddingRight,
            )
            while (low < high) {
                val mid = (low + high) / 2
                val contentWidth = (mid - cell.paddingLeft - cell.paddingRight).coerceAtLeast(1)
                val layout = StaticLayout.Builder.obtain(
                    value, 0, value.length, cell.paint, contentWidth,
                ).setIncludePad(cell.includeFontPadding).build()
                if (layout.lineCount <= 3) high = mid else low = mid + 1
            }
            required = maxOf(required, low)
        }
        return required
    }

    fun widths(): IntArray {
        val min = IntArray(env.keys.size + 1)
        val floor = IntArray(env.keys.size + 1)

        min[0] = ipMin()
        floor[0] = min[0]

        env.keys.forEachIndexed { i, k ->
            val savedSpeedColumn = env.savedTable && k in setOf(IpTestView.Key.SPEED, IpTestView.Key.SPEED_NODE)
            val header = if (savedSpeedColumn) savedSpeedHeaderNeed(k) else headerNeed(k)
            val button = when {
                savedSpeedColumn -> savedSpeedButtonNeed(k)
                k == IpTestView.Key.TEST -> speedButtonNeed()
                else -> 0
            }
            val requiredContentWidth = if (
                k !in setOf(IpTestView.Key.TEST, IpTestView.Key.PORTS, IpTestView.Key.PHASE, IpTestView.Key.EXIT_IP) &&
                !savedSpeedColumn
            ) contentNeed(k) else 0
            floor[i + 1] = maxOf(header, button, requiredContentWidth)
            min[i + 1] = maxOf(if (savedSpeedColumn) 0 else width(k), floor[i + 1])
        }

        // CUSTOM.md #94: the time split is as wide as its longest line, so "首字节 447 ms" does not wrap
        val phaseAt = env.keys.indexOf(IpTestView.Key.PHASE) + 1

        if (phaseAt > 0) {
            val need = phaseNeed()
            min[phaseAt] = maxOf(min[phaseAt], need)
            floor[phaseAt] = maxOf(floor[phaseAt], need)
        }

        // Keep the public-facing IP and operator on their own two lines, without wrapping either value.
        val exitIpAt = env.keys.indexOf(IpTestView.Key.EXIT_IP) + 1

        if (exitIpAt > 0) {
            val need = exitIpNeed()
            min[exitIpAt] = maxOf(min[exitIpAt], need)
            floor[exitIpAt] = maxOf(floor[exitIpAt], need)
        }

        val avail = if (env.savedTable && env.savedTableAvailableWidth > 0) env.savedTableAvailableWidth else env.tableBox.width
        val sum = min.sum()
        val speedColumns = env.keys.indices.filter { env.keys[it] == IpTestView.Key.SPEED || env.keys[it] == IpTestView.Key.SPEED_NODE }.map { it + 1 }

        // how the spare width is shared: the columns with the longest text get the most
        fun weightOf(i: Int): Double {
            val factor = if (i == 0) 1.0 else when (env.keys[i - 1]) {
                IpTestView.Key.COLO, IpTestView.Key.TCOLO, IpTestView.Key.ASN, IpTestView.Key.EXIT_IP, IpTestView.Key.PORTS -> 1.8
                IpTestView.Key.GEO, IpTestView.Key.LAND -> 1.3
                else -> 1.0
            }

            return min[i] * factor
        }

        if (avail >= sum) {
            val weight = DoubleArray(min.size) { weightOf(it) }
            val total = weight.sum()
            val extra = avail - sum
            val out = IntArray(min.size) { i -> min[i] + (extra * weight[i] / total).toInt() }

            out[out.size - 1] += avail - out.sum()

            return out
        }

        // CUSTOM.md #62: everything but 速度 fits: those columns keep their natural widths and fill the page, and 速度
        // stays behind the right edge (the table scrolls sideways to it)
        if (speedColumns.isNotEmpty()) {
            val sumNoSpeed = sum - speedColumns.sumOf { min[it] }

            if (avail >= sumNoSpeed) {
                val weight = DoubleArray(min.size) { if (it in speedColumns) 0.0 else weightOf(it) }
                val total = weight.sum()
                val extra = avail - sumNoSpeed
                val out = IntArray(min.size) { i -> min[i] + (extra * weight[i] / total).toInt() }

                val firstColumnAt = env.keys.indexOf(if (env.direct || env.savedTable) IpTestView.Key.COLO else IpTestView.Key.PORT) + 1
                val nonSpeedColumns = (0 until min.size).filter { it !in speedColumns }
                out[firstColumnAt] += avail - sumNoSpeed - nonSpeedColumns.sumOf { out[it] - min[it] }

                return out
            }
        }

        // CUSTOM.md #55 (r67): a little too wide (a long IP in the first column): the other columns give up to 15%
        // (their text just wraps one more time) so that the last column is not pushed off the screen; only when that
        // is not enough the table scrolls sideways as before
        val rest = sum - min[0]
        val need = sum - avail

        if (rest > 0 && need <= rest * 0.15) {
            val out = IntArray(min.size)

            out[0] = min[0]

            for (i in 1 until min.size) out[i] = (min[i] * (rest - need).toDouble() / rest).toInt()

            out[out.size - 1] += avail - out.sum()

            // Keep every header/button's longest explicit line intact. If the remaining columns cannot
            // absorb the shrink without breaking that minimum, retain the natural widths and scroll.
            for (i in out.indices) out[i] = maxOf(out[i], floor[i])

            if (out.sum() <= avail) return out
        }

        return min
    }

    fun headerNeed(k: IpTestView.Key): Int {
        val sample = TextView(env.context).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        val longest = title(k).split('\n').maxOf { sample.paint.measureText(it) }

        return longest.toInt() + 1 + dp(8)
    }

    fun speedButtonNeed(): Int {
        val sample = MaterialButton(env.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 13f
            isAllCaps = false
            setPadding(dp(4), 0, dp(4), 0)
        }
        val label = env.context.getString(speedButtonTextRes())
        val longest = label.split('\n').maxOf { sample.paint.measureText(it) }

        return longest.toInt() + 1 + dp(8)
    }

    fun speedButtonTextRes(): Int {
        val language = env.context.resources.configuration.locale?.language ?: Locale.getDefault().language

        return if (language == "zh") R.string.iptest_btn_speed_button else R.string.iptest_btn_speed
    }

    fun speedButtonMinHeight(): Int {
        val sample = MaterialButton(env.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 13f
            isAllCaps = false
        }
        val line = sample.paint.fontMetricsInt.let { it.bottom - it.top }

        return maxOf(dp(32), line * 2 + dp(4))
    }

    fun phaseNeed(): Int {
        val paint = TextView(env.context).apply { textSize = 14f }.paint
        val longest = env.rows.maxOfOrNull { r ->
            val texts = if (env.savedTable) listOf(phaseText(env.context, r, true), phaseText(env.context, r, false)) else listOf(phaseText(env.context, r, env.direct))
            texts.maxOf { text -> text.split('\n').maxOf { paint.measureText(it) } }
        } ?: 0f

        return (longest.toInt() + dp(10)).coerceAtMost(dp(120))
    }

    fun exitIpNeed(): Int {
        val paint = TextView(env.context).apply { textSize = 14f }.paint
        val longest = env.rows.maxOfOrNull { r -> maxOf(paint.measureText(r.exitIp), paint.measureText(r.exitAsn)) } ?: 0f

        return (longest.toInt() + dp(16)).coerceAtLeast(dp(116))
    }

    fun ipMin(): Int {
        val paint = TextView(env.context).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }.paint

        val longest = env.rows.maxOfOrNull { paint.measureText(it.ip) } ?: 0f

        return (longest.toInt() + dp(16)).coerceIn(dp(112), dp(150))
    }

    fun savedHeaderTitle(k: IpTestView.Key): String {
        return when (k) {
            IpTestView.Key.SPEED -> env.context.getString(R.string.saved_ip_speed_direct_short)
            IpTestView.Key.SPEED_NODE -> env.context.getString(R.string.saved_ip_speed_node_short)
            else -> title(k)
        }
    }

    fun title(k: IpTestView.Key): String {
        return env.context.getString(
            when (k) {
                IpTestView.Key.COLO -> if (env.direct || env.savedTable) R.string.iptest_col_colo else R.string.iptest_col_colo_node
                IpTestView.Key.ASN -> R.string.iptest_col_asn
                IpTestView.Key.GEO -> R.string.iptest_col_geo
                IpTestView.Key.LAND, IpTestView.Key.EXIT -> R.string.iptest_col_exit
                IpTestView.Key.EXIT_IP -> R.string.iptest_col_exit_ip
                IpTestView.Key.PORTS, IpTestView.Key.PORT -> R.string.iptest_col_port
                IpTestView.Key.LOSS -> R.string.iptest_col_loss
                IpTestView.Key.MIN -> R.string.iptest_col_min
                IpTestView.Key.MAX -> R.string.iptest_col_max
                IpTestView.Key.AVG -> R.string.iptest_col_avg
                IpTestView.Key.JITTER -> R.string.iptest_col_jitter
                IpTestView.Key.PHASE -> R.string.iptest_col_phase
                IpTestView.Key.TARGET -> R.string.iptest_col_target
                IpTestView.Key.TCOLO -> R.string.iptest_col_tcolo
                IpTestView.Key.TEST -> R.string.iptest_col_test
                IpTestView.Key.SPEED -> R.string.saved_ip_speed_direct
                IpTestView.Key.SPEED_NODE -> R.string.saved_ip_speed_node
            },
        )
    }

    fun savedSpeedHeaderNeed(k: IpTestView.Key): Int {
        val title = TextView(env.context).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        val detail = TextView(env.context).apply { textSize = 11f }
        val titleWidth = title.paint.measureText(savedHeaderTitle(k)).toInt() + dp(8)
        val unitWidth = detail.paint.measureText(env.context.getString(R.string.saved_ip_speed_unit)).toInt() + dp(6)
        val sortWidth = maxOf(
            detail.paint.measureText("按最快"),
            detail.paint.measureText("按最慢"),
        ).toInt() + dp(6)
        return maxOf(titleWidth, unitWidth, sortWidth)
    }

    fun savedSpeedButtonNeed(k: IpTestView.Key): Int {
        val sample = MaterialButton(env.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 14f
            isAllCaps = false
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        val label = env.context.getString(
            if (k == IpTestView.Key.SPEED) R.string.saved_ip_speed_direct_short else R.string.saved_ip_speed_node_short,
        )
        val longest = maxOf(
            label.split('\n').maxOf { sample.paint.measureText(it) },
            sample.paint.measureText("88.8"),
        )
        // Include the button's own horizontal padding and the cell container's two 4dp margins.
        return longest.toInt() + dp(1) + dp(6) + dp(8)
    }

    fun width(k: IpTestView.Key): Int {
        return dp(
            when (k) {
                IpTestView.Key.COLO -> 84
                IpTestView.Key.ASN -> 84
                IpTestView.Key.GEO, IpTestView.Key.LAND -> 76
                IpTestView.Key.PORTS -> 100 // CUSTOM.md #57: room for the replacement span's same-colour dot
                IpTestView.Key.PORT -> 56
                IpTestView.Key.LOSS -> 52
                IpTestView.Key.MIN, IpTestView.Key.MAX, IpTestView.Key.AVG -> 54
                IpTestView.Key.JITTER -> 54
                IpTestView.Key.PHASE -> 84
                IpTestView.Key.EXIT -> 76
                IpTestView.Key.EXIT_IP -> 116
                IpTestView.Key.TARGET -> 84
                IpTestView.Key.TCOLO -> 84
                IpTestView.Key.TEST -> 52
                IpTestView.Key.SPEED -> 96
                IpTestView.Key.SPEED_NODE -> 96
            },
        )
    }

    fun savedTableWidths(availableWidth: Int, savedRows: List<IpTestRow>, cityNames: Map<String, String>, countryCodes: Map<String, String>): IntArray {
        env.rows = savedRows
        env.colos = cityNames
        env.coloCountries = countryCodes
        env.savedTableAvailableWidth = availableWidth
        return widths()
    }
}
