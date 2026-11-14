package com.github.kr328.clash.design.custom.editor

// CUSTOM (CUSTOM.md #46, not upstream): undo and redo of the editor. An edit is "at [start] the text [removed] was
// replaced by [inserted]"; typed characters that follow each other (and backspaces) are one step. No Android classes.
class UndoStack(private val limit: Int = 400) {
    class Edit(val start: Int, val removed: String, val inserted: String)

    private val undo = ArrayList<Edit>()
    private val redo = ArrayList<Edit>()

    // set after an undo, a redo or a jump: the next edit starts a step of its own
    private var sealed = true

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun clear() {
        undo.clear()
        redo.clear()
        sealed = true
    }

    fun seal() {
        sealed = true
    }

    /** [forceMerge]: an edit made by the editor itself right after the user's (the indent after a typed new line). */
    fun record(edit: Edit, forceMerge: Boolean = false) {
        redo.clear()

        val last = undo.lastOrNull()

        if (last != null && !sealed) {
            val merged = merge(last, edit, forceMerge)

            if (merged != null) {
                undo[undo.lastIndex] = merged

                return
            }
        }

        undo.add(edit)
        sealed = false

        if (undo.size > limit) undo.removeAt(0)
    }

    fun popUndo(): Edit? {
        val edit = undo.removeLastOrNull() ?: return null

        redo.add(edit)
        sealed = true

        return edit
    }

    fun popRedo(): Edit? {
        val edit = redo.removeLastOrNull() ?: return null

        undo.add(edit)
        sealed = true

        return edit
    }

    private fun merge(a: Edit, b: Edit, force: Boolean): Edit? {
        // typing on: the new text is right behind the old
        if (a.removed.isEmpty() && b.removed.isEmpty() && a.inserted.isNotEmpty() && b.inserted.isNotEmpty() &&
            b.start == a.start + a.inserted.length
        ) {
            if (force || (b.inserted.length == 1 && b.inserted[0] != '\n' && a.inserted.length < 60)) {
                return Edit(a.start, "", a.inserted + b.inserted)
            }

            return null
        }

        // backspace, or delete forward, one character at a time
        if (a.inserted.isEmpty() && b.inserted.isEmpty() && a.removed.isNotEmpty() && b.removed.length == 1) {
            if (b.start + 1 == a.start) return Edit(b.start, b.removed + a.removed, "")
            if (b.start == a.start) return Edit(a.start, a.removed + b.removed, "")
        }

        return null
    }
}
