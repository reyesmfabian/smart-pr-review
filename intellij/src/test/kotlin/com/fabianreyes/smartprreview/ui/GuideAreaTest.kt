package com.fabianreyes.smartprreview.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.JButton
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource

class GuideAreaTest : BasePlatformTestCase() {
    fun `test the guidance reads as a label even where text areas are monospaced`() {
        // The IDE's theme gives text areas a monospaced font; the headless test theme does not.
        val before = UIManager.get("TextArea.font")
        UIManager.put("TextArea.font", FontUIResource(Font.MONOSPACED, Font.PLAIN, 13))
        try {
            val area = guideArea()
            assertEquals(JBUI.Fonts.label().family, area.font.family)
            // A theme change runs updateUI again; the label font must survive it.
            area.updateUI()
            assertEquals(JBUI.Fonts.label().family, area.font.family)
            assertFalse(area.isEditable)
            assertTrue(area.lineWrap)
        } finally {
            UIManager.put("TextArea.font", before)
        }
    }

    fun `test the header buttons wrap instead of hiding when the panel is narrow`() {
        val ai = JButton("Review with AI")
        val prepare = JButton("Prepare Review (0)")
        val row = wrappingRow(ai, prepare)
        row.setSize(ai.preferredSize.width + 10, 100)
        assertTrue(row.preferredSize.height >= ai.preferredSize.height + prepare.preferredSize.height)
    }
}
