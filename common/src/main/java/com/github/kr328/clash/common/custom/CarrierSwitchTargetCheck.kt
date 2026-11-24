package com.github.kr328.clash.common.custom

enum class CarrierSwitchTargetState {
    GroupMissing,
    NodeMissing,
    AlreadySelected,
    NeedSwitch,
}

object CarrierSwitchTargetCheck {
    fun check(
        groupType: String,
        proxyNames: List<String>,
        now: String,
        node: String,
    ): CarrierSwitchTargetState = when {
        groupType != "Selector" -> CarrierSwitchTargetState.GroupMissing
        node !in proxyNames -> CarrierSwitchTargetState.NodeMissing
        now == node -> CarrierSwitchTargetState.AlreadySelected
        else -> CarrierSwitchTargetState.NeedSwitch
    }
}
