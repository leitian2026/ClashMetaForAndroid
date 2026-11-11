package com.github.kr328.clash.design.custom

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.graphics.Typeface
import android.view.View
import com.github.kr328.clash.core.model.EffectiveConfig
import com.github.kr328.clash.core.model.EffectiveLine
import com.github.kr328.clash.design.R

/**
 * CUSTOM (CUSTOM.md #30, not upstream): the user's config with what the core and the app did to it marked in place.
 *
 *   plain line      nothing happened to it
 *   blue    ~       a value that was replaced: the old value struck through, the value that runs after it
 *   red     -       a field the core dropped or the app removed, struck through where it stood
 *   green   +       a field that was added, put where it belongs in the config
 *
 * The marks ("~", "-", "+", the strike-through) say the same as the colors, so nothing depends on seeing colors.
 * Long runs of lines that did not change are folded into one line that opens on a tap; the lines that did change
 * keep the lines they sit under (their parents) and one line on each side. The fields the core added only because
 * the user did not write them (its defaults) are hidden until asked for.
 *
 * The user's own blocks come first, in the order and with the indentation they wrote them; what was changed in them
 * stays where it is. The top-level blocks the user did not write at all (the core filled in a whole `tun`, `ntp`,
 * `profile` ...) are collected after the user's config in one section that is closed until tapped; in it only the
 * values a later step changed are shown (with the lines they sit under), unless all the defaults are asked for.
 * All of this happens in memory when the page draws; nothing runs on its own.
 */
class EffectiveText(private val context: Context, private val config: EffectiveConfig) {
    private var showKernel = false
    private var tailOpen = false // the section of the blocks the core added at the end
    private val expanded = HashSet<Int>() // folds the user opened, by the position of their first line

    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val colorChanged = if (night) Color.parseColor("#64B5F6") else Color.parseColor("#1565C0")
    private val colorRemoved = if (night) Color.parseColor("#EF9A9A") else Color.parseColor("#C62828")
    private val colorAdded = if (night) Color.parseColor("#81C784") else Color.parseColor("#2E7D32")
    private val colorMuted = Color.parseColor("#9E9E9E")

    private fun hiddenByDefault(l: EffectiveLine): Boolean {
        return l.status == EffectiveLine.ADDED && l.source == EffectiveLine.SRC_KERNEL
    }

    // who is under whom: the nearest line before this one that is indented less (-1: nobody)
    private fun parentsOf(lines: List<EffectiveLine>): IntArray {
        val parent = IntArray(lines.size) { -1 }
        val stack = ArrayList<Int>()

        for (i in lines.indices) {
            while (stack.isNotEmpty() && lines[stack[stack.size - 1]].indent >= lines[i].indent) {
                stack.removeAt(stack.size - 1)
            }

            parent[i] = if (stack.isEmpty()) -1 else stack[stack.size - 1]
            stack.add(i)
        }

        return parent
    }

    // A top-level block the user did not write: the core (not the override, not the app) brought the whole of it.
    private fun isKernelRoot(l: EffectiveLine): Boolean {
        return l.indent == 0 && l.status == EffectiveLine.ADDED && l.source == EffectiveLine.SRC_KERNEL
    }

    // The user's blocks in the order they were written, and the blocks the core added by itself.
    private val main: List<EffectiveLine>
    private val tail: List<EffectiveLine>

    init {
        val m = ArrayList<EffectiveLine>()
        val t = ArrayList<EffectiveLine>()
        var inTail = false

        for (l in config.lines) {
            if (l.indent == 0) inTail = isKernelRoot(l)

            if (inTail) t.add(l) else m.add(l)
        }

        main = m
        tail = t
    }

    private val tailBlocks: Int = tail.count { it.indent == 0 }

    // The core's defaults are hidden, except a block that holds something a later step changed (a default of the
    // core that the app turned off): that value stays, and so do the lines it sits under.
    private fun visibleOf(lines: List<EffectiveLine>): List<EffectiveLine> {
        if (showKernel) return lines

        val parent = parentsOf(lines)
        val show = BooleanArray(lines.size)

        for (i in lines.indices) {
            if (hiddenByDefault(lines[i])) continue

            show[i] = true

            var p = parent[i]

            while (p >= 0 && !show[p]) {
                show[p] = true
                p = parent[p]
            }
        }

        return lines.filterIndexed { i, _ -> show[i] }
    }

