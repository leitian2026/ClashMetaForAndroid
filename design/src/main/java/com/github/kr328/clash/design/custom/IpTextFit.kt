package com.github.kr328.clash.design.custom

object IpTextFit {
    data class Result(val text: String, val textSize: Float)

    fun fit(
        text: String,
        availableWidth: Float,
        baseTextSize: Float,
        minTextSize: Float,
        measureText: (String, Float) -> Float,
    ): Result {
        val base = baseTextSize.coerceAtLeast(1f)
        val minimum = minTextSize.coerceIn(1f, base)
        if (text.isEmpty() || availableWidth <= 0f) return Result("", minimum)
        if (measureText(text, base) <= availableWidth) return Result(text, base)

        if (measureText(text, minimum) <= availableWidth) {
            var low = minimum
            var high = base
            repeat(12) {
                val middle = (low + high) / 2f
                if (measureText(text, middle) <= availableWidth) low = middle else high = middle
            }
            return Result(text, low)
        }

        return Result(middleEllipsis(text, availableWidth, minimum, measureText), minimum)
    }

    private fun middleEllipsis(
        text: String,
        availableWidth: Float,
        textSize: Float,
        measureText: (String, Float) -> Float,
    ): String {
        val boundaries = (0..text.length).filter { index ->
            index == 0 || index == text.length || text[index - 1] == ':' || text[index] == ':'
        }.distinct()
        var best = "…"
        var bestLength = -1

        for (leftEnd in boundaries) {
            for (rightStart in boundaries) {
                if (rightStart < leftEnd) continue
                val candidate = text.substring(0, leftEnd) + "…" + text.substring(rightStart)
                val kept = leftEnd + text.length - rightStart
                if (kept > bestLength && measureText(candidate, textSize) <= availableWidth) {
                    best = candidate
                    bestLength = kept
                }
            }
        }

        if (bestLength >= 0) return best
        return if (measureText("…", textSize) <= availableWidth) "…" else ""
    }
}
