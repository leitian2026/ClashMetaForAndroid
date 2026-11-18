package com.github.kr328.clash.custom

import com.github.kr328.clash.design.custom.editor.YamlLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.common.util.grantPermissions
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.core.model.EffectiveConfig
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.custom.CheckMark
import com.github.kr328.clash.design.custom.ConfigDiff
import com.github.kr328.clash.design.custom.editor.EditorDesign
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.custom.ConfigErrorText
import com.github.kr328.clash.service.custom.ConfigHistory
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.fileName
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// CUSTOM (CUSTOM.md #46, not upstream): the built-in text editor. "Files" opens a text file of a profile here (up to
// 1 MB; anything else, and "open with another app", still goes to the other apps). The file is read and written
// through the same document provider the other apps use, so a saved config.yaml is the profile's unsaved copy,
// exactly as if an external editor had written it; the profile page's own "save" then lets the core check it.
// Nothing runs on its own: the file is read once when the page opens and written when the user taps save.
//
// CUSTOM.md #56: for the config.yaml of a profile the user edits (the "split" case), "save" only keeps a history
// version (ConfigHistory), "apply" writes the profile's pending copy and commits it (the core checks it first),
// and the menu opens the history and the compare against the config in use. Every other file is saved as before.
class EditorActivity : BaseActivity<EditorDesign>() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // from Android 11 the keyboard is told in the insets and the page makes room for it itself; before that
        // the window is made smaller
        window.setSoftInputMode(
            if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )

        super.onCreate(savedInstanceState)
    }

    override suspend fun main() {
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: id.substringAfterLast('/')
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "" // the profile's name, when opened from the profile list
        val uri = FilesClient(this).buildDocumentUri(id)

        // the config check works on the config.yaml at the top of a profile
        val canCheck = profile.isNotEmpty() && id == "$profile/config.yaml"

        // CUSTOM.md #50: mistakes are looked for in YAML files; the update notice is only for the profile's config.yaml
        val lint = name.substringAfterLast('.', "").lowercase().let { it == "yaml" || it == "yml" }
        // CUSTOM.md #51: the service refuses to write a profile that is not a local file, so it is only shown
        val readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)

        profileId = profile
        openedName = name

        // CUSTOM.md #56
        split = canCheck && !readOnly
        profileTitle = title.ifBlank { name }

        val design = EditorDesign(this, name, canCheck, name == "config.yaml", lint, readOnly, split,
            displayTitle = if (name == "config.yaml") title.ifBlank { name } else name)

        setContentDesign(design)

        try {
            design.load(withContext(Dispatchers.IO) { read(uri) })
            if (split) refreshApplyState(design)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(name, getString(R.string.editor_open_failed, e.message ?: e.javaClass.simpleName))

            return finish()
        }

        while (isActive) {
            when (design.requests.receive()) {
                EditorDesign.Request.Save -> if (!readOnly) {
                    if (split) saveHistory(design) else save(design, uri)
                }
                EditorDesign.Request.Apply -> if (split) apply(design, uri)
                EditorDesign.Request.History -> if (split) history(design)
                EditorDesign.Request.Compare -> if (split) compare(design, getString(R.string.editor_compare_title))
                EditorDesign.Request.JumpDefinition -> {
                    val (text, offset) = design.definitionContext()
                    val resolution = withContext(Dispatchers.Default) {
                        YamlLint.resolve(YamlLint.links(text), text, offset)
                    }
                    // CUSTOM.md #134: public properties of another module cannot be smart cast, so take them into locals
                    val link = resolution.link
                    val definitionLine = resolution.definitionLine
                    when {
                        link == null -> design.toast(getString(R.string.editor_goto_none))
                        resolution.builtIn -> design.toast(getString(R.string.editor_goto_builtin, link.name))
                        definitionLine == null -> design.toast(getString(R.string.editor_goto_missing, link.name))
                        else -> design.goToLine(definitionLine + 1, false)
                    }
                }
                EditorDesign.Request.Check -> check(design, uri, profile)
                EditorDesign.Request.External -> openExternal(design, uri)
                EditorDesign.Request.SaveAs -> saveAs(design, suggestName(name, title))
                EditorDesign.Request.ImportFrom -> importFrom(design)
                EditorDesign.Request.Leave -> if (leave(design, uri)) finish()
            }
        }
    }

    override fun onBackPressed() {
        design?.requests?.trySend(EditorDesign.Request.Leave)
    }

    private fun read(uri: Uri): String {
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("cannot open")

        return stream.use { readText(it) }
    }

    // CUSTOM.md #47: the text is laid out as a whole, so a file over the limit is refused (also when the page is
    // opened straight from the profile list, which does not look at the size first)
    private fun readText(stream: java.io.InputStream): String {
        val bytes = stream.readBytes()

        if (bytes.size > MAX_SIZE) throw IOException(getString(R.string.editor_too_big))

        return String(bytes, Charsets.UTF_8)
    }

    // CUSTOM.md #47: the file name offered by "save to anywhere": the profile's name for its config.yaml
    private fun suggestName(name: String, title: String): String {
        if (name != "config.yaml" || title.isBlank()) return name

        return title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".yaml"
    }

    // CUSTOM.md #47: writes the text on the page to a file the user picks anywhere on the phone (the system file
    // picker); the page's own file and its unsaved state are not touched
    private suspend fun saveAs(design: EditorDesign, suggested: String) {
        val bytes = design.currentText().toByteArray(Charsets.UTF_8)

        val target: Uri = startActivityForResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"), // text/plain would add ".txt" to "x.yaml"
            suggested,
        ) ?: return

        try {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openOutputStream(target, "wt") ?: throw IOException("cannot open")

                stream.use { it.write(bytes) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(
                getString(R.string.editor_save_as),
                getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName),
            )

            return
        }

        design.toast(getString(R.string.editor_saved_to, target.fileName ?: target.toString()))
    }

    // CUSTOM.md #47: reads a file the user picks anywhere on the phone and puts it in place of the text (one undo
    // step; it is not saved until the user taps save)
    private suspend fun importFrom(design: EditorDesign) {
        val source: Uri = startActivityForResult(ActivityResultContracts.GetContent(), "*/*") ?: return

        val text = try {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openInputStream(source) ?: throw IOException("cannot open")

                stream.use { readText(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(
                getString(R.string.editor_import_from),
                getString(R.string.editor_import_failed, e.message ?: e.javaClass.simpleName),
            )

            return
        }

        val undoable = design.replaceText(text)

        if (split) refreshApplyState(design)

        design.toast(
            getString(
                if (undoable) R.string.editor_imported else R.string.editor_imported_no_undo,
                source.fileName ?: source.toString(),
            )
        )
    }

    private var profileId = ""
    private var openedName = ""
    private var split = false // CUSTOM.md #56
    private var profileTitle = ""

    private suspend fun save(design: EditorDesign, uri: Uri): Boolean {
        val bytes = design.currentText().toByteArray(Charsets.UTF_8)

        try {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open")

                stream.use { it.write(bytes) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(
                getString(R.string.editor_save),
                getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName),
            )

            return false
        }

        design.markSaved()

        if (profileId.isEmpty()) {
            design.toast(getString(R.string.editor_saved))

            return true
        }

        return applyProfile(design)
    }

    // CUSTOM.md #51: what was written is the profile's unsaved copy; this is the profile page's "save": the core checks
    // it, and when it passes the profile in use is replaced and (if it is the active one) loaded again. When the core
    // refuses, the copy stays as it is, the reason and the line are shown, and nothing in use changes.
    private suspend fun applyProfile(design: EditorDesign): Boolean {
        try {
            withProfile { commit(UUID.fromString(profileId)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val line = ConfigErrorText.lineOf(e.message)

            if (line > 0 && openedName == "config.yaml") design.goToLine(line, true)

            design.message(
                getString(R.string.editor_apply_failed_title),
                getString(R.string.editor_apply_failed, e.message ?: e.javaClass.simpleName),
            )

            return false
        }

        val selected = try {
            withProfile { queryActive()?.uuid == profileUuid() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        design.toast(
            getString(R.string.editor_saved_applied) +
                if (selected) "" else " " + getString(R.string.editor_applied_not_loaded),
        )

        return true
    }

    // true when the page may be closed: nothing is unsaved, or the user chose to save (and it worked) or to discard
    private suspend fun leave(design: EditorDesign, uri: Uri): Boolean {
        if (split) return leaveSplit(design)

        if (!design.dirty) return true

        return when (design.confirmLeave()) {
            0 -> save(design, uri)
            1 -> true
            else -> false
        }
    }

    // lets the core read the profile's config.yaml: whether it is accepted, and which line it names when it is not.
    // CUSTOM.md #56: for a config the user edits the text on the page is what is checked (it is written to a temporary
    // folder, nothing of the profile is touched); for the other cases it is the profile's own copy, as before
    private suspend fun check(design: EditorDesign, uri: Uri, profile: String) {
        if (profile.isEmpty()) return

        val title = getString(R.string.editor_check_title)
        var temp: File? = null

        val dir = if (split) {
            val text = design.currentText()

            try {
                withContext(Dispatchers.IO) {
                    val folder = File(cacheDir, "editor_check")

                    folder.deleteRecursively()
                    folder.mkdirs()
                    File(folder, "config.yaml").writeBytes(text.toByteArray(Charsets.UTF_8))

                    folder
                }.also { temp = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                design.message(title, e.message ?: e.javaClass.simpleName)

                return
            }
        } else {
            val pending = pendingDir.resolve(profile)

            if (pending.resolve("config.yaml").isFile) pending else importedDir.resolve(profile)
        }

        try {
            val answer = try {
                JSONObject(withClash { queryCustomConnections(CustomConnections.SORT_VALIDATE, dir.absolutePath) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                design.message(title, e.message ?: e.javaClass.simpleName)

                return
            }

            if (answer.optBoolean("ok")) {
                design.toast(getString(R.string.editor_check_ok))

                return
            }

            val line = answer.optInt("line")
            val shown = ConfigErrorText.snippet(dir.resolve("config.yaml"), line)

            if (line > 0) design.goToLine(line, true)

            design.message(
                title,
                getString(R.string.editor_check_bad, answer.optString("error")) + (if (shown.isNotEmpty()) "\n\n$shown" else ""),
            )
        } finally {
            temp?.let { t -> withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { t.deleteRecursively() } }
        }
    }

    // ---- CUSTOM.md #56: history versions, apply, compare ----------------------------------------------

    /** Compare once at the specified lifecycle points, off the UI thread. */
    private suspend fun refreshApplyState(design: EditorDesign) {
        val text = design.currentText()
        val uuid = profileUuid()
        val matches = withContext(Dispatchers.IO) {
            ConfigHistory.isInUse(this@EditorActivity, uuid, text)
        }
        design.setApplied(matches)
    }

    private fun profileUuid(): UUID = UUID.fromString(profileId)

    /** "Save as history version": only written to the history, the profile's pending copy and the config in use are not touched. */
    private suspend fun saveHistory(design: EditorDesign, quiet: Boolean = false): Boolean {
        val text = design.currentText()
        val title = getString(R.string.editor_save_history)

        val result = try {
            withContext(Dispatchers.IO) { ConfigHistory.add(this@EditorActivity, profileUuid(), text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(title, getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName))

            return false
        }

        when (result.status) {
            ConfigHistory.ADDED, ConfigHistory.EXISTS -> {
                design.markSaved()

                if (!quiet) {
                    val inUse = withContext(Dispatchers.IO) { ConfigHistory.isInUse(this@EditorActivity, profileUuid(), text) }

                    design.toast(
                        getString(
                            when {
                                result.status == ConfigHistory.EXISTS -> R.string.editor_history_exists
                                inUse -> R.string.editor_history_saved_in_use
                                else -> R.string.editor_history_saved
                            },
                        ),
                    )
                }

                return true
            }
            ConfigHistory.EMPTY -> design.message(title, getString(R.string.editor_history_empty))
            else -> design.message(title, getString(R.string.editor_history_too_big))
        }

        return false
    }

    /**
     * "Apply": the text on the page is kept as a history version first (the config in use is kept before it, so the
     * list stays in order), then written into the profile's pending copy and committed. The core checks it before
     * anything is replaced: when it refuses, the config in use is not changed, the version in the history is marked
     * as refused, the pending copy that this made is dropped and the line the core names is shown.
     */
    /** The profile query API is authoritative: committing a non-selected profile does not load it. */
    private suspend fun showAppliedResult(design: EditorDesign, historyKept: Boolean) {
        val selected = try {
            withProfile { queryActive()?.uuid == profileUuid() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }

        val message = if (historyKept) R.string.editor_applied else R.string.editor_applied_no_history
        val suffix = if (selected) "" else " " + getString(R.string.editor_applied_not_loaded)
        design.toast(getString(message) + suffix)
    }

    private suspend fun apply(design: EditorDesign, uri: Uri): Boolean {
        val uuid = profileUuid()
        val text = design.currentText()

        if (withContext(Dispatchers.IO) { ConfigHistory.isInUse(this@EditorActivity, uuid, text) }) {
            withContext(Dispatchers.IO) { ConfigHistory.keepInUse(this@EditorActivity, uuid) }
            design.markSaved()
            design.setApplied(true)
            showAppliedResult(design, historyKept = true)

            return true
        }

        val added = try {
            withContext(Dispatchers.IO) {
                ConfigHistory.keepInUse(this@EditorActivity, uuid)
                ConfigHistory.add(this@EditorActivity, uuid, text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        val kept = added != null && (added.status == ConfigHistory.ADDED || added.status == ConfigHistory.EXISTS)

        if (kept) design.markSaved()

        val hadPending = try {
            withProfile { queryByUUID(uuid) }?.pending == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true // not known: nothing is dropped afterwards
        }

        try {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open")

                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }

            withProfile { commit(uuid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            added?.version?.let { v -> withContext(Dispatchers.IO) { ConfigHistory.markBad(v) } }

            // the copy that this apply made is not left behind (a copy that was there before is not touched)
            if (!hadPending) {
                try {
                    withProfile { release(uuid) }
                } catch (_: Exception) {
                }
            }

            val line = ConfigErrorText.lineOf(e.message)

            if (line > 0) design.goToLine(line, true)

            design.message(
                getString(R.string.editor_apply_failed_title),
                getString(R.string.editor_apply_failed, e.message ?: e.javaClass.simpleName),
            )

            return false
        }

        design.markSaved()
        refreshApplyState(design)
        showAppliedResult(design, historyKept = kept)

        return true
    }

    // CUSTOM.md #70: the version most recently loaded into the page from the history list (its time), 0 = none; the
    // list draws that row on a colour
    private var openedAt = 0L

    /** "History versions": the list of the versions of this config; the one picked is loaded into the page, not applied. */
    private suspend fun history(design: EditorDesign) {
        val uuid = profileUuid()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // the history of profiles that are gone (or are not edited by the user) is cleared when the list is opened
        try {
            val ids = withProfile { queryAll() }
                .filter { it.imported && it.type == Profile.Type.File }
                .map { it.uuid.toString() }
                .toSet()

            if (uuid.toString() in ids) {
                withContext(Dispatchers.IO) { ConfigHistory.cleanOrphans(this@EditorActivity, ids) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        val versions = withContext(Dispatchers.IO) { ConfigHistory.list(this@EditorActivity, uuid).toMutableList() }

        if (versions.isEmpty()) {
            design.message(profileTitle, getString(R.string.history_none))

            return
        }

        // one label per version: when it was kept, how long it is, what changed from the next older one, its state
        val pageText = if (openedAt != 0L) design.currentText() else "" // CUSTOM.md #70: to tell whether the opened version was changed since

        val inUse = withContext(Dispatchers.IO) { ConfigHistory.inUse(this@EditorActivity, uuid, versions) }
        val labels = withContext(Dispatchers.IO) {
            versions.mapIndexed { i, v ->
                val older = versions.getOrNull(i + 1)
                val olderAt = older?.at ?: 0L
                val stat = ConfigHistory.readStat(v, olderAt) ?: run {
                    val text = v.text()
                    val diff = if (older == null) null else LineDiff.diff(older.text().lines(), text.lines())
                    ConfigHistory.Stat(text.lines().size, diff?.added ?: 0, diff?.removed ?: 0).also {
                        ConfigHistory.writeStat(v, olderAt, it)
                    }
                }
                val date = format.format(Date(v.at))

                var label = if (older == null) {
                    getString(R.string.history_version_first, date, stat.lines)
                } else {
                    getString(R.string.history_version_line, date, stat.lines, stat.added, stat.removed)
                }

                var appliedRange: IntRange? = null
                if (v == inUse) {
                    val appliedLabel = getString(R.string.history_in_use)
                    label += appliedLabel
                    val appliedText = appliedLabel.substringAfter('·').trim()
                    val appliedStart = label.lastIndexOf(appliedText)
                    if (appliedStart >= 0) appliedRange = appliedStart until (appliedStart + appliedText.length)
                }

                if (v.bad) {
                    label += getString(R.string.history_bad)
                } else if (v.saved && v != inUse) {
                    label += getString(R.string.history_saved_unapplied)
                }

                if (v.verified) label += getString(R.string.history_verified) // CUSTOM.md #38

                // CUSTOM.md #70: the one that was opened (and whether the page differs from it now)
                if (v.at == openedAt) {
                    // CUSTOM.md #134: the text is only read here (at most one version), not for every row
                    label += getString(if (v.text() == pageText) R.string.history_opened else R.string.history_opened_changed)
                }

                val painted = CheckMark.paint(this@EditorActivity, label) ?: label
                appliedRange?.let { range ->
                    val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    android.text.SpannableStringBuilder(painted).apply {
                        val density = resources.displayMetrics.density
                        val horizontalPadding = 5f * density
                        val verticalPadding = 2f * density
                        val badgeColor = android.graphics.Color.parseColor(if (night) "#388E3C" else "#2E9D50")
                        setSpan(
                            object : android.text.style.ReplacementSpan() {
                                override fun getSize(
                                    paint: android.graphics.Paint,
                                    text: CharSequence,
                                    start: Int,
                                    end: Int,
                                    fm: android.graphics.Paint.FontMetricsInt?,
                                ): Int {
                                    return (paint.measureText(text, start, end) + horizontalPadding * 2).toInt()
                                }

                                override fun draw(
                                    canvas: android.graphics.Canvas,
                                    text: CharSequence,
                                    start: Int,
                                    end: Int,
                                    x: Float,
                                    top: Int,
                                    y: Int,
                                    bottom: Int,
                                    paint: android.graphics.Paint,
                                ) {
                                    val width = paint.measureText(text, start, end)
                                    val metrics = paint.fontMetrics
                                    val rect = android.graphics.RectF(
                                        x,
                                        y + metrics.ascent - verticalPadding,
                                        x + width + horizontalPadding * 2,
                                        y + metrics.descent + verticalPadding,
                                    )
                                    val background = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        color = badgeColor
                                    }
                                    canvas.drawRoundRect(rect, 4f * density, 4f * density, background)
                                    val originalColor = paint.color
                                    paint.color = android.graphics.Color.BLACK
                                    canvas.drawText(text.subSequence(start, end).toString(), x + horizontalPadding, y.toFloat(), paint)
                                    paint.color = originalColor
                                }
                            },
                            range.first,
                            range.last + 1,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                } ?: painted // CUSTOM.md #57: ✓ green, ✗ red
            }
        }

        // CUSTOM.md #70: a long press pins / unpins; the row shows a pin in front while it is pinned
        val base = labels.toMutableList()
        fun shown(i: Int): CharSequence = if (versions[i].pinned) android.text.TextUtils.concat("\uD83D\uDCCC ", base[i]) else base[i]

        val index = design.pickVersion(
            getString(R.string.history_pick_version, profileTitle),
            getString(R.string.editor_pick_hint, ConfigHistory.MAX_PINNED),
            versions.indices.map { shown(it) },
            versions.indexOfFirst { it.at == openedAt },
            onPin = { i ->
            val v = versions[i]
            val want = !v.pinned

            if (ConfigHistory.setPinned(this@EditorActivity, uuid, v, want)) {
                android.widget.Toast.makeText(
                    this@EditorActivity,
                    if (want) R.string.editor_pinned else R.string.editor_unpinned,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()

                shown(i)
            } else {
                android.widget.Toast.makeText(
                    this@EditorActivity,
                    getString(R.string.editor_pin_limit, ConfigHistory.MAX_PINNED),
                    android.widget.Toast.LENGTH_LONG,
                ).show()

                null
            }
            },
            onDelete = { i ->
                val v = versions[i]
                val deleted = withContext(Dispatchers.IO) { ConfigHistory.delete(this@EditorActivity, uuid, v) }
                if (deleted) {
                    if (openedAt == v.at) openedAt = 0L
                    0
                } else if (v == inUse) {
                    1
                } else {
                    null
                }
            },
        )
        val version = versions.getOrNull(index) ?: return

        if (design.dirty) {
            val ok = design.ask(
                getString(R.string.editor_history_overwrite_title),
                getString(R.string.editor_history_overwrite),
                getString(R.string.editor_history_load),
            )

            if (!ok) return
        }

        val text = try {
            withContext(Dispatchers.IO) { version.text() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(profileTitle, getString(R.string.editor_open_failed, e.message ?: e.javaClass.simpleName))

            return
        }

        val date = format.format(Date(version.at))
        val undoable = design.replaceText(text)

        openedAt = version.at // CUSTOM.md #70

        design.markSaved() // it is a version of the history, so there is nothing on the page that is not kept
        refreshApplyState(design)
        design.toast(getString(if (undoable) R.string.editor_history_loaded else R.string.editor_history_loaded_no_undo, date))

        // CUSTOM.md #58: no page, no dialog: what this version changes against the config in use is marked on the editor
        // itself (computed once now, nothing in the background); the menu's compare page is unchanged
        val uuidNow = profileUuid()
        val marks = try {
            withContext(Dispatchers.Default) {
                val used = ConfigHistory.current(this@EditorActivity, uuidNow)?.readText(Charsets.UTF_8) ?: ""

                ConfigDiff.marks(used, text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        design.showDiffMarks(marks)
    }

    /** The page of what the text on the page would change against the config in use. */
    private suspend fun compare(design: EditorDesign, title: String) {
        val uuid = profileUuid()

        design.showCompare(title) {
            val text = design.currentText()

            try {
                withContext(Dispatchers.Default) {
                    val used = ConfigHistory.current(this@EditorActivity, uuid)?.readText(Charsets.UTF_8) ?: ""

                    ConfigDiff.diff(used, text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null as EffectiveConfig?
            }
        }
    }

    // leaving, for a config the user edits: the changes are kept as a history version or dropped; the apply is a
    // separate step, so when what is on the page (or a saved version) is not applied, a note says so
    private suspend fun leaveSplit(design: EditorDesign): Boolean {
        var discarded = false

        if (design.dirty) {
            when (design.confirmLeave()) {
                0 -> if (!saveHistory(design, quiet = true)) return false
                1 -> discarded = true
                else -> return false
            }
        }

        val uuid = profileUuid()
        val text = design.currentText()

        val note = try {
            withContext(Dispatchers.IO) {
                when {
                    !discarded && !ConfigHistory.isInUse(this@EditorActivity, uuid, text) -> R.string.editor_leave_unapplied
                    else -> 0
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }

        if (note != 0) Toast.makeText(applicationContext, getString(note), Toast.LENGTH_LONG).show()

        return true
    }

    // the page is closed after this: text that is edited elsewhere would be out of date here
    private suspend fun openExternal(design: EditorDesign, uri: Uri) {
        // CUSTOM.md #56: the other app opens the profile's own file; what is on the page is kept as a history version
        if (design.dirty && !(if (split) saveHistory(design, quiet = true) else save(design, uri))) return

        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").grantPermissions())
        } catch (e: Exception) {
            design.message(getString(R.string.editor_external), e.message ?: e.javaClass.simpleName)

            return
        }

        finish()
    }

    companion object {
        private const val EXTRA_ID = "editor_document_id"
        private const val EXTRA_NAME = "editor_name"
        private const val EXTRA_PROFILE = "editor_profile"
        private const val EXTRA_TITLE = "editor_title" // CUSTOM.md #47
        private const val EXTRA_READ_ONLY = "editor_read_only" // CUSTOM.md #51

        // CUSTOM.md #50: a text over 3000 lines is shown a window at a time, so the size is limited by memory (the whole
        // text is kept as strings), not by the layout; a bigger file goes to another app
        private const val MAX_SIZE = 8_000_000

        private val textTypes = setOf("yaml", "yml", "json", "txt", "conf", "ini", "js", "list", "toml")

        /** Whether "Files" opens a file of this name and size here (and not in another app). */
        fun canOpen(name: String, size: Long): Boolean {
            return name.substringAfterLast('.', "").lowercase() in textTypes && size <= MAX_SIZE.toLong()
        }

        fun intent(
            context: Context,
            documentId: String,
            name: String,
            profile: UUID,
            title: String = "",
            readOnly: Boolean = false, // CUSTOM.md #51: the profile is not a local file, its files cannot be written
        ): Intent {
            return Intent(context, EditorActivity::class.java)
                .putExtra(EXTRA_ID, documentId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_PROFILE, profile.toString())
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_READ_ONLY, readOnly)
        }
    }
}
