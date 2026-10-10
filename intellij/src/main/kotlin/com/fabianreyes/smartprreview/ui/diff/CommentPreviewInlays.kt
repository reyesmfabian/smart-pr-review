package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.review.GutterMark
import com.fabianreyes.smartprreview.review.MarkKind
import com.fabianreyes.smartprreview.review.oneLine
import com.fabianreyes.smartprreview.review.severityLabel
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedString
import javax.swing.Icon

private const val PREVIEW_MAX = 120
private const val GAP = 4

/** "ana: why this retry? · 3 replies", prefixed when outdated; null for a thread without comments. */
internal fun threadPreview(t: Thread): String? {
    val first = t.comments.firstOrNull() ?: return null
    val outdated = if (t.isOutdated) "${message("label.outdated")} · " else ""
    val replies = t.comments.size - 1
    return outdated + oneLine("${first.author}: ${first.body}", PREVIEW_MAX) + if (replies > 0) " · ${message("preview.replies", replies)}" else ""
}

/** "Draft: …", or "AI draft (Security · Error): …". */
internal fun draftPreview(d: Draft): String = when (val o = d.origin) {
    DraftOrigin.Manual -> message("preview.draft", oneLine(d.body, PREVIEW_MAX))
    is DraftOrigin.Ai -> message("preview.aiDraft", o.profile, severityLabel(o.severity), oneLine(d.body, PREVIEW_MAX))
}

/** A mark's gutter icon, which its preview repeats. */
internal fun markIcon(kind: MarkKind): Icon = when (kind) {
    MarkKind.THREAD_ACTIVE -> AllIcons.General.Balloon
    MarkKind.THREAD_RESOLVED -> AllIcons.General.InspectionsOK
    MarkKind.DRAFT_MANUAL -> AllIcons.Actions.Edit
    MarkKind.DRAFT_AI -> AllIcons.Actions.IntentionBulb
}

/** A summary under a thread or draft, wrapped to the viewport; [id] is the thread or draft a click opens. */
class CommentPreview(val id: String, val text: String, private val icon: Icon) : EditorCustomElementRenderer {
    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)
    private val indent get() = icon.iconWidth + JBUI.scale(GAP)

    private fun layouts(editor: Editor, width: Int): List<TextLayout> {
        if (text.isEmpty()) return emptyList()
        val attributed = AttributedString(text).apply { addAttribute(TextAttribute.FONT, font(editor)) }
        val context = editor.contentComponent.getFontMetrics(font(editor)).fontRenderContext
        val measurer = LineBreakMeasurer(attributed.iterator, context)
        return buildList {
            while (measurer.position < text.length) add(measurer.nextLayout(maxOf(1, width - indent).toFloat()))
        }
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val natural = indent + editor.contentComponent.getFontMetrics(font(editor)).stringWidth(text)
        val available = editor.scrollingModel.visibleArea.width.takeIf { it > 0 }
            ?: editor.contentComponent.width.takeIf { it > 0 } ?: natural
        val insets = editor.contentComponent.insets
        val space = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN)).charWidth(' ')
        // The editor adds these margins to every block inlay's width, including its virtual columns.
        val margins = insets.left + insets.right + editor.settings.additionalColumnsCount * space
        return minOf(natural, available - margins).coerceAtLeast(1)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        maxOf(icon.iconHeight, maxOf(1, layouts(inlay.editor, calcWidthInPixels(inlay)).size) * inlay.editor.lineHeight)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        icon.paintIcon(editor.contentComponent, g, targetRegion.x, targetRegion.y + (editor.lineHeight - icon.iconHeight) / 2)
        g.font = font(editor)
        g.color = JBColor.GRAY
        val metrics = g.fontMetrics
        layouts(editor, targetRegion.width).forEachIndexed { line, layout ->
            layout.draw(g as Graphics2D, (targetRegion.x + indent).toFloat(),
                (targetRegion.y + line * editor.lineHeight + (editor.lineHeight - metrics.height) / 2 + metrics.ascent).toFloat())
        }
    }
}

/** The previews of one editor, replaced as a whole on every change. EDT only. */
class CommentPreviews(private val editor: Editor) {
    private val inlays = mutableListOf<Inlay<*>>()

    init {
        editor.scrollingModel.addVisibleAreaListener { event ->
            if (event.oldRectangle?.width != event.newRectangle.width) inlays.toList().forEach { it.update() }
        }
    }

    /** Shows [previews] under their 0-based lines; a line past the end goes on the last one. */
    fun show(previews: List<Pair<Int, CommentPreview>>) {
        inlays.forEach(Disposer::dispose)
        inlays.clear()
        val lines = editor.document.lineCount
        if (lines == 0) return
        for ((line0, preview) in previews) {
            val offset = editor.document.getLineEndOffset(line0.coerceIn(0, lines - 1))
            editor.inlayModel.addBlockElement(offset, true, false, 0, preview)?.let { inlays += it }
        }
    }
}

/** The preview a click opens: only a left click (the right click is the diff's context menu). */
internal fun clickedPreview(button: Int, renderer: Any?): CommentPreview? =
    (renderer as? CommentPreview)?.takeIf { button == MouseEvent.BUTTON1 }

/** A gutter mark's preview, or null when its thread or draft is gone or has nothing to show. */
internal fun preview(mark: GutterMark, threads: Map<String, Thread>, drafts: Map<String, Draft>): CommentPreview? {
    val text = when (mark.kind) {
        MarkKind.THREAD_ACTIVE, MarkKind.THREAD_RESOLVED -> threads[mark.id]?.let(::threadPreview)
        MarkKind.DRAFT_MANUAL, MarkKind.DRAFT_AI -> drafts[mark.id]?.let(::draftPreview)
    } ?: return null
    return CommentPreview(mark.id, text, markIcon(mark.kind))
}
