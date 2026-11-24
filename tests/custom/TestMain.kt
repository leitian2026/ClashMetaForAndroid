package com.github.kr328.clash.design.custom.editor

private var testPassed = 0
private var testFailed = 0

fun check(condition: Boolean, name: String) {
    if (condition) {
        testPassed++
        println("PASS: $name")
    } else {
        testFailed++
        System.err.println("FAIL: $name")
    }
}

fun main() {
    YamlPairsTests.run()
    TextSearchTests.run()
    YamlLintTests.run()
    RestoreCarrierSwitchTests.run()
    CarrierSwitchMatcherTests.run()
    CarrierOperatorConsensusTests.run()
    CarrierSwitchTargetCheckTests.run()
    println("Custom logic tests: $testPassed passed, $testFailed failed")
    if (testFailed > 0) kotlin.system.exitProcess(1)
}
