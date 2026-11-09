package com.github.kr328.clash.core.custom

// CUSTOM (CUSTOM.md #8): the service process asks the core for its active connections.
object CustomConnections {
    // JSON, see ConnectionSnapshot
    fun query(): String = CustomBridge.nativeQueryConnections()

    // false when the connection is already gone
    fun close(id: String): Boolean = CustomBridge.nativeCloseConnection(id)
}
