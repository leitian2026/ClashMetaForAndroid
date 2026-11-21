package com.github.kr328.clash.design.custom

import android.content.Context
import android.widget.Toast
import com.github.kr328.clash.core.model.IpTestReport
import com.github.kr328.clash.design.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * CUSTOM #43: the optional "read IPs from config" action, extracted from IpTestDialog.ask
 * without changing its callback, error, or cancellation behavior.
 */
internal object IpTestConfigReadButton {
    fun create(
        context: Context,
        scope: CoroutineScope,
        readConfig: (suspend () -> IpTestReport?)?,
        onReport: (IpTestReport) -> Unit,
    ): MaterialButton = MaterialButton(context).apply {
        setText(R.string.iptest_cfg_button)
        isAllCaps = false

        setOnClickListener {
            val read = readConfig ?: return@setOnClickListener

            isEnabled = false
            setText(R.string.iptest_cfg_reading)

            scope.launch {
                val report = try {
                    read()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                isEnabled = true
                setText(R.string.iptest_cfg_button)

                if (report == null) {
                    Toast.makeText(context, R.string.iptest_cfg_error, Toast.LENGTH_LONG).show()
                } else {
                    onReport(report)
                }
            }
        }
    }
}
