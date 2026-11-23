package com.github.kr328.clash.custom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal object RestoreCarrierSwitch {
    suspend fun restoreAndApply(
        context: Context,
        scope: CoroutineScope,
        clashRunning: Boolean,
        restore: suspend () -> BackupManager.RestoreResult,
    ): BackupManager.RestoreResult {
        val waiter = if (clashRunning) ProfileLoadedWaiter(context.applicationContext) else null
        waiter?.register()
        val result = try {
            restore()
        } catch (e: Throwable) {
            waiter?.close()
            throw e
        }
        if (!RestoreCarrierSwitchPolicy.shouldApply(result.carrierRestored)) {
            waiter?.close()
            return result
        }
        val waitForLoaded = RestoreCarrierSwitchPolicy.shouldWaitForProfileLoaded(
            result.carrierRestored, result.activeProfileChanged, clashRunning, result.activeProfileUuid,
        )
        if (!waitForLoaded) waiter?.close()
        val job = scope.launch(Dispatchers.IO) {
            if (waitForLoaded) {
                val loaded = try {
                    waiter?.await(result.activeProfileUuid) == true
                } catch (_: CancellationException) {
                    false
                } catch (_: Exception) {
                    false
                }
                if (!loaded) return@launch
            }
            withClash { applyCarrierSwitch() }
        }
        job.invokeOnCompletion { waiter?.close() }
        return result
    }

    private class ProfileLoadedWaiter(private val context: Context) {
        private val lock = Any()
        private val registered = AtomicBoolean(false)
        private val loadedProfiles = HashSet<String>()
        private var expectedUuid: String? = null
        private var continuation: CancellableContinuation<Boolean>? = null
        private var closed = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intents.ACTION_PROFILE_LOADED) return
                val uuid = intent.getStringExtra(Intents.EXTRA_UUID) ?: return
                val resume = synchronized(lock) {
                    if (closed) null else {
                        loadedProfiles.add(uuid)
                        if (uuid == expectedUuid) {
                            closed = true
                            continuation.also { continuation = null }
                        } else null
                    }
                }
                if (resume != null) {
                    unregister()
                    if (resume.isActive) resume.resume(true)
                }
            }
        }
        fun register() {
            try {
                context.registerReceiverCompat(
                    receiver, IntentFilter(Intents.ACTION_PROFILE_LOADED), Permissions.RECEIVE_SELF_BROADCASTS,
                )
                registered.set(true)
            } catch (_: Exception) {
                close()
            }
        }
        suspend fun await(uuid: String?): Boolean {
            if (uuid == null || !registered.get()) {
                close()
                return false
            }
            return suspendCancellableCoroutine { cont ->
                val alreadyLoaded = synchronized(lock) {
                    if (closed) false else if (uuid in loadedProfiles) {
                        closed = true
                        true
                    } else {
                        expectedUuid = uuid
                        continuation = cont
                        false
                    }
                }
                cont.invokeOnCancellation { close() }
                if (alreadyLoaded) {
                    unregister()
                    if (cont.isActive) cont.resume(true)
                }
            }
        }
        fun close() {
            synchronized(lock) {
                if (closed && !registered.get()) return
                closed = true
                continuation = null
                loadedProfiles.clear()
            }
            unregister()
        }
        private fun unregister() {
            if (registered.compareAndSet(true, false)) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) {
                }
            }
        }
    }
}
