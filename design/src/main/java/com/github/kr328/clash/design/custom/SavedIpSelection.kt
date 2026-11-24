package com.github.kr328.clash.design.custom

import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.kr328.clash.design.R

// changes.md #175: multi-selection state and actions for saved-IP entries.
internal class SavedIpSelection(private val env: SavedIpEnv) {
    var selectedOperatorId: String? = null
    val selectedAddresses = LinkedHashSet<String>()

fun toggleSelection(operatorId: String, address: String): Boolean {
        val currentOperator = selectedOperatorId
        if (currentOperator != null && currentOperator != operatorId) return false
        val enteringSelectionMode = currentOperator == null
        if (enteringSelectionMode) {
            selectedOperatorId = operatorId
            env.expandedOperatorId = operatorId
        }

        if (address in selectedAddresses) {
            selectedAddresses.remove(address)
        } else {
            if (selectedAddresses.size >= 20) {
                Toast.makeText(env.context, R.string.saved_ip_max_20, Toast.LENGTH_SHORT).show()
                return false
            }
            selectedAddresses.add(address)
        }

        val leavingSelectionMode = selectedAddresses.isEmpty()
        if (leavingSelectionMode) selectedOperatorId = null
        updateSelectionActions()
        if (enteringSelectionMode || leavingSelectionMode) env.renderOperator(operatorId)
        return address in selectedAddresses
    }

fun reconcileSelection(snapshot: SavedIpSnapshot) {
        val operatorId = selectedOperatorId ?: run {
            updateSelectionActions()
            return
        }
        val operator = snapshot.operators.firstOrNull { it.id == operatorId }
        if (operator == null) {
            clearSelectionState()
            return
        }
        val existing = operator.ips.mapTo(HashSet()) { it.address }
        selectedAddresses.retainAll(existing)
        if (selectedAddresses.isEmpty()) clearSelectionState() else updateSelectionActions()
    }

fun updateSelectionActions() {
        val active = selectedOperatorId != null
        env.selectionActions.visibility = if (active) View.VISIBLE else View.GONE
        env.selectedCountView.text = env.context.getString(R.string.saved_ip_selected_count, selectedAddresses.size)
        env.retestButton.isEnabled = env.canRetest && selectedAddresses.isNotEmpty()
        env.retestButton.alpha = if (env.retestButton.isEnabled) 1f else 0.35f
        env.deleteSelectedButton.isEnabled = selectedAddresses.isNotEmpty()
        env.deleteSelectedButton.alpha = if (env.deleteSelectedButton.isEnabled) 1f else 0.35f
        val operator = selectedOperatorId?.let { id ->
            env.snapshotCache.operators.firstOrNull { it.id == id }
        }
        val visible = operator?.let { env.visibleEntries(it, applySort = true) }.orEmpty()
        env.selectUnreachableButton.isEnabled = visible.any { it.status == SavedIpStatus.UNREACHABLE }
        env.selectUnreachableButton.alpha = if (env.selectUnreachableButton.isEnabled) 1f else 0.35f
        env.selectOldestButton.isEnabled = visible.isNotEmpty()
        env.selectOldestButton.alpha = if (env.selectOldestButton.isEnabled) 1f else 0.35f
        env.copyHostsButton.isEnabled = selectedAddresses.isNotEmpty()
        env.copyHostsButton.alpha = if (env.copyHostsButton.isEnabled) 1f else 0.35f
    }

fun selectUnreachable() {
        val operatorId = selectedOperatorId ?: return
        val operator = SavedIpStore.read(env.context).operators.firstOrNull { it.id == operatorId } ?: return
        val unreachable = env.visibleEntries(operator, applySort = true).filter { it.status == SavedIpStatus.UNREACHABLE }
        if (unreachable.isEmpty()) return
        val selected = unreachable.sortedBy { it.lastTestedAt }.take(20)
        selectedAddresses.clear()
        selectedAddresses.addAll(selected.map { it.address })
        if (selectedAddresses.isEmpty()) selectedOperatorId = null
        if (unreachable.size > 20) {
            Toast.makeText(env.context, env.context.getString(R.string.saved_ip_select_unreachable_overflow, unreachable.size), Toast.LENGTH_LONG).show()
        }
        env.refresh()
    }

fun selectOldest20() {
        val operatorId = selectedOperatorId ?: return
        val operator = SavedIpStore.read(env.context).operators.firstOrNull { it.id == operatorId } ?: return
        val selected = env.visibleEntries(operator, applySort = true).sortedBy { it.lastTestedAt }.take(20)
        selectedAddresses.clear()
        selectedAddresses.addAll(selected.map { it.address })
        if (selectedAddresses.isEmpty()) selectedOperatorId = null
        updateSelectionActions()
        env.renderOperator(operatorId)
    }

fun clearSelectionState() {
        selectedOperatorId = null
        selectedAddresses.clear()
        updateSelectionActions()
    }

fun clearSelection() {
        val operatorId = selectedOperatorId
        clearSelectionState()
        if (operatorId != null) env.renderOperator(operatorId)
    }

fun chooseSelectAge(operator: SavedIpOperator) {
        val labels = arrayOf(
            env.context.getString(R.string.saved_ip_select_untested),
            env.context.getString(R.string.saved_ip_select_over_1_hour),
            env.context.getString(R.string.saved_ip_select_over_6_hours),
            env.context.getString(R.string.saved_ip_select_over_24_hours),
        )
        MaterialAlertDialogBuilder(env.context)
            .setTitle(R.string.saved_ip_select_age_title)
            .setItems(labels) { _, which ->
                val now = System.currentTimeMillis()
                val matching = when (which) {
                    0 -> operator.ips.filter { it.status == SavedIpStatus.UNTESTED || it.lastTestedAt <= 0L }
                        .sortedBy { it.addedAt }
                    1, 2, 3 -> {
                        val hours = when (which) {
                            1 -> 1L
                            2 -> 6L
                            else -> 24L
                        }
                        val cutoff = now - hours * 60L * 60L * 1000L
                        operator.ips.filter { it.lastTestedAt > 0L && it.lastTestedAt < cutoff }
                            .sortedBy { it.lastTestedAt }
                    }
                    else -> emptyList()
                }
                selectGroupMatches(operator, matching)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

fun selectGroupMatches(operator: SavedIpOperator, matching: List<SavedIpEntry>) {
        if (matching.isEmpty()) {
            Toast.makeText(env.context, R.string.saved_ip_select_age_none, Toast.LENGTH_SHORT).show()
            return
        }
        selectedOperatorId = operator.id
        env.expandedOperatorId = operator.id
        selectedAddresses.clear()
        val selected = matching.take(20)
        selectedAddresses.addAll(selected.map { it.address })
        updateSelectionActions()
        env.renderOperator(operator.id)
        if (matching.size > 20) {
            Toast.makeText(
                env.context,
                env.context.getString(R.string.saved_ip_select_age_overflow, matching.size),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun handleBackPressed(): Boolean = false
}
