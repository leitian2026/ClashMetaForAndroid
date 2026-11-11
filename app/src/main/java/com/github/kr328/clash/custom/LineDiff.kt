package com.github.kr328.clash.custom

// CUSTOM (CUSTOM.md #28, not upstream): a plain line diff for the config history page. The common start and end
// of the two texts are cut off first (edits are usually small), the rest is compared with the longest common
// subsequence. When the changed middle is too big for that (more than LIMIT line pairs) it is shown as all
// removed, then all added. Pure Kotlin, no Android classes.
object LineDiff {
    private const val LIMIT = 6_000_000L

    /** [text] is the unified-style diff (empty when nothing differs), [truncated] counts the lines left out. */
    class Result(val text: String, val added: Int, val removed: Int, val truncated: Int)

    private class Op(val kind: Char, val text: String, val oldNo: Int)

    fun diff(old: List<String>, new: List<String>, context: Int = 2, maxLines: Int = 300): Result {
        var head = 0

        while (head < old.size && head < new.size && old[head] == new[head]) head++

        var tail = 0

        while (tail < old.size - head && tail < new.size - head &&
            old[old.size - 1 - tail] == new[new.size - 1 - tail]
        ) {
            tail++
        }

        val a = old.subList(head, old.size - tail)
        val b = new.subList(head, new.size - tail)

        if (a.isEmpty() && b.isEmpty()) return Result("", 0, 0, 0)

        val ops = ArrayList<Op>()

        for (i in maxOf(0, head - context) until head) ops.add(Op(' ', old[i], i + 1))

        if (a.size.toLong() * b.size > LIMIT) {
            a.forEachIndexed { i, t -> ops.add(Op('-', t, head + i + 1)) }
            b.forEach { ops.add(Op('+', it, head + a.size + 1)) }
        } else {
            val w = b.size + 1
            val t = ShortArray((a.size + 1) * w)

            for (i in a.size - 1 downTo 0) {
                for (j in b.size - 1 downTo 0) {
                    t[i * w + j] = if (a[i] == b[j]) {
                        (t[(i + 1) * w + j + 1] + 1).toShort()
                    } else {
                        maxOf(t[(i + 1) * w + j], t[i * w + j + 1])
                    }
                }
            }

            var i = 0
            var j = 0

            while (i < a.size && j < b.size) {
                when {
                    a[i] == b[j] -> {
                        ops.add(Op(' ', a[i], head + i + 1))
                        i++
                        j++
                    }
                    t[(i + 1) * w + j] >= t[i * w + j + 1] -> {
                        ops.add(Op('-', a[i], head + i + 1))
                        i++
                    }
                    else -> {
                        ops.add(Op('+', b[j], head + i + 1))
                        j++
                    }
                }
            }

            while (i < a.size) {
                ops.add(Op('-', a[i], head + i + 1))
                i++
            }

            while (j < b.size) {
                ops.add(Op('+', b[j], head + i + 1))
                j++
            }
        }

        for (k in 0 until minOf(context, tail)) {
            val index = old.size - tail + k

            ops.add(Op(' ', old[index], index + 1))
        }

        val keep = BooleanArray(ops.size)

        ops.forEachIndexed { index, op ->
            if (op.kind != ' ') {
                for (k in maxOf(0, index - context)..minOf(ops.size - 1, index + context)) keep[k] = true
            }
        }

        val out = StringBuilder()
        var lines = 0
        var truncated = 0

        ops.forEachIndexed { index, op ->
            if (!keep[index]) return@forEachIndexed

            if (lines >= maxLines) {
                truncated++

                return@forEachIndexed
            }

            if (index == 0 || !keep[index - 1]) {
                if (out.isNotEmpty()) out.append('\n')

                out.append("@@ ").append(op.oldNo).append(" @@\n")
            }

            out.append(op.kind).append(' ').append(op.text).append('\n')
            lines++
        }

        return Result(
            out.toString().trimEnd('\n'),
            ops.count { it.kind == '+' },
            ops.count { it.kind == '-' },
            truncated,
        )
    }
}
