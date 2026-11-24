package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.common.custom.CarrierSwitchTargetCheck
import com.github.kr328.clash.common.custom.CarrierSwitchTargetState

object CarrierSwitchTargetCheckTests {
    fun run() {
        check(
            CarrierSwitchTargetCheck.check("Selector", listOf("节点A", "节点B"), "节点A", "节点A") ==
                CarrierSwitchTargetState.AlreadySelected,
            "existing selector target already selected",
        )
        check(
            CarrierSwitchTargetCheck.check("Selector", listOf("节点A", "节点B"), "节点A", "节点B") ==
                CarrierSwitchTargetState.NeedSwitch,
            "existing selector target needs switch",
        )
        check(
            CarrierSwitchTargetCheck.check("Selector", listOf("节点A"), "节点A", "不存在") ==
                CarrierSwitchTargetState.NodeMissing,
            "target node missing",
        )
        check(
            CarrierSwitchTargetCheck.check("Unknown", emptyList(), "", "节点A") ==
                CarrierSwitchTargetState.GroupMissing,
            "unknown group missing",
        )
        check(
            CarrierSwitchTargetCheck.check("URLTest", listOf("节点A"), "节点A", "节点A") ==
                CarrierSwitchTargetState.GroupMissing,
            "non-selector group is missing for switching",
        )
        check(
            CarrierSwitchTargetCheck.check("Selector", emptyList(), "", "节点A") ==
                CarrierSwitchTargetState.NodeMissing,
            "empty proxy list means target node missing",
        )
        check(
            CarrierSwitchTargetCheck.check("Selector", listOf("节点A"), "", "节点A") ==
                CarrierSwitchTargetState.NeedSwitch,
            "empty current selection needs switch",
        )
    }
}
