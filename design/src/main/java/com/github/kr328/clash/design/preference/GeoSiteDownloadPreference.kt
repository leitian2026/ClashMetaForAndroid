package com.github.kr328.clash.design.preference

import android.content.Context
import android.text.format.Formatter
import android.view.View
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.custom.GeoSiteDownloader
import com.github.kr328.clash.design.databinding.PreferenceGeositeDownloadBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// CUSTOM: always-visible URL box + "download and install" button for geosite.dat (see CUSTOM.md #6).

fun PreferenceScreen.geoSiteDownload(): Preference {
    val binding = PreferenceGeositeDownloadBinding
        .inflate(context.layoutInflater, root, false)

    val store = context.getSharedPreferences(GeoSiteDownloader.STORE_NAME, Context.MODE_PRIVATE)
    // Same directory as Context.clashDir in the app module (filesDir/clash).
    val clashDir = context.filesDir.resolve("clash")

    var job: Job? = null

    fun currentUrl(): String = binding.urlField.text?.toString()?.trim().orEmpty()

    fun saveUrl() {
        val url = currentUrl()
        if (url.isNotEmpty()) {
            store.edit().putString(GeoSiteDownloader.KEY_URL, url).apply()
        }
    }

    fun setBusy(busy: Boolean) {
        binding.urlField.isEnabled = !busy
        binding.downloadButton.setText(
            if (busy) R.string.geosite_download_cancel else R.string.geosite_download_button
        )
    }

    binding.urlField.setText(
        store.getString(GeoSiteDownloader.KEY_URL, null)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.geosite_download_default_url)
    )
    binding.urlField.doOnTextChanged { _, _, _, _ -> binding.urlLayout.error = null }
    binding.urlField.setOnFocusChangeListener { _, hasFocus ->
        if (!hasFocus) saveUrl()
    }

    // CUSTOM (CUSTOM.md #13): the service reloads the profile when it gets this broadcast; with the
    // service stopped nothing listens and the new file is simply used at the next start.
    binding.reloadButton.setOnClickListener {
        context.sendOverrideChanged()

        binding.reloadButton.visibility = View.GONE
        binding.statusView.text = context.getString(R.string.geosite_download_reload_sent)
    }

    binding.downloadButton.setOnClickListener {
        val running = job
        if (running != null && running.isActive) {
            running.cancel()
            return@setOnClickListener
        }

        val url = currentUrl()
        if (url.length <= "https://".length || !url.startsWith("https://", ignoreCase = true)) {
            binding.urlLayout.error = context.getString(R.string.geosite_download_err_url)
            return@setOnClickListener
        }

        saveUrl()
        binding.statusView.text = ""
        binding.reloadButton.visibility = View.GONE
        setBusy(true)

        job = launch(Dispatchers.Main) {
            try {
                val size = GeoSiteDownloader.download(context, url, clashDir) { bytes ->
                    binding.statusView.post {
                        binding.statusView.text = context.getString(
                            R.string.geosite_download_progress,
                            Formatter.formatShortFileSize(context, bytes)
                        )
                    }
                }

                store.edit()
                    .putLong(
                        GeoSiteDownloader.KEY_INSTALLED_AT,
                        clashDir.resolve("geosite.dat").lastModified()
                    )
                    .apply()

                binding.statusView.text = context.getString(
                    R.string.geosite_download_done,
                    Formatter.formatShortFileSize(context, size)
                )
                binding.reloadButton.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                binding.statusView.text = context.getString(R.string.geosite_download_cancelled)

                throw e
            } catch (e: Exception) {
                binding.statusView.text = context.getString(
                    R.string.geosite_download_failed,
                    e.message ?: e.javaClass.simpleName
                )
            } finally {
                setBusy(false)
            }
        }
    }

    val impl = object : Preference {
        override val view: View
            get() = binding.root
    }

    addElement(impl)

    return impl
}
