package com.github.kr328.clash.design.custom

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.github.kr328.clash.common.custom.CarrierSwitchConfig
import com.github.kr328.clash.common.custom.CarrierSwitchKeyword
import com.github.kr328.clash.common.custom.CarrierSwitchTarget
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** CUSTOM (CUSTOM.md #169): editor for profile-scoped carrier-to-selector mappings. */
class CarrierSwitchDesign(
    context: android.content.Context,
    initial: CarrierSwitchConfig,
    private val groups: List<String>,
    private val nodes: Map<String, List<String>>,
) : Design<CarrierSwitchDesign.Request>(context) {
    sealed class Request {
        data class Save(val config: CarrierSwitchConfig) : Request()
    }

    private var config = initial
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val customRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val root: View
        get() = content

    init {
        content.addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), surface.insets.top + dp(4), dp(16), dp(8))
                addView(TextView(context).apply {
                    text = "‹"
                    textSize = 32f
                    gravity = Gravity.CENTER
                    setOnClickListener { (context as? Activity)?.finish() }
                }, LinearLayout.LayoutParams(dp(44), dp(48)))
                addView(TextView(context).apply {
                    setText(R.string.carrier_switch_title)
                    textSize = 20f
                })
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(body)
        content.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        body.addView(sectionTitle(R.string.carrier_switch_fixed_section))
        fixedRow(body, "telecom", R.string.carrier_switch_telecom, config.telecom)
        fixedRow(body, "unicom", R.string.carrier_switch_unicom, config.unicom)
        fixedRow(body, "mobile", R.string.carrier_switch_mobile, config.mobile)

        body.addView(sectionTitle(R.string.carrier_switch_custom_section))
        body.addView(customRows)
        body.addView(MaterialButton(context).apply {
            setText(R.string.carrier_switch_add)
            isAllCaps = false
            setOnClickListener { addCustom() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        content.addView(MaterialButton(context).apply {
            setText(R.string.carrier_switch_save)
            isAllCaps = false
            setOnClickListener { requests.trySend(Request.Save(config)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(8), dp(16), surface.insets.bottom + dp(12))
        })

        renderCustomRows()
    }

    fun showSaved() {
        Toast.makeText(context, R.string.carrier_switch_saved, Toast.LENGTH_SHORT).show()
    }

    private fun fixedRow(parent: LinearLayout, key: String, title: Int, target: CarrierSwitchTarget) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        row.addView(TextView(context).apply {
            setText(title)
            textSize = 14f
        })
        val defaultButton = MaterialButton(context).apply {
            isAllCaps = false
            text = labelledTargetText(R.string.carrier_switch_default_target_label, target)
            setOnClickListener {
                chooseTarget(target) { selected ->
                    val updated = target.copy(group = selected.group, node = selected.node)
                    config = updateFixedConfig(key, updated)
                    text = labelledTargetText(R.string.carrier_switch_default_target_label, updated)
                }
            }
        }
        row.addView(defaultButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val ipv6Current = CarrierSwitchTarget(target.v6Group, target.v6Node)
        val ipv6Button = MaterialButton(context).apply {
            isAllCaps = false
            text = labelledTargetText(R.string.carrier_switch_ipv6_target_label, ipv6Current)
            setOnClickListener {
                chooseTarget(ipv6Current) { selected ->
                    val updated = target.copy(v6Group = selected.group, v6Node = selected.node)
                    config = updateFixedConfig(key, updated)
                    text = labelledTargetText(R.string.carrier_switch_ipv6_target_label, CarrierSwitchTarget(updated.v6Group, updated.v6Node))
                }
            }
        }
        row.addView(ipv6Button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
    }

    private fun updateFixedConfig(key: String, target: CarrierSwitchTarget): CarrierSwitchConfig = when (key) {
        "telecom" -> config.copy(telecom = target)
        "unicom" -> config.copy(unicom = target)
        else -> config.copy(mobile = target)
    }

    private fun chooseTarget(current: CarrierSwitchTarget, selected: (CarrierSwitchTarget) -> Unit) {
        val options = groups + ""
        val labels = options.map { if (it.isEmpty()) context.getString(R.string.carrier_switch_clear) else it }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.carrier_switch_choose_group)
            .setItems(labels.toTypedArray()) { _, index ->
                val group = options[index]
                if (group.isEmpty()) {
                    selected(CarrierSwitchTarget())
                } else {
                    chooseNode(group, current.node) { node ->
                        selected(CarrierSwitchTarget(group, node))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseNode(group: String, current: String, selected: (String) -> Unit) {
        val options = nodes[group].orEmpty()
        if (options.isEmpty()) {
            Toast.makeText(context, R.string.carrier_switch_node_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = options + ""
        val display = labels.map { if (it.isEmpty()) context.getString(R.string.carrier_switch_clear) else it }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.carrier_switch_choose_node)
            .setItems(display.toTypedArray()) { _, index -> selected(labels[index]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addCustom() = editCustom(null)

    private fun editCustom(index: Int?) {
        val old = index?.let { config.custom.getOrNull(it) }
        val keywordInput = EditText(context).apply {
            hint = context.getString(R.string.carrier_switch_keyword)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(old?.keyword.orEmpty())
        }
        val regionInput = EditText(context).apply {
            hint = context.getString(R.string.carrier_switch_region_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(old?.region.orEmpty())
        }
        val inputs = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(keywordInput)
            addView(regionInput)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.carrier_switch_custom_section)
            .setView(inputs)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val keyword = keywordInput.text.toString().trim()
                val region = regionInput.text.toString().trim()
                if (keyword.isEmpty() && region.isEmpty()) {
                    Toast.makeText(context, R.string.carrier_switch_keyword_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                chooseTarget(CarrierSwitchTarget(old?.group.orEmpty(), old?.node.orEmpty())) { target ->
                    chooseTarget(CarrierSwitchTarget(old?.v6Group.orEmpty(), old?.v6Node.orEmpty())) { ipv6Target ->
                        val item = CarrierSwitchKeyword(
                            keyword, target.group, target.node, region, ipv6Target.group, ipv6Target.node,
                        )
                        val updated = config.custom.toMutableList()
                        if (index == null) updated += item else if (index in updated.indices) updated[index] = item
                        config = config.copy(custom = updated)
                        renderCustomRows()
                    }
                }
            }
            .show()
    }

    private fun renderCustomRows() {
        customRows.removeAllViews()
        config.custom.forEachIndexed { index, item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(context).apply {
                text = listOf(item.keyword, item.region.takeIf { it.isNotBlank() }?.let { "[$it]" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                textSize = 15f
            })
            row.addView(TextView(context).apply {
                text = labelledTargetText(
                    R.string.carrier_switch_default_target_label, CarrierSwitchTarget(item.group, item.node),
                )
                textSize = 12f
            })
            row.addView(TextView(context).apply {
                text = labelledTargetText(
                    R.string.carrier_switch_ipv6_target_label, CarrierSwitchTarget(item.v6Group, item.v6Node),
                )
                textSize = 12f
            })
            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(MaterialButton(context).apply {
                setText(R.string.carrier_switch_edit)
                isAllCaps = false
                setOnClickListener { editCustom(index) }
            })
            actions.addView(MaterialButton(context).apply {
                setText(R.string.carrier_switch_delete)
                isAllCaps = false
                setOnClickListener {
                    config = config.copy(custom = config.custom.filterIndexed { i, _ -> i != index })
                    renderCustomRows()
                }
            })
            row.addView(actions)
            customRows.addView(row)
            if (index < config.custom.lastIndex) customRows.addView(View(context).apply {
                setBackgroundColor(0x22000000)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    private fun sectionTitle(resource: Int) = TextView(context).apply {
        setText(resource)
        textSize = 16f
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun labelledTargetText(label: Int, target: CarrierSwitchTarget): String =
        context.getString(label, targetText(target))

    private fun targetText(target: CarrierSwitchTarget): String {
        return if (target.group.isBlank() || target.node.isBlank()) {
            context.getString(R.string.carrier_switch_not_set)
        } else {
            context.getString(R.string.carrier_switch_group_node, target.group, target.node)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
