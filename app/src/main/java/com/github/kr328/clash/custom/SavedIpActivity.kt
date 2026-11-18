package com.github.kr328.clash.custom

import android.content.Intent
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.design.custom.SavedIpDesign

/** CUSTOM.md #156 step 3/4: local saved-IP management and the hand-off to the existing IP test flow. */
class SavedIpActivity : BaseActivity<SavedIpDesign>() {
    private var page: SavedIpDesign? = null

    override suspend fun main() {
        val canRetest = intent.getBooleanExtra(EXTRA_CAN_RETEST, false)
        val design = SavedIpDesign(
            this,
            onBack = { finish() },
            canRetest = canRetest,
            onRetest = { operatorId, addresses ->
                startActivity(
                    Intent(this, ConfigCheckActivity::class.java)
                        .putExtra(EXTRA_PRESET_IPS, addresses.joinToString("\n"))
                        .putExtra(EXTRA_OPERATOR_ID, operatorId),
                )
            },
        )
        page = design
        setContentDesign(design)
    }

    // CUSTOM.md #156 step 24: refresh the existing page when it is reordered to the front.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        page?.setCanRetest(intent.getBooleanExtra(EXTRA_CAN_RETEST, false))
    }

    override fun onResume() {
        super.onResume()
        page?.refresh()
    }

    companion object {
        const val EXTRA_CAN_RETEST = "saved_ip_can_retest"
        const val EXTRA_PRESET_IPS = "saved_ip_preset_ips"
        const val EXTRA_OPERATOR_ID = "saved_ip_operator_id"
    }
}
