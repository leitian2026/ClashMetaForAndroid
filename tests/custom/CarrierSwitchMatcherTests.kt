package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.common.custom.CarrierSwitchKeyword
import com.github.kr328.clash.common.custom.CarrierSwitchMatcher
import com.github.kr328.clash.common.custom.CarrierSwitchTarget
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot

object CarrierSwitchMatcherTests {
    private fun identity(operator: String = "", rawOperator: String = "") = NetworkIdentitySnapshot(
        signature = "test",
        operator = operator,
        rawOperator = rawOperator,
        province = "",
        city = "",
    )

    fun run() {
        val telecom = CarrierSwitchTarget("电信组", "电信节点")
        val unicom = CarrierSwitchTarget("联通组", "联通节点")
        val mobile = CarrierSwitchTarget("移动组", "移动节点")
        val config = CarrierSwitchConfig(telecom = telecom, unicom = unicom, mobile = mobile)

        check(CarrierSwitchMatcher.match(config, identity(operator = "电信")) == telecom, "telecom uses fixed mapping")
        check(CarrierSwitchMatcher.match(config, identity(operator = "联通")) == unicom, "unicom uses fixed mapping")
        check(CarrierSwitchMatcher.match(config, identity(operator = "移动")) == mobile, "mobile uses fixed mapping")

        val broadcast = CarrierSwitchTarget("广电组", "广电节点")
        val broadcastConfig = config.copy(broadcast = broadcast)
        check(
            CarrierSwitchMatcher.match(broadcastConfig, identity(operator = "广电")) == broadcast,
            "broadcast uses fixed mapping",
        )
        val broadcastCustom = CarrierSwitchTarget("自定义广电组", "自定义广电节点")
        check(
            CarrierSwitchMatcher.match(
                broadcastConfig.copy(custom = listOf(CarrierSwitchKeyword("广电", broadcastCustom.group, broadcastCustom.node))),
                identity(operator = "广电"),
            ) == broadcast,
            "broadcast fixed mapping takes precedence over custom keyword",
        )
        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(CarrierSwitchKeyword("广电", broadcastCustom.group, broadcastCustom.node))),
                identity(operator = "广电"),
            ) == broadcastCustom,
            "empty broadcast fixed mapping falls back to custom keyword",
        )

        val emptyNode = CarrierSwitchTarget("运营商组", "")
        val emptyGroup = CarrierSwitchTarget("", "运营商节点")
        val keywordTarget = CarrierSwitchTarget("关键词组", "关键词节点")
        check(
            CarrierSwitchMatcher.match(config.copy(telecom = emptyNode), identity(operator = "电信")) == null,
            "fixed mapping with empty node is skipped",
        )
        check(
            CarrierSwitchMatcher.match(config.copy(telecom = emptyGroup), identity(operator = "电信")) == null,
            "fixed mapping with empty group is skipped",
        )
        check(
            CarrierSwitchMatcher.match(
                config.copy(telecom = emptyNode, custom = listOf(CarrierSwitchKeyword("电信", keywordTarget.group, keywordTarget.node))),
                identity(operator = "电信"),
            ) == keywordTarget,
            "invalid fixed mapping does not block a valid keyword mapping",
        )

        val keywordConfig = config.copy(custom = listOf(CarrierSwitchKeyword("电信", keywordTarget.group, keywordTarget.node)))
        check(
            CarrierSwitchMatcher.match(keywordConfig, identity(operator = "中国电信运营商")) == keywordTarget,
            "custom keyword matches operator case-insensitively",
        )
        val rawKeywordConfig = config.copy(custom = listOf(CarrierSwitchKeyword("CARRIER", keywordTarget.group, keywordTarget.node)))
        check(
            CarrierSwitchMatcher.match(rawKeywordConfig, identity(rawOperator = "carrier")) == keywordTarget,
            "custom keyword matches raw operator case-insensitively",
        )
        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(
                    CarrierSwitchKeyword("电信", "无效组", ""),
                    CarrierSwitchKeyword("电信", keywordTarget.group, keywordTarget.node),
                )),
                identity(operator = "电信"),
            ) == keywordTarget,
            "invalid keyword mapping does not block the next valid mapping",
        )

        val unicomCustom = CarrierSwitchTarget("自定义联通组", "自定义联通节点")
        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(CarrierSwitchKeyword("联通", unicomCustom.group, unicomCustom.node))),
                identity(operator = "联通"),
            ) == unicom,
            "fixed unicom mapping takes precedence over custom keyword",
        )

        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(CarrierSwitchKeyword("", "空关键词组", "空关键词节点"))),
                identity(operator = "未知"),
            ) == null,
            "custom item without keyword never matches",
        )

        check(
            CarrierSwitchMatcher.match(config, identity(operator = "未知")) == null,
            "unknown operator without custom mapping returns null",
        )

        check(
            !CarrierSwitchMatcher.needsSwitch("目标节点", "目标节点"),
            "already selected target does not trigger another switch",
        )
        check(
            CarrierSwitchMatcher.needsSwitch("其他节点", "目标节点"),
            "different selected node triggers a switch",
        )
        check(
            CarrierSwitchMatcher.needsSwitch(null, "目标节点"),
            "missing selection triggers a switch",
        )
    }
}
