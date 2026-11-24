package com.github.kr328.clash.service.custom

import android.content.Context
import com.github.kr328.clash.common.custom.ConfigProxyCount
import com.github.kr328.clash.common.custom.UpdateGuardRecord
import com.github.kr328.clash.common.custom.UpdateGuardStore
import com.github.kr328.clash.service.util.importedDir
import java.util.UUID

/** CUSTOM #173: compare a subscription's previous and newly imported proxy counts off the profile lock. */
object UpdateGuard {
    fun afterUpdateAsync(context: Context, uuid: UUID) {
        Thread({
            try {
                val previous = ConfigUpdateSnapshot.read(context, uuid)
                val currentFile = context.importedDir.resolve("$uuid/config.yaml")
                if (previous == null || !currentFile.isFile ||
                    currentFile.length() <= 0L || currentFile.length() > ConfigUpdateSnapshot.MAX_BYTES
                ) {
                    UpdateGuardStore.delete(context, uuid)
                    return@Thread
                }

                val current = currentFile.readText(Charsets.UTF_8)
                val oldCount = ConfigProxyCount.count(previous)
                val newCount = ConfigProxyCount.count(current)
                if (ConfigProxyCount.dropped(oldCount, newCount)) {
                    UpdateGuardStore.write(
                        context,
                        uuid,
                        UpdateGuardRecord(
                            time = System.currentTimeMillis(),
                            oldCount = oldCount,
                            newCount = newCount,
                        ),
                    )
                } else {
                    UpdateGuardStore.delete(context, uuid)
                }
            } catch (_: Exception) {
                // CUSTOM #173: this diagnostic must never affect a successful subscription update.
            }
        }, "custom-update-guard").apply {
            isDaemon = true
            start()
        }
    }
}
