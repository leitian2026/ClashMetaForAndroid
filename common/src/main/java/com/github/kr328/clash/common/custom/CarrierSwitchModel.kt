package com.github.kr328.clash.common.custom

data class CarrierSwitchTarget(
    val group: String = "",
    val node: String = "",
)

data class CarrierSwitchKeyword(
    val keyword: String,
    val group: String,
    val node: String,
)

data class CarrierSwitchConfig(
    val telecom: CarrierSwitchTarget = CarrierSwitchTarget(),
    val unicom: CarrierSwitchTarget = CarrierSwitchTarget(),
    val mobile: CarrierSwitchTarget = CarrierSwitchTarget(),
    val custom: List<CarrierSwitchKeyword> = emptyList(),
)