    private fun marker(l: EffectiveLine): String {
        return when (l.status) {
            EffectiveLine.CHANGED -> "~ "
            EffectiveLine.REMOVED -> "- "
            EffectiveLine.ADDED -> "+ "
            else -> "  "
        }
    }

    private fun tag(l: EffectiveLine): String {
        val id = when (l.source) {
            EffectiveLine.SRC_OVERRIDE -> R.string.eff_tag_override
            EffectiveLine.SRC_APP -> R.string.eff_tag_app
            else -> when (l.status) {
                EffectiveLine.REMOVED -> R.string.eff_tag_kernel_ignored
                EffectiveLine.ADDED -> R.string.eff_tag_kernel_default
                else -> R.string.eff_tag_kernel_changed
            }
        }

        return "  " + context.getString(id)
    }

    /** The whole config as text, every line, with the current choice about the core's defaults. */
    fun plain(): String {
        val sb = StringBuilder()

        plainLines(sb, visibleOf(main))

        if (tail.isNotEmpty() && tailOpen) {
            sb.append('\n').append("# ").append(context.getString(R.string.eff_tail_title)).append('\n')
            plainLines(sb, visibleOf(tail))
        }

        return sb.toString()
    }

    private fun plainLines(sb: StringBuilder, lines: List<EffectiveLine>) {
        for (l in lines) {
            sb.append(marker(l)).append(" ".repeat(l.indent)).append(l.pre)

            if (l.anchor) {
                sb.append("  ").append(context.getString(R.string.eff_anchor)).append('\n')

                continue
            }

            if (l.status == EffectiveLine.CHANGED) {
                sb.append(l.old).append(" -> ")
            }

            sb.append(l.value)

            if (l.status != EffectiveLine.SAME) sb.append(tag(l))

            sb.append('\n')
        }
    }

