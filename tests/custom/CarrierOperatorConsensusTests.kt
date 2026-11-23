package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.common.custom.CarrierOperatorConsensus
import com.github.kr328.clash.common.custom.CarrierOperatorVote

object CarrierOperatorConsensusTests {
    fun run() {
        val twoTelecom = listOf(
            CarrierOperatorVote("ipip", "电信"),
            CarrierOperatorVote("pconline", "电信"),
            CarrierOperatorVote("bilibili", "联通"),
        )
        val telecom = CarrierOperatorConsensus.resolve(twoTelecom)
        check(telecom?.operator == "电信", "two matching providers beat one different provider")
        check(telecom?.sources == listOf("ipip", "pconline"), "consensus records agreeing providers")

        check(
            CarrierOperatorConsensus.resolve(
                listOf(CarrierOperatorVote("ipip", "移动"), CarrierOperatorVote("bilibili", "移动")),
            )?.operator == "移动",
            "two matching providers are sufficient when the third request fails",
        )
        check(
            CarrierOperatorConsensus.resolve(listOf(CarrierOperatorVote("ipip", "电信"))) == null,
            "one provider is not enough",
        )
        val disagreement = listOf(
            CarrierOperatorVote("ipip", "电信"),
            CarrierOperatorVote("pconline", "联通"),
        )
        check(CarrierOperatorConsensus.resolve(disagreement) == null, "two different providers have no consensus")
        check(CarrierOperatorConsensus.hasDisagreement(disagreement), "different known operators are reported")
        check(
            CarrierOperatorConsensus.resolve(
                listOf(
                    CarrierOperatorVote("ipip", "电信"),
                    CarrierOperatorVote("pconline", "未知"),
                    CarrierOperatorVote("bilibili", "移动"),
                ),
            ) == null,
            "unknown result does not count as a vote",
        )
        check(
            CarrierOperatorConsensus.resolve(
                listOf(
                    CarrierOperatorVote("ipip", "广电"),
                    CarrierOperatorVote("pconline", "广电"),
                    CarrierOperatorVote("bilibili", "未知"),
                ),
            )?.operator == "广电",
            "unknown third result does not invalidate two matching known results",
        )
        check(
            CarrierOperatorConsensus.resolve(
                listOf(
                    CarrierOperatorVote("ipip", "电信"),
                    CarrierOperatorVote("pconline", "联通"),
                    CarrierOperatorVote("bilibili", "移动"),
                ),
            ) == null,
            "three different operators have no consensus",
        )
    }
}
