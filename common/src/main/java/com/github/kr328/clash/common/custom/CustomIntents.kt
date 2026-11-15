package com.github.kr328.clash.common.custom

import com.github.kr328.clash.common.util.packageName

// CUSTOM (CUSTOM.md #7): broadcast sent by the service when the node IP in use changed.
object CustomIntents {
    val ACTION_NODE_IP_CHANGED = "$packageName.intent.action.NODE_IP_CHANGED"

    // CUSTOM (CUSTOM.md #53): the list of problems of the running config changed
    val ACTION_ISSUES_CHANGED = "$packageName.intent.action.ISSUES_CHANGED"
}
