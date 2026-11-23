package com.github.kr328.clash.common.custom

/** Atomic cross-process snapshot: service writes; the app process reads. */
data class NetworkIdentitySnapshot(
    val signature: String,
    val operator: String = "",
    val province: String = "",
    val city: String = "",
    val error: String = "",
    val source: String = "",
    val checkedAt: Long = 0L,
    val rawOperator: String = "",
    val diagnostic: String = "",
    val switchResult: String = "",
    val switchError: String = "",
    val switchNotice: String = "",
) {
    val hasIdentity: Boolean get() = operator.isNotEmpty() || province.isNotEmpty() || city.isNotEmpty()
    val hasNetworkError: Boolean get() = error.isNotEmpty() || switchError.isNotEmpty()
}
