package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.design.custom.effectiveSavedIpSortName

object SavedIpSortVisibilityTests {
    fun run() {
        check(effectiveSavedIpSortName("SPEED_DIRECT_FASTEST", false) == "DEFAULT", "hidden direct-speed fastest sort falls back to default")
        check(effectiveSavedIpSortName("SPEED_DIRECT_SLOWEST", false) == "DEFAULT", "hidden direct-speed slowest sort falls back to default")
        check(effectiveSavedIpSortName("SPEED_NODE_FASTEST", false) == "SPEED_NODE_FASTEST", "hidden direct speed preserves node-speed sort")
        check(effectiveSavedIpSortName("SPEED_NODE_SLOWEST", false) == "SPEED_NODE_SLOWEST", "hidden direct speed preserves node-speed slowest sort")
        check(
            effectiveSavedIpSortName("SPEED_DIRECT_FASTEST", true) == "SPEED_DIRECT_FASTEST" &&
                effectiveSavedIpSortName("SPEED_NODE_FASTEST", true) == "SPEED_NODE_FASTEST",
            "visible direct speed preserves both speed sorts",
        )
        check(effectiveSavedIpSortName("DEFAULT", false) == "DEFAULT", "default sort remains unchanged")
    }
}
