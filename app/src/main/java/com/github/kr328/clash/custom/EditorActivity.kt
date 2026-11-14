package com.github.kr328.clash.custom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.view.WindowManager
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.common.util.grantPermissions
import com.github.kr328.clash.core.custom.CustomConnections
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.custom.editor.EditorDesign
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.custom.ConfigErrorText
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.fileName
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

// CUSTOM (CUSTOM.md #46, not upstream): the built-in text editor. "Files" opens a text file of a profile here (up to
// 1 MB; anything else, and "open with another app", still goes to the other apps). The file is read and written
// through the same document provider the other apps use, so a saved config.yaml is the profile's unsaved copy,
// exactly as if an external editor had written it; the profile page's own "save" then lets the core check it.
// Nothing runs on its own: the file is read once when the page opens and written when the user taps save.
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
        val updateNotice = name == "config.yaml" && intent.getBooleanExtra(EXTRA_UPDATES, false)
        val design = EditorDesign(this, name, canCheck, name == "config.yaml", lint, updateNotice)

        setContentDesign(design)

        try {
            design.load(withContext(Dispatchers.IO) { read(uri) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            design.message(name, getString(R.string.editor_open_failed, e.message ?: e.javaClass.simpleName))

            return finish()
        }

        while (isActive) {
            when (design.requests.receive()) {
                EditorDesign.Request.Save -> save(design, uri)
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

        design.toast(
            getString(
                if (undoable) R.string.editor_imported else R.string.editor_imported_no_undo,
                source.fileName ?: source.toString(),
            )
        )
    }

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
        design.toast(getString(R.string.editor_saved))

        return true
    }

    // true when the page may be closed: nothing is unsaved, or the user chose to save (and it worked) or to discard
    private suspend fun leave(design: EditorDesign, uri: Uri): Boolean {
        if (!design.dirty) return true

        return when (design.confirmLeave()) {
            0 -> save(design, uri)
            1 -> true
            else -> false
        }
    }

    // saves what is on the page, then lets the core read the profile's config.yaml: whether it is accepted, and which
    // line it names when it is not
    private suspend fun check(design: EditorDesign, uri: Uri, profile: String) {
        if (profile.isEmpty()) return

        if (design.dirty && !save(design, uri)) return

        val pending = pendingDir.resolve(profile)
        val dir = if (pending.resolve("config.yaml").isFile) pending else importedDir.resolve(profile)
        val title = getString(R.string.editor_check_title)

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
    }

    // the page is closed after this: text that is edited elsewhere would be out of date here
    private suspend fun openExternal(design: EditorDesign, uri: Uri) {
        if (design.dirty && !save(design, uri)) return

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
        private const val EXTRA_UPDATES = "editor_updates" // CUSTOM.md #50

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
            updatesFromUrl: Boolean = false, // CUSTOM.md #50: an update downloads the profile again and replaces the edits
        ): Intent {
            return Intent(context, EditorActivity::class.java)
                .putExtra(EXTRA_ID, documentId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_PROFILE, profile.toString())
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_UPDATES, updatesFromUrl)
        }
    }
}
