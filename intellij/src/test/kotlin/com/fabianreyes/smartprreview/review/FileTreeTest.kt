package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.Side
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.providers.ThreadComment
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTreeTest {
    private fun file(path: String, status: ChangedFile.Status = ChangedFile.Status.M) = ChangedFile(status, path)

    private fun shape(nodes: List<FileNode>): List<Any> = nodes.map {
        when (it) {
            is FileNode.Folder -> mapOf(it.label to shape(it.children))
            is FileNode.File -> it.file.path
        }
    }

    private fun thread(path: String, line: Int?, outdated: Boolean = false, body: String = "hi") = Thread(
        "t", path, Side.HEAD, line, null, outdated, null, false, listOf(ThreadComment("c", "ana", body, "")),
    )

    private fun draft(body: String, startLine: Int = 3, endLine: Int = 3) =
        Draft("d", "src/a.kt", startLine, endLine, null, null, body, "h".repeat(40), DraftOrigin.Manual)

    @Test
    fun `a folder holding just one folder shows as one node, folders first`() {
        val tree = fileTree(
            listOf(
                file("src/ai/review.ts"), file("src/ai/engines.ts"),
                file("packages/web/src/app.tsx", ChangedFile.Status.A), file("README.md"),
            ),
        )
        assertEquals(
            listOf(
                mapOf("packages/web/src" to listOf("packages/web/src/app.tsx")),
                mapOf("src/ai" to listOf("src/ai/engines.ts", "src/ai/review.ts")),
                "README.md",
            ),
            shape(tree),
        )
        assertEquals("packages/web/src", (tree[0] as FileNode.Folder).path)
    }

    @Test
    fun `nested folder paths accumulate the compacted labels`() {
        val tree = fileTree(listOf(file("a/b/c.kt"), file("a/d/e.kt"), file("a/f.kt")))
        val a = tree.single() as FileNode.Folder
        assertEquals("a", a.path)
        assertEquals(listOf("a/b", "a/d"), a.children.filterIsInstance<FileNode.Folder>().map { it.path })
    }

    @Test
    fun `threadPlace drops the line of an outdated thread`() {
        assertEquals("src/app.ts:12", threadPlace(thread("src/app.ts", 12)))
        assertEquals("src/app.ts", threadPlace(thread("src/app.ts", 12, outdated = true)))
        assertEquals("src/app.ts", threadPlace(thread("src/app.ts", null)))
    }

    @Test
    fun `threadText shows author and 80 chars, marked when outdated`() {
        assertEquals("ana: ${"x".repeat(80)}", threadText(thread("a", 1, body = "x".repeat(100))))
        assertEquals("${message("label.outdated")} · ana: hi", threadText(thread("a", null, outdated = true)))
    }

    @Test
    fun `byPosition orders by path then line`() {
        val sorted = byPosition(listOf(thread("b", 1), thread("a", 9), thread("a", 2), thread("a", null)))
        assertEquals(listOf("a" to null, "a" to 2, "a" to 9, "b" to 1), sorted.map { it.path to it.line })
    }

    @Test
    fun `draftPlace and draftText`() {
        assertEquals("src/a.kt:3", draftPlace(draft("x")))
        assertEquals("src/a.kt:3-5", draftPlace(draft("x", 3, 5)))
        assertEquals("  second  ", draftText(draft("\n  \n  second  \nthird")))
        assertEquals(200, draftText(draft("y".repeat(300))).length)
    }

    @Test
    fun `a thread row is one line even when the comment has several`() {
        assertEquals("ana: first line second", threadText(thread("a.kt", 3, body = "first line\n\n  second")))
    }
}
