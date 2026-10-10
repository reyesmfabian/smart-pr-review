package com.fabianreyes.smartprreview.ui.diff

import com.intellij.diff.util.Side
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import java.awt.Color

/** The native deleted color is gray in Android Studio. Override only this viewer, never the global scheme. */
internal fun reviewColorScheme(original: EditorColorsScheme, side: Side): EditorColorsScheme {
    // Editor-bound delegates return null from clone() on newer IDEs; the global scheme is cloneable.
    val scheme = (original.clone() ?: EditorColorsManager.getInstance().globalScheme.clone()) as EditorColorsScheme
    val dark = ColorUtil.isDark(original.defaultBackground)
    fun attributes(removed: Boolean): TextAttributes = TextAttributes().apply {
        backgroundColor = Color(if (removed) { if (dark) 0x603035 else 0xFFDCE0 } else { if (dark) 0x284D35 else 0xD5F5DC })
        // Diff uses the foreground field for the muted line behind word-level changes, not for syntax text.
        foregroundColor = ColorUtil.mix(backgroundColor, original.defaultBackground, 0.45)
        errorStripeColor = Color(if (removed) { if (dark) 0xF28B82 else 0xB42318 } else { if (dark) 0x81C995 else 0x16743C })
    }
    scheme.setAttributes(DiffColors.DIFF_DELETED, attributes(true))
    scheme.setAttributes(DiffColors.DIFF_INSERTED, attributes(false))
    scheme.setAttributes(DiffColors.DIFF_MODIFIED, attributes(side == Side.LEFT))
    return scheme
}
