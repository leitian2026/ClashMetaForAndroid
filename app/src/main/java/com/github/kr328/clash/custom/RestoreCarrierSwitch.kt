package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** CUSTOM (#17/#169): restore mappings are applied after restore, unless profile loading will do it. */
internal object RestoreCarrierSwitch {
    suspend fun restoreAndApply(
        context: Context,
        scope: CoroutineScope,
        clashRunning: Boolean,
        restore: suspend () -> BackupManager.RestoreResult,
    ): BackupManager.RestoreResult {
        val result = restore()
        if (RestoreCarrierSwitchPolicy.shouldApplyAfterRestore(result.configurationReloadRequested)) {
            scope.launch(Dispatchers.IO) {
                withClash { applyCarrierSwitch() }
            }
        }
        return result
    }
}
