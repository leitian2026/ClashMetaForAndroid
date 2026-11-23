package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.common.custom.CarrierSwitchKeyword
import com.github.kr328.clash.common.custom.CarrierSwitchMatcher
import com.github.kr328.clash.common.custom.CarrierSwitchTarget
import com.github.kr328.clash.common.custom.NetworkIdentitySnapshot

object CarrierSwitchMatcherTests {
    private fun identity(
        operator: String = "",
        rawOperator: String = "",
        province: String = "",
        city: String = "",
    ) = NetworkIdentitySnapshot(
        signature = "test",
        operator = operator,
        rawOperator = rawOperator,
        province = province,
        city = city,
    )

    fun run() {
        val telecom = CarrierSwitchTarget("电信组", "电信节点")
        val unicom = CarrierSwitchTarget("联通组", "联通节点")
        val mobile = CarrierSwitchTarget("移动组", "移动节点")
        val config = CarrierSwitchConfig(telecom = telecom, unicom = unicom, mobile = mobile)

        check(CarrierSwitchMatcher.match(config, identity(operator = "电信")) == telecom, "telecom uses fixed mapping")
        check(CarrierSwitchMatcher.match(config, identity(operator = "联通")) == unicom, "unicom uses fixed mapping")
        check(CarrierSwitchMatcher.match(config, identity(operator = "移动")) == mobile, "mobile uses fixed mapping")

        val emptyNode = CarrierSwitchTarget("运营商组", "")
        val emptyGroup = CarrierSwitchTarget("", "运营商节点")
        check(
            CarrierSwitchMatcher.match(config.copy(telecom = emptyNode), identity(operator = "电信")) == emptyNode,
            "fixed mapping returns target with empty node",
        )
        check(
            CarrierSwitchMatcher.match(config.copy(telecom = emptyGroup), identity(operator = "电信")) == emptyGroup,
            "fixed mapping returns target with empty group",
        )

        val keywordTarget = CarrierSwitchTarget("关键词组", "关键词节点")
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
            "custom item without keyword and region never matches",
        )

        val regionalTarget = CarrierSwitchTarget("地区组", "地区节点")
        val regionOnlyConfig = config.copy(
            custom = listOf(CarrierSwitchKeyword("", regionalTarget.group, regionalTarget.node, "东莞")),
        )
        check(
            CarrierSwitchMatcher.match(
                regionOnlyConfig,
                identity(operator = "联通", province = "广东", city = "东莞"),
            ) == regionalTarget,
            "region match takes precedence over fixed mapping",
        )

        val regionAndKeywordTarget = CarrierSwitchTarget("地区关键词组", "地区关键词节点")
        val regionAndKeywordConfig = config.copy(
            custom = listOf(CarrierSwitchKeyword("电信", regionAndKeywordTarget.group, regionAndKeywordTarget.node, "东莞")),
        )
        check(
            CarrierSwitchMatcher.match(
                regionAndKeywordConfig,
                identity(operator = "联通", city = "东莞"),
            ) == unicom,
            "region and keyword must both match",
        )
        check(
            CarrierSwitchMatcher.match(
                regionAndKeywordConfig,
                identity(operator = "电信", city = "东莞"),
            ) == regionAndKeywordTarget,
            "region and keyword match together",
        )

        val provinceTarget = CarrierSwitchTarget("省份组", "省份节点")
        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(CarrierSwitchKeyword("", provinceTarget.group, provinceTarget.node, "广东"))),
                identity(province = "广东省"),
            ) == provinceTarget,
            "region matches province by containment",
        )
        val cityTarget = CarrierSwitchTarget("城市组", "城市节点")
        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(CarrierSwitchKeyword("", cityTarget.group, cityTarget.node, "东莞"))),
                identity(city = "东莞市"),
            ) == cityTarget,
            "region matches city by containment",
        )
        val caseTarget = CarrierSwitchTarget("大小写组", "大小写节点")
        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(CarrierSwitchKeyword("", caseTarget.group, caseTarget.node, "dongguan"))),
                identity(city = "DONGGUAN CITY"),
            ) == caseTarget,
            "region matching is case-insensitive",
        )

        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(CarrierSwitchKeyword("广电", "广电组", "广电节点", "深圳"))),
                identity(operator = "中国广电", city = "东莞"),
            ) == null,
            "regional item is excluded from final keyword matching",
        )

        val firstRegionTarget = CarrierSwitchTarget("靠前组", "靠前节点")
        val secondRegionTarget = CarrierSwitchTarget("靠后组", "靠后节点")
        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(
                    CarrierSwitchKeyword("", firstRegionTarget.group, firstRegionTarget.node, "东莞"),
                    CarrierSwitchKeyword("", secondRegionTarget.group, secondRegionTarget.node, "东莞"),
                )),
                identity(city = "东莞市"),
            ) == firstRegionTarget,
            "first matching regional item wins",
        )

        check(CarrierSwitchMatcher.match(config, identity(operator = "未知")) == null, "unknown operator without custom mapping returns null")

        check(
            CarrierSwitchMatcher.match(
                config.copy(custom = listOf(CarrierSwitchKeyword("", "地区组", "地区节点", "东莞"))),
                identity(operator = "移动"),
            ) == mobile,
            "region does not match without province or city",
        )
    }
}
