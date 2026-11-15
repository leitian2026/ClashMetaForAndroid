package com.github.kr328.clash.core.custom

// CUSTOM (CUSTOM.md #8, #15): the service process asks the core for its active connections.
object CustomConnections {
    const val SORT_TIME = 0 // newest first
    const val SORT_TRAFFIC = 1 // most data (upload + download) first
    const val SORT_MUX = 2 // CUSTOM #20: not a list, the per-node socket summary (see MuxSnapshot)

    const val SORT_ERRORS = 3 // CUSTOM #21: not a list, the recent warnings and errors (see ErrorSnapshot)

    const val SORT_CHECK = 4 // CUSTOM #22: not a list, runs the config check; the filter is its JSON request

    const val SORT_NODEINFO = 5 // CUSTOM #23 #24: not a list, IPv6-only nodes and the last test / network change times (see NodeInfo)

    const val SORT_RETEST = 6 // CUSTOM #24: not a list; the filter is "changed" (network changed) or "run" (latency test of every group)

    const val SORT_VALIDATE = 7 // CUSTOM #27: not a list; the filter is a profile folder, the answer says whether its config.yaml parses

    const val SORT_EFFECTIVE = 8 // CUSTOM #30: not a list; the filter is a profile folder, the answer is its config.yaml with what the core / the app changed marked in place (see EffectiveConfig)

    const val SORT_LOG_LEVEL = 9 // CUSTOM #31: not a list; the filter is a log level ("debug" ...), the core takes it at once

    const val SORT_ISSUES = 10 // CUSTOM #53: not a list; the problems of the running config (see IssuesSnapshot); the filter is "" or a command ("sub|name|reason", "subok|name")

    // ids that close() takes for the problems list (CUSTOM #53)
    const val ISSUE_IGNORE = "!issue-ignore:" // + id
    const val ISSUE_UNIGNORE = "!issue-unignore:" // + id
    const val ISSUE_CLEAR = "!issue-clear"

    // ids that close() takes to clear the core's DNS cache / its fake-ip map (the latter also closes every connection) (CUSTOM #26)
    const val FLUSH_DNS = "!flush-dns"
    const val FLUSH_FAKEIP = "!flush-fakeip"

    // id that close() takes to stop the running "test other IPs" of the config check page (CUSTOM #43)
    const val CANCEL_IPTEST = "!cancel-iptest"

    // id that close() takes to forget the recorded errors (CUSTOM #21)
    const val CLEAR_ERRORS = "!errors"

    // id that close() takes to close every connection
    const val CLOSE_ALL = "*"

    // JSON, see ConnectionSnapshot. [filter]: only connections containing every word of it
    fun query(sort: Int, filter: String): String = CustomBridge.nativeQueryConnections(sort, filter)

    // false when the connection is already gone
    fun close(id: String): Boolean = CustomBridge.nativeCloseConnection(id)
}
