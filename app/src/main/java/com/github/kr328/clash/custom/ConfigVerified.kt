package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.service.custom.ConfigHistory
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #38, not upstream): when the home page gets "the node in use works" (CUSTOM.md #37), the
// version of the active profile's config.yaml that is in use is marked as worked in the config history (the
// list shows "✓ worked" next to it, so a rollback can go back to a version that is known to work).
//
// Power: nothing runs on its own. It is called with the answer the home page reads anyway, does one small
// file comparison, and is skipped while the config file has not changed since the last mark.
object ConfigVerified {
    private var lastKey = ""

    suspend fun mark(context: Context) {
        val active = withProfile { queryActive() } ?: return
        val file = ConfigHistory.current(context, active.uuid) ?: return
        val key = "${active.uuid}:${file.lastModified()}:${file.length()}"

        if (key == lastKey) return

        withContext(Dispatchers.IO) { ConfigHistory.markVerified(context, active.uuid) }

        lastKey = key
    }
}
