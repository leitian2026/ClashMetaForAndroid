package com.github.kr328.clash.design.custom

import android.content.Context
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot
import com.github.kr328.clash.design.R

// CUSTOM (CUSTOM.md #168): shared formatting for the home card and diagnostic report.
object NetworkIdentityText {
    fun location(identity: NetworkIdentitySnapshot): String {
        val province = clean(identity.province)
        val city = clean(identity.city)
        return listOf(province, city, identity.operator).filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { "?" }
    }

    private fun clean(value: String): String = value.trim()
        .removeSuffix("特别行政区")
        .removeSuffix("壮族自治区")
        .removeSuffix("维吾尔自治区")
        .removeSuffix("自治区")
        .removeSuffix("省")
        .removeSuffix("市")

    fun error(context: Context, code: String): String = context.getString(
        when (code) {
            "no_network" -> R.string.network_identity_error_no_network
            "timeout" -> R.string.network_identity_error_timeout
            "operator_mismatch" -> R.string.network_identity_error_mismatch
            "bind_failed" -> R.string.network_identity_error_bind
            "switch_failed" -> R.string.network_identity_error_all_failed
            else -> R.string.network_identity_error_all_failed
        }
    )
}
