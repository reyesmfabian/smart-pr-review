package com.fabianreyes.smartprreview.ui.diff

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DiffPreviewTabTest : BasePlatformTestCase() {
    private val manager get() = FileEditorManager.getInstance(project)
    private val tabs get() = DiffPreviewTab.getInstance(project)
    private fun file(name: String) = LightVirtualFile(name, "x")

    override fun tearDown() {
        try {
            manager.openFiles.forEach { manager.closeFile(it) }
        } finally {
            super.tearDown()
        }
    }

    fun `test a one-click diff replaces the previous one`() {
        val a = file("a.txt")
        val b = file("b.txt")
        tabs.show("a", a)
        tabs.show("b", b)
        assertFalse(manager.isFileOpen(a))
        assertTrue(manager.isFileOpen(b))
    }

    fun `test a pinned diff stays when the next ones open`() {
        val a = file("a.txt")
        val b = file("b.txt")
        val c = file("c.txt")
        tabs.show("a", a)
        tabs.pin("a")
        tabs.show("b", b)
        tabs.show("c", c)
        assertTrue(manager.isFileOpen(a))
        assertFalse(manager.isFileOpen(b))
        assertTrue(manager.isFileOpen(c))
    }

    fun `test a double click that arrives before its diff loads pins it`() {
        val a = file("a.txt")
        val b = file("b.txt")
        val load = tabs.ticket() // first click: its diff starts loading
        tabs.pin("a") // second click of a double click; the first click's diff is still loading
        tabs.show("a", a, load)
        tabs.show("b", b)
        assertTrue(manager.isFileOpen(a))
        assertTrue(manager.isFileOpen(b))
    }

    fun `test a preview the user closed is left alone`() {
        val a = file("a.txt")
        val b = file("b.txt")
        val other = file("other.txt")
        tabs.show("a", a)
        manager.closeFile(a)
        manager.openFile(other, true)
        tabs.show("b", b)
        assertTrue(manager.isFileOpen(other))
        assertTrue(manager.isFileOpen(b))
    }

    fun `test a double click on the diff already in the preview pins it without a second tab`() {
        val a = file("a.txt")
        tabs.show("a", a)
        assertTrue(tabs.reveal("a")) // first click of the double click: already on screen, nothing loads
        tabs.pin("a") // second click
        tabs.show("b", file("b.txt"))
        assertTrue(manager.isFileOpen(a))
        assertEquals(2, manager.openFiles.size)
    }

    fun `test a click on a pinned diff shows its tab instead of opening another`() {
        val a = file("a.txt")
        tabs.show("a", a)
        tabs.pin("a")
        assertTrue(tabs.reveal("a"))
        assertEquals(1, manager.openFiles.size)
    }

    fun `test reveal finds nothing for another diff or a closed tab`() {
        val a = file("a.txt")
        tabs.show("a", a)
        assertFalse(tabs.reveal("b"))
        manager.closeFile(a)
        assertFalse(tabs.reveal("a"))
    }

    fun `test a double click survives another diff loading first`() {
        val a = file("a.txt")
        val b = file("b.txt")
        val c = file("c.txt")
        val loadA = tabs.ticket()
        tabs.pin("a")
        tabs.show("b", b) // clicked after the double click on a, but loaded first
        tabs.show("a", a, loadA)
        tabs.show("c", c)
        assertTrue(manager.isFileOpen(a))
        assertFalse(manager.isFileOpen(b))
        assertTrue(manager.isFileOpen(c))
    }

    fun `test a slow diff does not replace the one clicked after it`() {
        val a = file("a.txt")
        val b = file("b.txt")
        val loadA = tabs.ticket()
        val loadB = tabs.ticket()
        tabs.show("b", b, loadB)
        tabs.show("a", a, loadA) // arrives late
        assertFalse(manager.isFileOpen(a))
        assertTrue(manager.isFileOpen(b))
    }

    fun `test a double click whose diff never loaded does not pin a later click`() {
        val a = file("a.txt")
        tabs.pin("a") // its load failed: show never came
        tabs.show("a", a) // a new one-click load
        tabs.show("b", file("b.txt"))
        assertFalse(manager.isFileOpen(a))
    }
}
