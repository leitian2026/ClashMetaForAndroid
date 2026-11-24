package com.github.kr328.clash.design.custom.editor

import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.common.custom.CarrierSwitchKeyword
import com.github.kr328.clash.common.custom.CarrierSwitchRename
import com.github.kr328.clash.common.custom.CarrierSwitchTarget

object CarrierSwitchRenameTests {
    fun run() {
        groupRename()
        nodeRename()
        mergeChain()
        revertedRenameIsIgnored()
        customEntryIsRenamed()
        noChangeReturnsNull()
    }

    private fun groupRename() {
        val config = CarrierSwitchConfig(telecom = CarrierSwitchTarget("OldGroup", "Node"))
        val result = CarrierSwitchRename.apply(config, listOf("OldGroup" to "NewGroup"), setOf("NewGroup", "Node"))
        check(result?.telecom?.group == "NewGroup", "carrier mapping group is renamed")
    }

    private fun nodeRename() {
        val config = CarrierSwitchConfig(unicom = CarrierSwitchTarget("Group", "OldNode"))
        val result = CarrierSwitchRename.apply(config, listOf("OldNode" to "NewNode"), setOf("Group", "NewNode"))
        check(result?.unicom?.node == "NewNode", "carrier mapping node is renamed")
    }

    private fun mergeChain() {
        val config = CarrierSwitchConfig(
            telecom = CarrierSwitchTarget("A", "B"),
            mobile = CarrierSwitchTarget("B", "A"),
        )
        val result = CarrierSwitchRename.apply(
            config,
            listOf("A" to "B", "B" to "C"),
            setOf("C"),
        )
        check(result?.telecom?.group == "C" && result.telecom.node == "C", "rename chain is collapsed to its final name")
        check(result?.let { it.mobile.group == "C" && it.mobile.node == "C" } == true, "every chain source resolves to the final name")
        val cycle = CarrierSwitchRename.apply(config, listOf("A" to "B", "B" to "A"), setOf("A", "B"))
        check(cycle == null, "rename cycle is treated as no change")
    }

    private fun revertedRenameIsIgnored() {
        val config = CarrierSwitchConfig(telecom = CarrierSwitchTarget("Old", "Node"))
        val result = CarrierSwitchRename.apply(config, listOf("Old" to "New"), setOf("Old", "New", "Node"))
        check(result == null, "rename is ignored when the old name is still defined")
    }

    private fun customEntryIsRenamed() {
        val config = CarrierSwitchConfig(
            custom = listOf(CarrierSwitchKeyword("carrier", "OldGroup", "OldNode")),
        )
        val result = CarrierSwitchRename.apply(
            config,
            listOf("OldGroup" to "NewGroup", "OldNode" to "NewNode"),
            setOf("NewGroup", "NewNode"),
        )
        check(
            result?.custom?.single() == CarrierSwitchKeyword("carrier", "NewGroup", "NewNode"),
            "custom carrier mapping targets are renamed",
        )
    }

    private fun noChangeReturnsNull() {
        val config = CarrierSwitchConfig()
        check(CarrierSwitchRename.apply(config, listOf("A" to "B"), setOf("B")) == null, "unchanged config returns null")
    }
}
