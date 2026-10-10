package com.fabianreyes.smartprreview.ui.diff

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.popup.AbstractPopup
import javax.swing.JPanel

class CommentPopupsTest : BasePlatformTestCase() {
    fun `test long comment bodies wrap and scroll vertically even without spaces`() {
        for (editable in listOf(true, false)) {
            val text = "word".repeat(2000) + "\n" + "A long comment. ".repeat(1000)
            val body = commentTextArea(text, editable, 4)
            val pane = JBScrollPane(body)
            pane.setSize(300, 120)
            pane.doLayout()
            pane.viewport.doLayout()
            assertTrue(body.lineWrap)
            assertTrue(body.wrapStyleWord)
            assertTrue(body.scrollableTracksViewportWidth)
            assertEquals(text, body.text)
            assertEquals(pane.viewport.extentSize.width, body.width)
            assertTrue(body.preferredSize.height > pane.viewport.extentSize.height)
        }
    }

    fun `test a comment box survives a click outside, so typed text is not lost`() {
        val popup = commentPopup(JPanel(), JBTextArea())
        try {
            assertFalse((popup as AbstractPopup).isCancelOnClickOutside)
            // Esc still closes it, as the Cancel button does.
            assertTrue(popup.isCancelKeyEnabled)
        } finally {
            Disposer.dispose(popup)
        }
    }

    fun `test a comment box closes when another window opens, so it never covers a dialog`() {
        val popup = commentPopup(JPanel(), JBTextArea())
        try {
            // No public getter: the builder's setCancelOnOtherWindowOpen lands in this field.
            val field = AbstractPopup::class.java.getDeclaredField("myCancelOnWindow").apply { isAccessible = true }
            assertTrue(field.getBoolean(popup))
        } finally {
            Disposer.dispose(popup)
        }
    }
}
