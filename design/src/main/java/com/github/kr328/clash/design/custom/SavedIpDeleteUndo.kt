package com.github.kr328.clash.design.custom

import android.util.Log
import android.widget.Toast
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.UUID

// changes.md #175: deletion, undo, and operator-management actions for the saved-IP page.
internal class SavedIpDeleteUndo(private val env: SavedIpEnv) {
    internal data class SavedIpUndo(val operatorId: String, val entries: List<SavedIpEntry>)

    var failureDetailDialog: android.app.Dialog? = null
    private var pendingUndo: SavedIpUndo? = null
    private var undoSnackbar: Snackbar? = null

fun invalidateUndo() {
        pendingUndo = null
        undoSnackbar?.dismiss()
        undoSnackbar = null
    }

fun showDeleteUndo(operatorId: String, entries: List<SavedIpEntry>) {
        if (entries.isEmpty()) return
        invalidateUndo()
        val undo = SavedIpUndo(operatorId, entries.toList())
        pendingUndo = undo
        val snackbar = Snackbar.make(
            env.root,
            env.context.getString(R.string.saved_ip_deleted_undo, entries.size),
            Snackbar.LENGTH_LONG,
        ).setAction(R.string.saved_ip_undo) {
            restoreDeleted(undo)
        }
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (pendingUndo === undo) pendingUndo = null
                if (undoSnackbar === transientBottomBar) undoSnackbar = null
            }
        })
        undoSnackbar = snackbar
        snackbar.show()
    }

fun restoreDeleted(undo: SavedIpUndo) {
        if (pendingUndo !== undo) return
        pendingUndo = null
        undoSnackbar = null
        val exists = env.snapshotCache.operators.any { it.id == undo.operatorId }
        if (!exists) return
        if (!writeSnapshot { snapshot ->
            snapshot.copy(operators = snapshot.operators.map { operator ->
                if (operator.id != undo.operatorId) operator else {
                    val addresses = operator.ips.mapTo(HashSet()) { it.address }
                    val restored = undo.entries.filterNot { it.address in addresses }
                    operator.copy(ips = operator.ips + restored)
                }
            })
        }) return
        env.refresh()
    }

fun dismissFailureDetailDialog() {
        failureDetailDialog?.dismiss()
        failureDetailDialog = null
    }

fun disposeUndo() {
        dismissFailureDetailDialog()
        invalidateUndo()
    }

fun deleteSelected() {
        val operatorId = env.selectedOperatorId ?: return
        val addresses = env.selectedAddresses.toSet()
        if (addresses.isEmpty()) return

        MaterialAlertDialogBuilder(env.context)
            .setTitle(R.string.saved_ip_delete_ip_title)
            .setMessage(env.context.getString(R.string.saved_ip_delete_selected_message, addresses.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                var deletedEntries = emptyList<SavedIpEntry>()
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { operator ->
                        if (operator.id == operatorId) {
                            deletedEntries = operator.ips.filter { it.address in addresses }
                            operator.copy(ips = operator.ips.filterNot { it.address in addresses })
                        } else operator
                    })
                }) return@setPositiveButton
                env.clearSelectionState()
                env.refresh()
                showDeleteUndo(operatorId, deletedEntries)
            }
            .show()
    }

fun confirmDeleteOperator(operator: SavedIpOperator) {
        if (operator.id in SavedIpStore.builtInOperatorIds) return
        MaterialAlertDialogBuilder(env.context)
            .setTitle(R.string.saved_ip_operator_delete_title)
            .setMessage(env.context.getString(R.string.saved_ip_operator_delete_message, operator.ips.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(
                        operators = snapshot.operators.filterNot { it.id == operator.id },
                        lastOperatorId = if (snapshot.lastOperatorId == operator.id) SavedIpStore.OPERATOR_TELECOM else snapshot.lastOperatorId,
                    )
                }) return@setPositiveButton
                env.refresh()
            }
            .show()
    }

