package com.github.kr328.clash.design.custom

/** CUSTOM (CUSTOM.md #159/#164): one selected speed column and one direction. */
internal enum class SavedIpSort {
    DEFAULT,
    SPEED_DIRECT_FASTEST,
    SPEED_DIRECT_SLOWEST,
    SPEED_NODE_FASTEST,
    SPEED_NODE_SLOWEST;

    val columnName: String?
        get() = when (this) {
            SPEED_DIRECT_FASTEST, SPEED_DIRECT_SLOWEST -> "SPEED"
            SPEED_NODE_FASTEST, SPEED_NODE_SLOWEST -> "SPEED_NODE"
            DEFAULT -> null
        }

    val isFastest: Boolean
        get() = this == SPEED_DIRECT_FASTEST || this == SPEED_NODE_FASTEST

    companion object {
        fun forColumn(columnName: String, fastest: Boolean): SavedIpSort = when (columnName) {
            "SPEED" -> if (fastest) SPEED_DIRECT_FASTEST else SPEED_DIRECT_SLOWEST
            "SPEED_NODE" -> if (fastest) SPEED_NODE_FASTEST else SPEED_NODE_SLOWEST
            else -> DEFAULT
        }
    }
}

/** Sort by the selected speed column only; zero values stay last and ties remain stable. */
internal fun sortSavedIpEntries(
    defaultOrderedEntries: List<SavedIpEntry>,
    sort: SavedIpSort,
): List<SavedIpEntry> {
    if (sort == SavedIpSort.DEFAULT) return defaultOrderedEntries

    return defaultOrderedEntries.withIndex().sortedWith { left, right ->
        val leftValue = when (sort.columnName) {
            "SPEED" -> left.value.speedDirect
            "SPEED_NODE" -> left.value.speedNode
            else -> 0.0
        }
        val rightValue = when (sort.columnName) {
            "SPEED" -> right.value.speedDirect
            "SPEED_NODE" -> right.value.speedNode
            else -> 0.0
        }
        val leftMissing = leftValue <= 0.0
        val rightMissing = rightValue <= 0.0
        when {
            leftMissing != rightMissing -> if (leftMissing) 1 else -1
            !leftMissing && leftValue != rightValue -> {
                if (sort.isFastest) rightValue.compareTo(leftValue)
                else leftValue.compareTo(rightValue)
            }
            else -> left.index.compareTo(right.index)
        }
    }.map { it.value }
}
