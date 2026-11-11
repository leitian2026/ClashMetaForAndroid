package com.github.kr328.clash.design.custom

import com.github.kr328.clash.core.model.CrashRecord
import com.github.kr328.clash.core.model.ErrorSnapshot
import com.github.kr328.clash.core.model.ExitRecord

/**
 * CUSTOM (CUSTOM.md #21): everything the errors page shows. [snapshot] / [timeouts] are null while the
 * proxy is not running or the core could not be asked.
 *
 * CUSTOM (CUSTOM.md #23, #24): [ipv6Skipped] are IPv6-only nodes whose speed test timed out while the network has
 * no IPv6 (expected, not counted as timeouts); [testedAt] / [networkChangedAt] (unix ms, 0 = unknown) tell how
 * old the speed-test result is and whether the network changed after it.
 */
data class ErrorsReport(
    val running: Boolean,
    val snapshot: ErrorSnapshot?,
    val timeouts: List<String>?,
    val crashes: List<CrashRecord>,
    val exits: List<ExitRecord>,
    val ipv6Skipped: List<String> = emptyList(),
    val testedAt: Long = 0,
    val networkChangedAt: Long = 0,
)
