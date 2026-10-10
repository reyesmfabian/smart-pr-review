package com.fabianreyes.smartprreview.ui.diff

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * VS Code's preview tab for review diffs: a diff opened with one click replaces the previous one-click diff, unless a
 * double click pinned it. "Pinned" means opened with a double click; the IDE's own tab pin is not consulted (reading
 * it needs JetBrains implementation APIs). A tab the user already closed is left alone. EDT only.
 */
@Service(Service.Level.PROJECT)
class DiffPreviewTab(private val project: Project) {
    private var preview: VirtualFile? = null
    private var previewKey: String? = null

    /** Diffs a double click pinned, by key, so a later click shows that tab instead of opening a copy. */
    private val pinned = HashMap<String, VirtualFile>()

    /** A double click whose diff had not finished loading, and the last load it can belong to. */
    private var pinKey: String? = null
    private var pinTicket = 0

    /** Loads are numbered so a slow one never replaces a diff clicked after it. */
    private var issued = 0
    private var shownTicket = 0

    /** A number for a diff about to load; pass it to [show] when it is ready. */
    fun ticket(): Int = ++issued

    /**
     * Opens [file], the diff shown under [key] and loaded under [ticket]. A double click ([pin]) keeps it; otherwise it
     * replaces the last preview, unless a diff clicked after it is already showing.
     */
    fun show(key: String, file: VirtualFile, ticket: Int = ticket()) {
        val manager = FileEditorManager.getInstance(project)
        val keep = pinKey == key && ticket <= pinTicket
        if (keep) pinKey = null
        if (!keep && ticket < shownTicket) return
        shownTicket = maxOf(shownTicket, ticket)
        // A pinned diff opens next to the preview, as in VS Code; only a new preview replaces the old one.
        val last = if (keep) null else preview?.takeIf { manager.isFileOpen(it) }
        manager.openFile(file, true)
        last?.let { manager.closeFile(it) }
        pinned.values.removeIf { !manager.isFileOpen(it) }
        if (keep) {
            pinned[key] = file
        } else {
            preview = file
            previewKey = key
        }
    }

    /** Shows the tab already holding the diff under [key], the preview or a pinned one; false when there is none to show. */
    fun reveal(key: String): Boolean {
        val manager = FileEditorManager.getInstance(project)
        val file = (preview.takeIf { previewKey == key } ?: pinned[key])?.takeIf { manager.isFileOpen(it) } ?: return false
        manager.openFile(file, true)
        return true
    }

    /** Keeps the diff shown under [key] open from now on: the preview tab, or the one still loading. */
    fun pin(key: String) {
        val shown = preview
        if (previewKey == key && shown != null) {
            pinned[key] = shown
            preview = null
            previewKey = null
        } else {
            // Belongs to the load already started for the first click of the double click.
            pinKey = key
            pinTicket = issued
        }
    }

    companion object {
        fun getInstance(project: Project): DiffPreviewTab = project.service()
    }
}
