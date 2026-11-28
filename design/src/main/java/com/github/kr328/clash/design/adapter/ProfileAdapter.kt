package com.github.kr328.clash.design.adapter

import android.content.Context
import java.text.DecimalFormat // CUSTOM (CUSTOM.md #179)
import java.text.DecimalFormatSymbols // CUSTOM (CUSTOM.md #179)
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterProfileBinding
import com.github.kr328.clash.design.model.ProfilePageState
import com.github.kr328.clash.design.model.ProxyPageState
import com.github.kr328.clash.design.ui.ObservableCurrentTime
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir // CUSTOM (CUSTOM.md #179)
import com.github.kr328.clash.service.util.pendingDir // CUSTOM (CUSTOM.md #179)

class ProfileAdapter(
    private val context: Context,
    private val onClicked: (Profile) -> Unit,
    private val onMenuClicked: (Profile) -> Unit,
    private val onEditClicked: (Profile) -> Unit = {}, // CUSTOM (CUSTOM.md #47)
) :RecyclerView.Adapter<ProfileAdapter.Holder>() {
    class Holder(val binding: AdapterProfileBinding) : RecyclerView.ViewHolder(binding.root)

    private val currentTime = ObservableCurrentTime()

    var profiles: List<Profile> = emptyList()
    val states = ProfilePageState()

    fun updateElapsed() {
        currentTime.update()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterProfileBinding
                .inflate(context.layoutInflater, parent, false)
                .also { it.currentTime = currentTime }
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = profiles[position]
        val binding = holder.binding

        if (current === binding.profile)
            return

        binding.profile = current
        // CUSTOM (CUSTOM.md #179): read only config.yaml in the profile's current directory.
        val profileDirectory = if (current.pending) context.pendingDir else context.importedDir
        val configFile = profileDirectory.resolve(current.uuid.toString()).resolve("config.yaml")
        binding.configSize = if (configFile.isFile) {
            formatConfigSize(configFile.length())
        } else {
            "—"
        }
        binding.setClicked {
            onClicked(current)
        }
        binding.setMenu {
            onMenuClicked(current)
        }
        // CUSTOM (CUSTOM.md #47)
        binding.setEdit {
            onEditClicked(current)
        }
    }

    // CUSTOM (CUSTOM.md #179): always show exactly two fractional digits.
    private fun formatConfigSize(bytes: Long): String {
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val number = DecimalFormat("0.00", DecimalFormatSymbols.getInstance()).format(value)
        return "$number ${units[unitIndex]}"
    }

    override fun getItemCount(): Int {
        return profiles.size
    }
}