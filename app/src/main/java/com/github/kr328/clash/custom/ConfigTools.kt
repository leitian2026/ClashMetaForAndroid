package com.github.kr328.clash.custom

import android.content.Context
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.custom.BackupDesign
import com.github.kr328.clash.service.custom.ConfigErrorText
import com.github.kr328.clash.service.custom.ConfigHistory
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CUSTOM (CUSTOM.md #27 #28, not upstream): the dialogs of "config history and checks" (started from the entry
// in the app settings, see BackupActivity). Everything here runs only after the user picked an item:
//   * versions and rollback: the versions of a profile's config.yaml that ConfigHistory kept, a diff against the
//     version in use, and restoring one (written into the pending copy and committed through the normal profile
//     manager, so the core checks it like any save);
//   * check a config: the core parses the config.yaml (the unsaved edit when there is one) and says what is wrong
//     and on which line, without loading it.
object ConfigTools {
    suspend fun run(context: Context, design: BackupDesign) {
        val profiles = withProfile { queryAll() }.filter { it.imported && it.type != Profile.Type.External }

        if (profiles.isEmpty()) {
            design.message(context.getString(DesignR.string.history_menu_title), context.getString(DesignR.string.history_no_profile))

            return
        }

        val choice = design.pick(
            context.getString(DesignR.string.history_menu_title),
            listOf(
                context.getString(DesignR.string.history_menu_history),
                context.getString(DesignR.string.history_menu_check),
            ),
        )

        when (choice) {
            0 -> {
                // a subscription (URL) is downloaded again by every save, so a restored config would be replaced at once
                val editable = profiles.filter { it.type == Profile.Type.File }
                val profile = pickProfile(context, design, editable, DesignR.string.history_no_file_profile) ?: return

                history(context, design, profile)
            }
            1 -> {
                val profile = pickProfile(context, design, profiles, DesignR.string.history_no_profile) ?: return

                check(context, design, profile)
            }
        }
    }

    private suspend fun pickProfile(
        context: Context,
        design: BackupDesign,
        profiles: List<Profile>,
        emptyText: Int,
    ): Profile? {
        if (profiles.isEmpty()) {
            design.message(context.getString(DesignR.string.history_menu_title), context.getString(emptyText))

            return null
        }

        if (profiles.size == 1) return profiles[0]

        val index = design.pick(context.getString(DesignR.string.history_pick_profile), profiles.map { it.name })

        return profiles.getOrNull(index)
    }

    // ---- versions and rollback ------------------------------------------------------------------

    private suspend fun history(context: Context, design: BackupDesign, profile: Profile) {
        val versions = ConfigHistory.list(context, profile.uuid)

        if (versions.isEmpty()) {
            design.message(profile.name, context.getString(DesignR.string.history_none))

            return
        }

        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // one label per version: when it was kept, how long it is, what changed from the next older one
        val labels = withContext(Dispatchers.IO) {
            val inUse = ConfigHistory.current(context, profile.uuid)?.readText(Charsets.UTF_8)

            versions.mapIndexed { i, v ->
                val text = v.text()
                val lines = text.lines().size
                val older = versions.getOrNull(i + 1)
                val date = format.format(Date(v.at))

                val line = if (older == null) {
                    context.getString(DesignR.string.history_version_first, date, lines)
                } else {
                    val d = LineDiff.diff(older.text().lines(), text.lines())

                    context.getString(DesignR.string.history_version_line, date, lines, d.added, d.removed)
                }

                if (inUse != null && inUse == text) line + context.getString(DesignR.string.history_in_use) else line
            }
        }

        while (true) {
            val index = design.pick(context.getString(DesignR.string.history_pick_version, profile.name), labels)
            val version = versions.getOrNull(index) ?: return

            while (true) {
                val action = design.pick(
                    format.format(Date(version.at)),
                    listOf(
                        context.getString(DesignR.string.history_action_diff),
                        context.getString(DesignR.string.history_action_restore),
                    ),
                )

                when (action) {
                    0 -> showDiff(context, design, profile, version, format)
                    1 -> {
                        if (restore(context, design, profile, version, format)) return
                    }
                    else -> break // back to the list of versions
                }
            }
        }
    }

    private suspend fun showDiff(
        context: Context,
        design: BackupDesign,
        profile: Profile,
        version: ConfigHistory.Version,
        format: SimpleDateFormat,
    ) {
        val result = withContext(Dispatchers.IO) {
            val now = ConfigHistory.current(context, profile.uuid)?.readText(Charsets.UTF_8) ?: ""

            LineDiff.diff(now.lines(), version.text().lines())
        }

        val title = context.getString(DesignR.string.history_diff_title, format.format(Date(version.at)))

        if (result.text.isEmpty()) {
            design.message(title, context.getString(DesignR.string.history_diff_same))

            return
        }

        val more = if (result.truncated > 0) "\n" + context.getString(DesignR.string.history_diff_more, result.truncated) else ""

        design.showText(
            title,
            context.getString(DesignR.string.history_diff_head, result.removed, result.added) + "\n\n" + result.text + more,
        )
    }

    /** true when the version was restored (the dialogs are done), false when the user said no or it failed. */
    private suspend fun restore(
        context: Context,
        design: BackupDesign,
        profile: Profile,
        version: ConfigHistory.Version,
        format: SimpleDateFormat,
    ): Boolean {
        val ok = design.confirm(
            context.getString(DesignR.string.history_action_restore),
            context.getString(DesignR.string.history_restore_confirm, format.format(Date(version.at))),
        )

        if (!ok) return false

        design.setStatus(context.getString(DesignR.string.history_restoring))

        val error = try {
            // patch makes sure a pending copy of the profile exists; the old config.yaml in it is replaced and the
            // normal commit checks the result and swaps it in (and ConfigHistory keeps both versions)
            withProfile { patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey) }

            withContext(Dispatchers.IO) {
                context.pendingDir.resolve(profile.uuid.toString()).resolve("config.yaml")
                    .writeText(version.text(), Charsets.UTF_8)
            }

            withProfile { commit(profile.uuid, null) }

            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the old config stays in use; the half-done pending copy is thrown away
            try {
                withProfile { release(profile.uuid) }
            } catch (_: Exception) {
            }

            e.message ?: e.javaClass.simpleName
        }

        if (error == null) {
            design.message(profile.name, context.getString(DesignR.string.history_restore_done))

            return true
        }

        design.message(profile.name, context.getString(DesignR.string.history_restore_failed, error))

        return false
    }

    // ---- check a config -------------------------------------------------------------------------

    private suspend fun check(context: Context, design: BackupDesign, profile: Profile) {
        val pending = context.pendingDir.resolve(profile.uuid.toString())
        val usePending = pending.resolve("config.yaml").isFile
        val dir = if (usePending) pending else context.importedDir.resolve(profile.uuid.toString())

        design.setStatus(context.getString(DesignR.string.history_checking))

        val answer = try {
            JSONObject(withClash { queryCustomConnections(CustomConnections.SORT_VALIDATE, dir.absolutePath) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(profile.name, e.message ?: e.javaClass.simpleName)

            return
        }

        val note = if (usePending) context.getString(DesignR.string.history_check_pending) else ""

        if (answer.optBoolean("ok")) {
            design.message(profile.name, context.getString(DesignR.string.history_check_ok) + note)

            return
        }

        val line = answer.optInt("line")
        val shown = ConfigErrorText.snippet(dir.resolve("config.yaml"), line)

        design.message(
            profile.name,
            context.getString(DesignR.string.history_check_bad, answer.optString("error")) +
                (if (shown.isNotEmpty()) "\n\n" + shown else "") + note,
        )
    }
}
