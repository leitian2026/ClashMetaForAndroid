package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.core.custom.IpHistory
import com.github.kr328.clash.core.custom.IpHistoryEntry
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.custom.NetExtra
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// CUSTOM (CUSTOM.md #33): the two things the "network status" card on the home page needs besides the
// tunnel state: the last delay of the node in use, and the last two IP switches. Both are things the
// app already has (the last speed test, the history file of #10); nothing is measured here. Called
// whenever the home page refreshes (opening it, the core starting / stopping, the node IP changing);
// no timer.
suspend fun loadNetExtra(context: Context, running: Boolean, node: String): NetExtra? {
    if (!running || node.isBlank()) return null

    val delay = try {
        // the first group that lists the node (the global group lists all of them)
        withClash {
            var found: Int? = null

            for (group in queryProxyGroupNames(false)) {
                val proxy = queryProxyGroup(group, ProxySort.Default).proxies
                    .firstOrNull { it.name == node && !it.isGroup }

                if (proxy != null) {
                    found = proxy.delay.takeIf { it > 0 }

                    break
                }
            }

            found
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    val history: List<IpHistoryEntry> = withContext(Dispatchers.IO) {
        try {
            val file = context.filesDir.resolve("custom").resolve("ip_history.json")

            if (file.isFile) IpHistory.parse(file.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    return NetExtra(delay, history.lastOrNull(), history.getOrNull(history.size - 2))
}
