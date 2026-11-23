package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.service.custom.CarrierSwitch // CUSTOM #169
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class SuspendModule(service: Service) : Module<Unit>(service) {
    private suspend fun sendScreenState(interactive: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                CustomConnections.query(
                    CustomConnections.SORT_ISSUES,
                    if (interactive) "screen|1" else "screen|0"
                )
            } catch (e: Exception) {
                Log.w("Screen state update failed", e)
            }
        }
    }

    override suspend fun run() {
        // Register first so a screen transition during the initial state query cannot be missed.
        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        val profileLoaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }

        val interactive = service.getSystemService<PowerManager>()?.isInteractive ?: true

        Clash.suspendCore(!interactive)
        sendScreenState(interactive) // CUSTOM: CUSTOM.md #53; default in Go is screen-on until this arrives

        try {
            while (true) {
                select<Unit> {
                    screenToggle.onReceive {
                        when (it.action) {
                            Intent.ACTION_SCREEN_ON -> {
                                Clash.suspendCore(false)
                                sendScreenState(true)

                                Log.d("Clash resumed")
                            }
                            Intent.ACTION_SCREEN_OFF -> {
                                Clash.suspendCore(true)
                                sendScreenState(false)

                                Log.d("Clash suspended")
                            }
                        }
                    }
                    profileLoaded.onReceive {
                        val current = service.getSystemService<PowerManager>()?.isInteractive ?: true
                        sendScreenState(current)
                        CarrierSwitch.applyAfterProfileLoaded(service) // CUSTOM #169: reapply after each loaded profile
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                Clash.suspendCore(false)
            }
        }
    }
}