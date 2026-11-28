package com.github.kr328.clash.design.adapter

import android.content.Context
import android.text.format.Formatter // CUSTOM (CUSTOM.md #179)
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
            Formatter.formatShortFileSize(context, configFile.length())
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

    override fun getItemCount(): Int {
        return profiles.size
    }
}