    /** The page text. [onChange] is called after a tap changed what is shown; the caller draws again. */
    fun render(onChange: () -> Unit): CharSequence {
        val sb = SpannableStringBuilder()

        header(sb, onChange)

        body(sb, visibleOf(main), 0, onChange)

        if (tail.isNotEmpty()) tailSection(sb, onChange)

        // the font is one for everything, so the indentation lines up
        sb.setSpan(TypefaceSpan("monospace"), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        return sb
    }

    // [foldBase] keeps the folds of the two parts apart: a fold is known by the position of its first line
    private fun body(sb: SpannableStringBuilder, lines: List<EffectiveLine>, foldBase: Int, onChange: () -> Unit) {
        val parent = parentsOf(lines)

        // what stays visible: the changed lines, what they sit under, and one line on each side
        val keep = BooleanArray(lines.size)

        for (i in lines.indices) {
            if (lines[i].status == EffectiveLine.SAME) continue

            keep[i] = true

            if (i > 0) keep[i - 1] = true
            if (i + 1 < lines.size) keep[i + 1] = true

            var p = parent[i]

            while (p >= 0 && !keep[p]) {
                keep[p] = true
                p = parent[p]
            }
        }

        var i = 0

        while (i < lines.size) {
            if (keep[i] || lines[i].status != EffectiveLine.SAME) {
                line(sb, lines, parent, i)
                i++

                continue
            }

            var end = i

            while (end < lines.size && !keep[end]) end++

            val count = end - i

            if (count < 3 || expanded.contains(foldBase + i)) {
                for (k in i until end) line(sb, lines, parent, k)
            } else {
                fold(sb, foldBase + i, count, onChange)
            }

            i = end
        }
    }

    // The blocks the core added by itself, after the user's config; closed until tapped.
    private fun tailSection(sb: SpannableStringBuilder, onChange: () -> Unit) {
        sb.append('\n')

        val at = sb.length

        sb.append(context.getString(if (tailOpen) R.string.eff_tail_hide else R.string.eff_tail_show, tailBlocks))
        sb.setSpan(click {
            tailOpen = !tailOpen
            onChange()
        }, at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n')

        if (!tailOpen) return

        val lines = visibleOf(tail)

        if (lines.isEmpty()) {
            val noteAt = sb.length

            sb.append(context.getString(R.string.eff_tail_empty)).append('\n')
            sb.setSpan(ForegroundColorSpan(colorMuted), noteAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            return
        }

        body(sb, lines, TAIL_FOLD_BASE, onChange)
    }

    private fun header(sb: SpannableStringBuilder, onChange: () -> Unit) {
        sb.append(context.getString(R.string.eff_summary, config.changed, config.removed, config.added)).append('\n')

        // the legend: what each mark and color means
        val changedAt = sb.length
        sb.append("~ ").append(context.getString(R.string.eff_legend_changed))
        sb.setSpan(ForegroundColorSpan(colorChanged), changedAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("   ")

        val removedAt = sb.length
        sb.append("- ").append(context.getString(R.string.eff_legend_removed))
        sb.setSpan(ForegroundColorSpan(colorRemoved), removedAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StrikethroughSpan(), removedAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("   ")

        val addedAt = sb.length
        sb.append("+ ").append(context.getString(R.string.eff_legend_added))
        sb.setSpan(ForegroundColorSpan(colorAdded), addedAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n')

        if (config.addedKernel > 0) {
            val at = sb.length

            sb.append(
                context.getString(
                    if (showKernel) R.string.eff_hide_kernel else R.string.eff_show_kernel,
                    config.addedKernel,
                ),
            )
            sb.setSpan(click {
                showKernel = !showKernel
                if (showKernel) tailOpen = true // the defaults it asks for are mostly in that section
                expanded.clear()
                onChange()
            }, at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
        }

        if (config.changed + config.removed + config.added == 0) {
            sb.append(context.getString(R.string.eff_no_diff)).append('\n')
        }

        val noteAt = sb.length

        sb.append(context.getString(R.string.eff_note)).append('\n')
        sb.setSpan(ForegroundColorSpan(colorMuted), noteAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.85f), noteAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        sb.append('\n')
    }

    private fun fold(sb: SpannableStringBuilder, at: Int, count: Int, onChange: () -> Unit) {
        val start = sb.length

        sb.append("  ").append(context.getString(R.string.eff_fold, count))
        sb.setSpan(click {
            expanded.add(at)
            onChange()
        }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(colorMuted), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n')
    }

    private fun line(sb: SpannableStringBuilder, lines: List<EffectiveLine>, parent: IntArray, i: Int) {
        val l = lines[i]
        val start = sb.length

        sb.append(marker(l)).append(" ".repeat(l.indent)).append(l.pre)

        if (l.anchor) {
            // an anchor definition the core ignores: one muted line, what it holds is used where it is pointed at
            val noteAt = sb.length

            sb.append("  ").append(context.getString(R.string.eff_anchor)).append('\n')
            sb.setSpan(ForegroundColorSpan(colorMuted), noteAt, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            return
        }

        when (l.status) {
            EffectiveLine.CHANGED -> {
                val oldAt = sb.length

                sb.append(l.old)
                sb.setSpan(ForegroundColorSpan(colorMuted), oldAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StrikethroughSpan(), oldAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(' ')

                val newAt = sb.length

                sb.append(l.value)
                sb.setSpan(ForegroundColorSpan(colorChanged), newAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), newAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(colorChanged), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            EffectiveLine.REMOVED -> {
                sb.append(l.value)
                sb.setSpan(ForegroundColorSpan(colorRemoved), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StrikethroughSpan(), start + 2 + l.indent, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            EffectiveLine.ADDED -> {
                sb.append(l.value)
                sb.setSpan(ForegroundColorSpan(colorAdded), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            else -> sb.append(l.value)
        }

        // who did it is written once for a block, not on every line of it
        if (l.status != EffectiveLine.SAME) {
            val p = if (parent[i] >= 0) lines[parent[i]] else null
            val root = p == null || p.status != l.status || p.source != l.source

            if (root || l.status == EffectiveLine.CHANGED) {
                val tagAt = sb.length

                sb.append(tag(l))
                sb.setSpan(ForegroundColorSpan(colorMuted), tagAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.85f), tagAt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        sb.append('\n')
    }

    private fun click(action: () -> Unit): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) {
                action()
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.isUnderlineText = true
            }
        }
    }

    private companion object {
        const val TAIL_FOLD_BASE = 1_000_000
    }
}
