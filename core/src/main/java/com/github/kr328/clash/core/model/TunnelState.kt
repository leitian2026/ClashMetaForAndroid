package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.CountryFlag
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TunnelState(
    val mode: Mode,
    // CUSTOM (CUSTOM.md #7): node IP the core dialed most recently, "" if none yet
    val currentNode: String = "",
    val currentIp: String = "",
    val currentCountry: String = "",
    // CUSTOM (CUSTOM.md #7): every address of the node that requests went through most recently
    // (most recent first, at most 3), and how many more were left out; empty when none is known
    val currentIps: List<CurrentIp> = emptyList(),
    val currentMore: Int = 0,
) : Parcelable {
    @Serializable
    data class CurrentIp(
        val ip: String,
        val country: String = "",
    )

    /**
     * CUSTOM (CUSTOM.md #7): "🇺🇸 1.2.3.4 · 🇯🇵 5.6.7.8" for the home page and the notification; with
     * nothing known the single address, "" when there is none. [more] words "N more were left out".
     */
    fun ipsText(more: (Int) -> String): String {
        val list = when {
            currentIps.isNotEmpty() -> currentIps
            currentIp.isNotEmpty() -> listOf(CurrentIp(currentIp, currentCountry))
            else -> return ""
        }

        val text = list.joinToString(" · ") { "${CountryFlag.of(it.country).ifEmpty { "？" }} ${it.ip}" }

        return if (currentMore > 0) "$text ${more(currentMore)}" else text
    }

    @Serializable
    enum class Mode {
        @SerialName("direct")
        Direct,

        @SerialName("global")
        Global,

        @SerialName("rule")
        Rule,

        @SerialName("script")
        Script,
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<TunnelState> {
        override fun createFromParcel(parcel: Parcel): TunnelState {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<TunnelState?> {
            return arrayOfNulls(size)
        }
    }
}