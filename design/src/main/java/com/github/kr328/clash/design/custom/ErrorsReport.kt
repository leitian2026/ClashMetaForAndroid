package com.github.kr328.clash.design.custom

import com.github.kr328.clash.core.model.CrashRecord
import com.github.kr328.clash.core.model.ErrorSnapshot
import com.github.kr328.clash.core.model.ExitRecord

/**
 * CUSTOM (CUSTOM.md #21): everything the errors page shows. [snapshot] / [timeouts] are null while the
 * proxy is not running or the core could not be asked.
 */
data class ErrorsReport(
    val running: Boolean,
    val snapshot: ErrorSnapshot?,
    val timeouts: List<String>?,
    val crashes: List<CrashRecord>,
    val exits: List<ExitRecord>,
)
