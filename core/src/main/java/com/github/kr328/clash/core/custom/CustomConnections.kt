package com.github.kr328.clash.core.custom

// CUSTOM (CUSTOM.md #8, #15): the service process asks the core for its active connections.
object CustomConnections {
    const val SORT_TIME = 0 // newest first
    const val SORT_TRAFFIC = 1 // most data (upload + download) first
    const val SORT_MUX = 2 // CUSTOM #20: not a list, the per-node socket summary (see MuxSnapshot)

    const val SORT_ERRORS = 3 // CUSTOM #21: not a list, the recent warnings and errors (see ErrorSnapshot)

    const val SORT_CHECK = 4 // CUSTOM #22: not a list, runs the config check; the filter is its JSON request

    // id that close() takes to forget the recorded errors (CUSTOM #21)
    const val CLEAR_ERRORS = "!errors"

    // id that close() takes to close every connection
    const val CLOSE_ALL = "*"

    // JSON, see ConnectionSnapshot. [filter]: only connections containing every word of it
    fun query(sort: Int, filter: String): String = CustomBridge.nativeQueryConnections(sort, filter)

    // false when the connection is already gone
    fun close(id: String): Boolean = CustomBridge.nativeCloseConnection(id)
}
