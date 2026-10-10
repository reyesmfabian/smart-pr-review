package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.git.ChangedFile.Status
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.providers.FakeProvider
import com.fabianreyes.smartprreview.review.DraftStore
import com.fabianreyes.smartprreview.review.MemoryStore
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class ReviewDiffTest : BasePlatformTestCase() {
    private lateinit var git: Git
    private lateinit var c: OpenPullRequest
    private lateinit var base: String
    private lateinit var head: String

    override fun setUp() {
        super.setUp()
        val repo: Path = Files.createTempDirectory("review-diff")
        git = Git(repo)
        git.run("init", "-q", "-b", "main")
        git.run("config", "user.email", "t@example.com")
        git.run("config", "user.name", "t")
        git.run("config", "commit.gpgsign", "false")
        repo.resolve("a.txt").writeText("one\ntwo\n")
        git.run("add", "-A")
        git.run("commit", "-q", "-m", "base")
        base = git.headSha()
        repo.resolve("a.txt").writeText("one\nTWO\n")
        git.run("add", "-A")
        git.run("commit", "-q", "-m", "head")
        head = git.headSha()
        val pr = FakeProvider().apply { baseSha = base; headSha = head }.getPullRequest(7)
        c = OpenPullRequest(pr, base, emptyList(), emptyList(), mutableSetOf(), DraftStore(MemoryStore(), "k"), false)
    }

    private fun text(content: com.intellij.diff.contents.DiffContent) = (content as DocumentContent).document.text

    fun `test review colors accept a real editor's non-cloneable bound scheme`() {
        myFixture.configureByText("colors.txt", "text")
        val scheme = reviewColorScheme(myFixture.editor.colorsScheme, Side.LEFT)
        val deleted = scheme.getAttributes(com.intellij.openapi.diff.DiffColors.DIFF_DELETED).backgroundColor!!
        assertTrue(deleted.red > deleted.green)
    }

    fun `test review colors are red on the left green on the right and never mutate the global scheme`() {
        val original = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
        val key = com.intellij.openapi.diff.DiffColors.DIFF_DELETED
        val before = original.getAttributes(key).clone()
        val left = reviewColorScheme(original, Side.LEFT)
        val right = reviewColorScheme(original, Side.RIGHT)
        for (scheme in listOf(left, right)) {
            val deleted = scheme.getAttributes(key).backgroundColor!!
            assertTrue(deleted.red > deleted.green)
            val added = scheme.getAttributes(com.intellij.openapi.diff.DiffColors.DIFF_INSERTED).backgroundColor!!
            assertTrue(added.green > added.red)
        }
        val old = left.getAttributes(com.intellij.openapi.diff.DiffColors.DIFF_MODIFIED).backgroundColor!!
        val new = right.getAttributes(com.intellij.openapi.diff.DiffColors.DIFF_MODIFIED).backgroundColor!!
        assertTrue(old.red > old.green)
        assertTrue(new.green > new.red)
        assertEquals(before, original.getAttributes(key))
    }

    fun `test a modified file carries the review data, titles and the line to scroll to`() {
        val file = ChangedFile(Status.M, "a.txt")
        val r = diffRequest(project, c, git, file, 5, 7)
        assertEquals("a.txt (#7)", r.title)
        assertEquals(listOf("Before · a.txt · ${base.take(8)}", "After · a.txt · ${head.take(8)}"), r.contentTitles)
        assertEquals(ReviewDiffInfo("a.txt", base, head, file), r.getUserData(REVIEW_FILE))
        assertEquals(true, r.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
        assertEquals(com.intellij.openapi.util.Pair(Side.RIGHT, 4), r.getUserData(DiffUserDataKeys.SCROLL_TO_LINE))
        assertEquals("one\ntwo\n", text(r.contents[0]))
        assertEquals("one\nTWO\n", text(r.contents[1]))
    }

    fun `test the review diff extension is registered`() {
        assertTrue(com.intellij.diff.DiffExtension.EP_NAME.extensionList.any { it is ReviewDiffExtension })
    }

    fun `test only the right editor of a two-side viewer is a target`() {
        myFixture.configureByText("x.txt", "a")
        // A real two-side viewer needs a UI; the unified viewer and no viewer are the cases to reject.
        assertNull(headEditor(null, myFixture.editor))
        assertNull(headEditor(Any(), myFixture.editor))
        assertNull(headEditor(null, null))
    }

    fun `test the line goes on the requested side`() {
        val r = diffRequest(project, c, git, ChangedFile(Status.M, "a.txt"), 2, 7, Side.LEFT)
        assertEquals(com.intellij.openapi.util.Pair(Side.LEFT, 1), r.getUserData(DiffUserDataKeys.SCROLL_TO_LINE))
    }

    fun `test a deleted file has an empty right side`() {
        val r = diffRequest(project, c, git, ChangedFile(Status.D, "a.txt"), null, 7)
        assertEquals("", text(r.contents[1]))
        assertSame(com.intellij.diff.tools.simple.SimpleDiffTool.INSTANCE, r.getUserData(com.intellij.diff.util.DiffUserDataKeysEx.FORCE_DIFF_TOOL))
        assertEquals(com.intellij.diff.util.DiffUserDataKeysEx.ScrollToPolicy.FIRST_CHANGE, r.getUserData(com.intellij.diff.util.DiffUserDataKeysEx.SCROLL_TO_CHANGE))
        assertNull(r.getUserData(DiffUserDataKeys.SCROLL_TO_LINE))
    }

    fun `test an added file has an empty left side`() {
        val r = diffRequest(project, c, git, ChangedFile(Status.A, "a.txt"), null, 7)
        assertEquals("", text(r.contents[0]))
        assertEquals("one\nTWO\n", text(r.contents[1]))
    }

    fun `test the gutter tooltip escapes markup because it is rendered as HTML`() {
        val t = markTooltip("ann: <img src=x> and <b>bold</b> & more")!!
        assertTrue(t, t.contains("&lt;img"))
        assertFalse(t, t.contains("<img") || t.contains("<b>"))
        assertNull(markTooltip(null))
    }

    fun `test a draft is allowed only inside a commentable range, any line when ranges are null`() {
        val ranges = listOf(com.fabianreyes.smartprreview.git.LineRange(10, 20))
        assertTrue(lineAllowed(ranges, 12, 15))
        assertTrue(lineAllowed(ranges, 10, 20))
        assertFalse(lineAllowed(ranges, 18, 25))
        assertFalse(lineAllowed(ranges, 1, 5))
        assertFalse(lineAllowed(emptyList(), 1, 1))
        assertTrue(lineAllowed(null, 1, 999))
    }

    fun `test the focused comment travels on the request`() {
        val r = diffRequest(project, c, git, ChangedFile(Status.M, "a.txt"), 2, 7, focus = "t1")
        assertEquals("t1", r.getUserData(REVIEW_FOCUS))
        assertNull(diffRequest(project, c, git, ChangedFile(Status.M, "a.txt"), 2, 7).getUserData(REVIEW_FOCUS))
    }

    fun `test the preview key tells files, lines and comments apart`() {
        assertEquals(diffKey("a.txt", null, null), diffKey("a.txt", null, null))
        assertFalse(diffKey("a.txt", null, null, "old") == diffKey("a.txt", null, null, "new"))
        assertFalse(diffKey("a.txt", 2, null) == diffKey("a.txt", null, null))
        assertFalse(diffKey("a.txt", 2, "t1") == diffKey("a.txt", 2, null))
    }

    fun `test a draft goes only to the pull request version the diff shows`() {
        val info = ReviewDiffInfo("a.txt", base, head, ChangedFile(Status.M, "a.txt"))
        assertSame(c, draftTarget(c, info))
        assertNull(draftTarget(null, info))
        // Another pull request, or new commits, since this diff opened.
        assertNull(draftTarget(c, info.copy(rightSha = base)))
    }
}
