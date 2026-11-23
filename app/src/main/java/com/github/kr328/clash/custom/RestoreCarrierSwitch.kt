package com.github.kr328.clash.custom

import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restore mappings after completion when a profile load will not apply them. */
internal object RestoreCarrierSwitch {
    suspend fun restoreAndApply(
        restore: suspend () -> BackupManager.RestoreResult,
    ): BackupManager.RestoreResult {
        val result = restore()
        if (RestoreCarrierSwitchPolicy.shouldApplyAfterRestore(result.configurationReloadRequested)) {
            CoroutineScope(Dispatchers.IO).launch {
                withClash { applyCarrierSwitch() }
            }
        }
        return result
    }
}
