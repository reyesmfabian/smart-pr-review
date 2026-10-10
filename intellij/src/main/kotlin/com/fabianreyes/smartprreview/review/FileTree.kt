package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.Thread

/** Untrusted text on one line: every run of whitespace (newlines too) becomes one space. */
fun flat(text: String): String = text.replace(Regex("\\s+"), " ").trim()

/** [flat] text that ends in "…" past [max] chars. */
fun oneLine(text: String, max: Int): String {
    val line = flat(text)
    return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
}

/** One line for a thread: "author: body" (80 chars of body, on one line), prefixed when outdated; "" without comments. */
fun threadText(t: Thread): String {
    val first = t.comments.firstOrNull() ?: return ""
    val text = "${first.author}: ${flat(first.body).take(80)}"
    return if (t.isOutdated) "${message("label.outdated")} · $text" else text
}

/** The first non-empty line of a draft. */
fun draftText(d: Draft): String = d.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(200)

fun severityLabel(s: DraftOrigin.Severity): String = when (s) {
    DraftOrigin.Severity.ERROR -> message("label.error")
    DraftOrigin.Severity.WARNING -> message("label.warning")
    DraftOrigin.Severity.INFO -> message("label.info")
}

/** A selection as drafts store it: 0-based lines, 1-based columns (end exclusive) only when text is selected. */
data class SelectedRange(val startLine: Int, val endLine: Int, val startColumn: Int?, val endColumn: Int?)

/**
 * The selection as the editor shows it: one that ends at column 0 of a later line stops at the end of the line before
 * (as selectedRange in the VS Code extension). Positions are 0-based; [lineLength] gives a line's length.
 */
fun selectedRange(startLine: Int, startCol: Int, endLine: Int, endCol: Int, lineLength: (Int) -> Int): SelectedRange {
    var eLine = endLine
    var eCol = endCol
    if (endCol == 0 && endLine > startLine) {
        eLine = endLine - 1
        eCol = lineLength(eLine)
    }
    val empty = eLine == startLine && eCol == startCol
    return SelectedRange(startLine, eLine, if (empty) null else startCol + 1, if (empty) null else eCol + 1)
}

sealed interface FileNode {
    data class Folder(val path: String, val label: String, val children: List<FileNode>) : FileNode
    data class File(val file: ChangedFile) : FileNode
}

private class FolderBuilder {
    val folders = sortedMapOf<String, FolderBuilder>(String.CASE_INSENSITIVE_ORDER.thenComparing(naturalOrder()))
    val files = mutableListOf<ChangedFile>()
}

/**
 * The changed files in their folders, folders first. A folder holding just one folder shows as one node
 * ("src/ai"), as the Explorer's compact folders do.
 */
fun fileTree(files: List<ChangedFile>): List<FileNode> {
    val root = FolderBuilder()
    for (file in files) {
        var folder = root
        for (part in file.path.split("/").dropLast(1)) folder = folder.folders.getOrPut(part) { FolderBuilder() }
        folder.files += file
    }
    fun nodes(folder: FolderBuilder, prefix: String): List<FileNode> =
        folder.folders.map { (name, first) ->
            var label = name
            var sub = first
            while (sub.files.isEmpty() && sub.folders.size == 1) {
                val (next, only) = sub.folders.entries.single()
                label = "$label/$next"
                sub = only
            }
            FileNode.Folder("$prefix$label", label, nodes(sub, "$prefix$label/"))
        } + folder.files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path }).map { FileNode.File(it) }
    return nodes(root, "")
}

fun byPosition(threads: List<Thread>): List<Thread> =
    threads.sortedWith(compareBy<Thread, String>(String.CASE_INSENSITIVE_ORDER) { it.path }.thenBy { it.line ?: 0 })

/** "src/app.ts:12", or the path alone for an outdated thread, which has no line. */
fun threadPlace(t: Thread): String = t.line?.takeIf { it != 0 && !t.isOutdated }?.let { "${t.path}:$it" } ?: t.path

/** "src/a.kt:3" or "src/a.kt:3-5". */
fun draftPlace(d: Draft): String = if (d.startLine == d.endLine) "${d.path}:${d.startLine}" else "${d.path}:${d.startLine}-${d.endLine}"