fun chooseDeleteOlder(operator: SavedIpOperator) {
        val days = intArrayOf(1, 3, 7, 30)
        val labels = arrayOf(
            env.context.getString(R.string.saved_ip_days_1),
            env.context.getString(R.string.saved_ip_days_3),
            env.context.getString(R.string.saved_ip_days_7),
            env.context.getString(R.string.saved_ip_days_30),
        )
        MaterialAlertDialogBuilder(env.context)
            .setTitle(R.string.saved_ip_delete_older_title)
            .setItems(labels) { _, which ->
                if (which in days.indices) {
                    val cutoff = System.currentTimeMillis() - days[which].toLong() * 24L * 60L * 60L * 1000L
                    confirmDeleteOlder(operator, days[which], cutoff)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

fun confirmDeleteOlder(operator: SavedIpOperator, days: Int, cutoff: Long) {
        val current = env.snapshotCache.operators.firstOrNull { it.id == operator.id } ?: operator
        val count = current.ips.count { it.lastTestedAt > 0L && it.lastTestedAt < cutoff }
        MaterialAlertDialogBuilder(env.context)
            .setTitle(R.string.saved_ip_delete_older_confirm_title)
            .setMessage(env.context.getString(R.string.saved_ip_delete_older_message, savedIpDisplayName(env.context, operator), count, days))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                var deletedEntries = emptyList<SavedIpEntry>()
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { saved ->
                        if (saved.id == operator.id) {
                            deletedEntries = saved.ips.filter { it.lastTestedAt > 0L && it.lastTestedAt < cutoff }
                            saved.copy(ips = saved.ips.filterNot { it.lastTestedAt > 0L && it.lastTestedAt < cutoff })
                        } else saved
                    })
                }) return@setPositiveButton
                if (env.selectedOperatorId == operator.id) env.clearSelectionState()
                env.refresh()
                showDeleteUndo(operator.id, deletedEntries)
            }
            .show()
    }

fun confirmDeleteUnreachable(operator: SavedIpOperator) {
        val count = operator.ips.count { it.status == SavedIpStatus.UNREACHABLE }
        if (count == 0) return
        MaterialAlertDialogBuilder(env.context)
            .setTitle(R.string.saved_ip_delete_unreachable_title)
            .setMessage(env.context.getString(R.string.saved_ip_delete_unreachable_message, savedIpDisplayName(env.context, operator), count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                var deletedEntries = emptyList<SavedIpEntry>()
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { current ->
                        if (current.id == operator.id) {
                            deletedEntries = current.ips.filter { it.status == SavedIpStatus.UNREACHABLE }
                            current.copy(ips = current.ips.filterNot { it.status == SavedIpStatus.UNREACHABLE })
                        } else current
                    })
                }) return@setPositiveButton
                if (env.selectedOperatorId == operator.id) env.clearSelectionState()
                env.refresh()
                showDeleteUndo(operator.id, deletedEntries)
            }
            .show()
    }

fun confirmDeleteIp(operatorId: String, address: String) {
        MaterialAlertDialogBuilder(env.context)
            .setTitle(R.string.saved_ip_delete_ip_title)
            .setMessage(env.context.getString(R.string.saved_ip_delete_ip_message, address))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_delete) { _, _ ->
                if (!writeSnapshot { snapshot ->
                    snapshot.copy(operators = snapshot.operators.map { operator ->
                        if (operator.id == operatorId) operator.copy(ips = operator.ips.filterNot { it.address == address })
                        else operator
                    })
                }) return@setPositiveButton
                env.refresh()
            }
            .show()
    }

fun writeSnapshot(transform: (SavedIpSnapshot) -> SavedIpSnapshot): Boolean {
        return try {
            SavedIpStore.update(env.context, transform)
            true
        } catch (e: SavedIpLimitException) {
            Log.w("SavedIpDesign", "Saved IP list reached its ${SavedIpStore.MAX_IP_COUNT}-entry limit", e)
            Toast.makeText(env.context, env.context.getString(R.string.saved_ip_save_limit, SavedIpStore.MAX_IP_COUNT), Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Log.e("SavedIpDesign", "Failed to write saved IP list", e)
            Toast.makeText(env.context, env.context.getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            false
        }
    }

fun editOperator(operator: SavedIpOperator) {
        if (operator.id in SavedIpStore.builtInOperatorIds) return
        showOperatorNameDialog(operator)
    }

fun showOperatorNameDialog(existing: SavedIpOperator?) {
        val input = EditText(env.context).apply {
            setHint(R.string.saved_ip_operator_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            if (existing != null) setText(existing.name)
            setSelection(text.length)
        }
        val dialog = MaterialAlertDialogBuilder(env.context)
            .setTitle(if (existing == null) R.string.saved_ip_add_operator_title else R.string.saved_ip_edit_operator_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_ip_add, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val error = savedIpOperatorNameError(env.context, name, existing?.id)
                if (error != 0) {
                    input.error = env.context.getString(error)
                    return@setOnClickListener
                }

                if (!writeSnapshot { snapshot ->
                    val operators = snapshot.operators.toMutableList()
                    if (existing == null) {
                        operators += SavedIpOperator("custom_" + UUID.randomUUID().toString(), name)
                    } else {
                        val index = operators.indexOfFirst { it.id == existing.id }
                        if (index >= 0) operators[index] = operators[index].copy(name = name)
                    }
                    snapshot.copy(operators = operators)
                }) return@setOnClickListener
                dialog.dismiss()
                env.refresh()
            }
        }
        dialog.show()
    }
}
