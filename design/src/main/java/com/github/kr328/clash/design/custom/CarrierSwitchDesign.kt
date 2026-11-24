package com.github.kr328.clash.design.custom

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
    initialAutomaticEnabled: Boolean,
    identityDiagnostic: String,
) : Design<CarrierSwitchDesign.Request>(context) {
    sealed class Request {
        data class SetEnabled(val enabled: Boolean) : Request()
        data class Save(val config: CarrierSwitchConfig) : Request()
        data class LoadNodes(val group: String, val selected: (String) -> Unit) : Request()
    }

    private var config = initial
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val customRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var automaticSwitch: Switch? = null
    private var automaticEnabled = initialAutomaticEnabled
    private val loadingGroups = mutableSetOf<String>()

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

        body.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply {
                setText(R.string.carrier_switch_enabled_label)
                textSize = 15f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            automaticSwitch = Switch(context).apply {
                isChecked = automaticEnabled
                setOnCheckedChangeListener { _, checked ->
                    requests.trySend(Request.SetEnabled(checked))
                }
            }
            addView(automaticSwitch)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })

        body.addView(sectionTitle(R.string.carrier_switch_fixed_section))
        fixedRow(body, "telecom", R.string.carrier_switch_telecom, config.telecom)
        fixedRow(body, "unicom", R.string.carrier_switch_unicom, config.unicom)
        fixedRow(body, "mobile", R.string.carrier_switch_mobile, config.mobile)
        fixedRow(body, "broadcast", R.string.carrier_switch_broadcast, config.broadcast)

        body.addView(sectionTitle(R.string.carrier_switch_custom_section))
        body.addView(customRows)
        body.addView(outlinedButton().apply {
            setText(R.string.carrier_switch_add)
            setOnClickListener { addCustom() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        body.addView(sectionTitle(R.string.carrier_switch_identity_section))
        body.addView(TextView(context).apply {
            text = identityDiagnostic.ifBlank { context.getString(R.string.carrier_switch_identity_empty) }
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(8))
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

    fun showSaveFailed() {
        Toast.makeText(context, R.string.carrier_switch_save_failed, Toast.LENGTH_LONG).show()
    }

    fun setAutomaticEnabled(enabled: Boolean) {
        automaticEnabled = enabled
        automaticSwitch?.apply {
            setOnCheckedChangeListener(null)
            isChecked = enabled
            setOnCheckedChangeListener { _, checked ->
                requests.trySend(Request.SetEnabled(checked))
            }
        }
    }

    fun showEnableSaveFailed() {
        Toast.makeText(context, R.string.carrier_switch_enabled_save_failed, Toast.LENGTH_LONG).show()
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
        var current = target
        val defaultButton = outlinedButton().apply {
            text = labelledTargetText(R.string.carrier_switch_default_target_label, current)
            setOnClickListener {
                chooseTarget(current) { selected ->
                    current = current.copy(group = selected.group, node = selected.node)
                    config = updateFixedConfig(key, current)
                    text = labelledTargetText(R.string.carrier_switch_default_target_label, current)
                }
            }
        }
        row.addView(defaultButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
    }

    private fun updateFixedConfig(key: String, target: CarrierSwitchTarget): CarrierSwitchConfig = when (key) {
        "telecom" -> config.copy(telecom = target)
        "unicom" -> config.copy(unicom = target)
        "mobile" -> config.copy(mobile = target)
        "broadcast" -> config.copy(broadcast = target)
        else -> config
    }

    private fun chooseTarget(current: CarrierSwitchTarget, selected: (CarrierSwitchTarget) -> Unit) {
        if (groups.isEmpty()) {
            Toast.makeText(context, R.string.carrier_switch_no_groups, Toast.LENGTH_SHORT).show()
            return
        }
        val options = groups + ""
        val labels = options.map { if (it.isEmpty()) context.getString(R.string.carrier_switch_clear) else it }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.carrier_switch_choose_group)
            .setItems(labels.toTypedArray()) { _, index ->
                val group = options[index]
                if (group.isEmpty()) {
                    selected(CarrierSwitchTarget())
                } else {
                    chooseNode(group) { node ->
                        selected(CarrierSwitchTarget(group, node))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseNode(group: String, selected: (String) -> Unit) {
        if (!loadingGroups.add(group)) return
        if (requests.trySend(Request.LoadNodes(group, selected)).isFailure) {
            loadingGroups.remove(group)
        }
    }

    fun showNodes(group: String, nodes: List<String>, selected: (String) -> Unit) {
        loadingGroups.remove(group)
        if (nodes.isEmpty()) {
            Toast.makeText(context, R.string.carrier_switch_node_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = nodes + ""
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
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.carrier_switch_custom_section)
            .setView(keywordInput)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val keyword = keywordInput.text.toString().trim()
                if (keyword.isEmpty()) {
                    Toast.makeText(context, R.string.carrier_switch_keyword_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                chooseTarget(CarrierSwitchTarget(old?.group.orEmpty(), old?.node.orEmpty())) { target ->
                    val item = CarrierSwitchKeyword(keyword, target.group, target.node)
                    val updated = config.custom.toMutableList()
                    if (index == null) updated += item else if (index in updated.indices) updated[index] = item
                    config = config.copy(custom = updated)
                    renderCustomRows()
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
                text = item.keyword
                textSize = 15f
            })
            row.addView(TextView(context).apply {
                text = labelledTargetText(
                    R.string.carrier_switch_default_target_label, CarrierSwitchTarget(item.group, item.node),
                )
                textSize = 12f
            })
            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(borderlessButton().apply {
                setText(R.string.carrier_switch_edit)
                setOnClickListener { editCustom(index) }
            })
            actions.addView(borderlessButton().apply {
                setText(R.string.carrier_switch_delete)
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

    private fun outlinedButton() = MaterialButton(
        context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        isAllCaps = false
        val color = primaryTextColor()
        setTextColor(color)
        strokeColor = ColorStateList.valueOf(
            Color.argb((Color.alpha(color) * 0.36f).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
        )
        strokeWidth = dp(1)
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    }

    private fun borderlessButton() = MaterialButton(
        context, null, com.google.android.material.R.attr.borderlessButtonStyle,
    ).apply {
        isAllCaps = false
        setTextColor(primaryTextColor())
    }

    private fun primaryTextColor(): Int {
        val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            values.getColor(0, Color.BLACK)
        } finally {
            values.recycle()
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
