package com.github.kr328.clash.design.custom

/** CUSTOM (CUSTOM.md #164): only speed ascending / descending; DEFAULT restores test-time order. */
internal enum class SavedIpSort {
    DEFAULT,
    SPEED_ASCENDING,
    SPEED_DESCENDING,
}

/**
 * Sort entries that are already in the existing default order.
 *
 * Keeping the original index as the final comparison key makes equal metrics stable.
 * Missing (zero) metrics are always placed after entries with a measured value.
 */
internal fun sortSavedIpEntries(
    defaultOrderedEntries: List<SavedIpEntry>,
    sort: SavedIpSort,
): List<SavedIpEntry> {
    if (sort == SavedIpSort.DEFAULT) return defaultOrderedEntries

    return defaultOrderedEntries.withIndex().sortedWith { left, right ->
        val leftValue = when (sort) {
            SavedIpSort.SPEED_ASCENDING, SavedIpSort.SPEED_DESCENDING -> left.value.speed
            SavedIpSort.DEFAULT -> 0.0
        }
        val rightValue = when (sort) {
            SavedIpSort.SPEED_ASCENDING, SavedIpSort.SPEED_DESCENDING -> right.value.speed
            SavedIpSort.DEFAULT -> 0.0
        }
        val leftMissing = leftValue <= 0.0
        val rightMissing = rightValue <= 0.0
        when {
            leftMissing != rightMissing -> if (leftMissing) 1 else -1
            !leftMissing && leftValue != rightValue -> {
                if (sort == SavedIpSort.SPEED_ASCENDING) leftValue.compareTo(rightValue)
                else rightValue.compareTo(leftValue)
            }
            else -> left.index.compareTo(right.index)
        }
    }.map { it.value }
}
