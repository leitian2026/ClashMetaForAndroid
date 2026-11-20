package com.github.kr328.clash.design.custom

import com.github.kr328.clash.core.model.IpTestRow

/**
 * CUSTOM.md #166: local, stable ranking shared by the live test table and saved-IP groups.
 * The loss percentage is compared at its stored integer precision (no extra bucket); average latency
 * uses 20 ms buckets, followed by lower jitter and higher download speed.
 */
internal object IpRecommend {
    private const val MAX_RECOMMENDATIONS = 3
    private const val SAVED_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    fun forTest(rows: List<IpTestRow>): Set<String> =
        top(rows.filter { it.ok && it.sent > 0 })

    fun forSaved(entries: List<SavedIpEntry>, now: Long): Set<String> =
        top(entries.asSequence()
            .filter { entry ->
                entry.failStreak == 0 &&
                    entry.lastTestedAt > 0L &&
                    now - entry.lastTestedAt <= SAVED_MAX_AGE_MS
            }
            .mapNotNull { it.result }
            .filter { it.ok && it.sent > 0 }
            .toList())

    private fun top(rows: List<IpTestRow>): Set<String> =
        rows.asSequence()
            .filter { it.ip.isNotBlank() }
            .sortedWith(
                compareBy<IpTestRow> { it.loss }
                    .thenBy { it.avg / 20 }
                    .thenBy { it.jitter }
                    .thenByDescending { it.speed },
            )
            .take(MAX_RECOMMENDATIONS)
            .mapTo(LinkedHashSet()) { it.ip }
}
