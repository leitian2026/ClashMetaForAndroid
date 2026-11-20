package com.github.kr328.clash.design.custom

/** Ordering modes for the saved-IP list. The choice belongs to the current page only. */
internal enum class SavedIpSort {
    DEFAULT,
    AVG_ASCENDING,
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
            SavedIpSort.AVG_ASCENDING -> left.value.avg.toDouble()
            SavedIpSort.SPEED_DESCENDING -> left.value.speed
            SavedIpSort.DEFAULT -> 0.0
        }
        val rightValue = when (sort) {
            SavedIpSort.AVG_ASCENDING -> right.value.avg.toDouble()
            SavedIpSort.SPEED_DESCENDING -> right.value.speed
            SavedIpSort.DEFAULT -> 0.0
        }
        val leftMissing = leftValue <= 0.0
        val rightMissing = rightValue <= 0.0
        when {
            leftMissing != rightMissing -> if (leftMissing) 1 else -1
            !leftMissing && leftValue != rightValue -> {
                if (sort == SavedIpSort.AVG_ASCENDING) leftValue.compareTo(rightValue)
                else rightValue.compareTo(leftValue)
            }
            else -> left.index.compareTo(right.index)
        }
    }.map { it.value }
}
