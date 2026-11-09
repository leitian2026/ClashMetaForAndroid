package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.format
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.service.model.Profile
import java.util.Date

// CUSTOM (CUSTOM.md #9): remaining data and expiry of the active subscription, appended to the
// subtext of the profile card on the home page. The numbers are what the subscription server sent
// (header `subscription-userinfo`) when the profile was last updated and are stored with the profile,
// so building this text needs no network and no extra work.
object ProfileUsage {
    private const val DAY = 24L * 60 * 60 * 1000

    /** "\n<remaining> · <expiry>", or null when the profile carries neither. */
    fun text(context: Context, profile: Profile?): String? {
        if (profile == null) return null

        val parts = ArrayList<String>(2)

        if (profile.total > 0) {
            val left = profile.total - profile.upload - profile.download

            parts.add(
                if (left > 0) {
                    context.getString(R.string.main_profile_left, left.toBytesString())
                } else {
                    context.getString(R.string.main_profile_used_up)
                }
            )
        }

        if (profile.expire > 0) {
            val now = System.currentTimeMillis()
            val date = Date(profile.expire).format(context, includeDate = true, includeTime = false)

            parts.add(
                if (profile.expire > now) {
                    // rounded up, so the last day still reads "1 day left"
                    val days = (profile.expire - now + DAY - 1) / DAY

                    context.getString(R.string.main_profile_expire, date, days.toInt())
                } else {
                    context.getString(R.string.main_profile_expired, date)
                }
            )
        }

        if (parts.isEmpty()) return null

        return "\n" + parts.joinToString(" · ")
    }
}
