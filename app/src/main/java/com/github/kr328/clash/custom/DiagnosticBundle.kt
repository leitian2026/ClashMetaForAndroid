package com.github.kr328.clash.custom

import android.content.Context
import android.net.Uri
import android.os.Build
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.DnsCheckReport
import com.github.kr328.clash.design.custom.DnsCheckText
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// CUSTOM (CUSTOM.md #29, not upstream): one zip with what is needed to look at a problem, made when the user taps
// "export diagnostic bundle". Nothing is collected in the background; every part is read at that moment from
// something that already exists:
//
//   summary.txt        versions, device, whether the core runs, the active profile's name and type
//   config.yaml        the active profile's config, secrets masked (ConfigMask)
//   override.json      the override settings, secrets masked
//   dns-check.txt      the DNS check (CUSTOM.md #25 #26) for the domain last typed on the config check page
//   errors.json        the core's recent warnings / errors and connection failures (errors page, CUSTOM.md #21)
//   mux.json           the per-node socket summary (CUSTOM.md #20)
//   ip_history.json    the node IP switch history (CUSTOM.md #10)
//   crash/*.txt        the crash records of this app (CUSTOM.md #21)
//
// A part that cannot be read is left out, the rest is still written.
object DiagnosticBundle {
    /** Writes the bundle into [uri]; returns the number of files in it. [running]: the core is running. */
    suspend fun export(context: Context, uri: Uri, running: Boolean): Int {
        val files = LinkedHashMap<String, ByteArray>()

        fun put(name: String, text: String) {
            files[name] = text.toByteArray(Charsets.UTF_8)
        }

        val active = try {
            withProfile { queryActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        put("summary.txt", summary(context, running, active?.name, active?.type?.name))

        withContext(Dispatchers.IO) {
            try {
                active?.let { p ->
                    val config = context.importedDir.resolve(p.uuid.toString()).resolve("config.yaml")

                    if (config.isFile) put("config.yaml", ConfigMask.mask(config.readText(Charsets.UTF_8)))
                }

                val override = context.clashDir.resolve("override.json")

                if (override.isFile) put("override.json", ConfigMask.mask(override.readText(Charsets.UTF_8)))

                val history = context.filesDir.resolve("custom/ip_history.json")

                if (history.isFile) put("ip_history.json", history.readText(Charsets.UTF_8))

                context.filesDir.resolve("custom/crash").listFiles()?.filter { it.isFile }?.forEach {
                    put("crash/" + it.name, it.readText(Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }

        if (running) {
            try {
                put("errors.json", withClash { queryCustomConnections(CustomConnections.SORT_ERRORS, "") })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }

            try {
                put("mux.json", withClash { queryCustomConnections(CustomConnections.SORT_MUX, "") })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }

            // the DNS check for the domain the user typed last on the config check page (at most a few seconds)
            val domain = context.getSharedPreferences("custom_config_check", Context.MODE_PRIVATE)
                .getString("domain", null)?.trim().orEmpty()

            if (domain.isNotEmpty()) {
                try {
                    val request = JSONObject().put("domain", domain).put("kind", "dns").toString()
                    val raw = withClash { queryCustomConnections(CustomConnections.SORT_CHECK, request) }

                    put("dns-check.txt", DnsCheckText.build(context, DnsCheckReport.parse(raw)))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }

        return withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("cannot open $uri")

            ZipOutputStream(output.buffered()).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }

            files.size
        }
    }

    private fun summary(context: Context, running: Boolean, profile: String?, type: String?): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }

        val core = try {
            Bridge.nativeCoreVersion().replace("_", "-")
        } catch (_: Throwable) {
            "?"
        }

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())

        return buildString {
            append("created: ").append(now).append('\n')
            append("app: ").append(version).append('\n')
            append("core: ").append(core).append('\n')
            append("android: ").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("core running: ").append(running).append('\n')
            append("active profile: ").append(profile ?: "-").append(" (").append(type ?: "-").append(")\n")
            append('\n')
            append("Passwords, uuids and keys in config.yaml / override.json are replaced by ***, links keep only their host.\n")
            append("Still inside: node server addresses, and the domains of recent failed connections (errors.json).\n")
        }
    }
}
