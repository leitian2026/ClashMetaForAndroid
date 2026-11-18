package com.github.kr328.clash.custom

import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.design.custom.SavedIpDesign

/** CUSTOM.md #156 step 3: local saved-IP management; does not require Clash to be running. */
class SavedIpActivity : BaseActivity<SavedIpDesign>() {
    override suspend fun main() {
        val page = SavedIpDesign(this) { finish() }
        setContentDesign(page)
    }
}
