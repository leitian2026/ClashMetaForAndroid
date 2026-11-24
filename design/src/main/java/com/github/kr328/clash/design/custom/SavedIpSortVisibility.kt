package com.github.kr328.clash.design.custom

/** Returns the sort to apply without mutating the persisted sort preference. */
internal fun effectiveSavedIpSortName(sortName: String, showDirectSpeed: Boolean): String =
    if (!showDirectSpeed && (sortName == "SPEED_DIRECT_FASTEST" || sortName == "SPEED_DIRECT_SLOWEST")) {
        "DEFAULT"
    } else {
        sortName
    }
