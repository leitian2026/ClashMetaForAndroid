package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.design.R as DesignR

// CUSTOM (CUSTOM.md #17): compact user-data restore summary shared by local and cloud restore.
object RestoreSummary {
    fun extraLines(context: Context, result: BackupManager.RestoreResult): List<String> {
        val lines = ArrayList<String>()
        result.savedIps?.let { stat ->
            lines += if (stat.merged) {
                context.getString(DesignR.string.restore_summary_saved_ips_merged, stat.added, stat.updated, stat.total)
            } else {
                context.getString(DesignR.string.restore_summary_saved_ips_replaced, stat.total)
            }
        }
        if (result.carrierRestored + result.carrierKept + result.carrierFailed > 0) {
            lines += context.getString(DesignR.string.restore_summary_carrier, result.carrierRestored, result.carrierKept)
        }
        if (result.carrierFailed > 0) {
            lines += context.getString(DesignR.string.restore_summary_carrier_failed, result.carrierFailed)
        }
        return lines
    }
}
