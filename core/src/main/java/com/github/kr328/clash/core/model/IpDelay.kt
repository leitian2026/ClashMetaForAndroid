package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

/**
 * CUSTOM (not upstream): delay of one address behind a node.
 * delay > 0: milliseconds, 0: failed / timeout.
 */
@Serializable
data class IpDelay(
    val ip: String,
    val delay: Int,
)
