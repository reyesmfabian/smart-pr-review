package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.auth.Auth
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.git.LineRange
import com.fabianreyes.smartprreview.providers.CommentableLines
import com.fabianreyes.smartprreview.providers.FakeProvider
import com.fabianreyes.smartprreview.providers.PullRequest
import com.fabianreyes.smartprreview.providers.Vote
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ReviewSessionTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var remote: Path
    private lateinit var root: Path
    private lateinit var git: Git
    private lateinit var baseSha: String
    private lateinit var headSha: String
    private lateinit var provider: FakeProvider
    private lateinit var session: ReviewSession
    private lateinit var storage: MemoryStore
    private var redraws = 0

    private val auth = object : Auth {
        override fun isSignedIn() = true
        override fun signIn(force: Boolean) {}
        override fun authorization() = "Bearer x"
    }
    private val remoteInfo = RemoteInfo(ProviderKind.GITHUB, "github.com", "https://github.com", "octo", null, "app")

    private fun ready(p: FakeProvider = provider, info: RemoteInfo = remoteInfo) = RepositoryState.Ready(git, "origin", info, auth, p)

    private fun <T : Throwable> thrown(type: Class<T>, block: () -> Unit): T = Assert.assertThrows(type) { block() }

    private fun write(repo: Path, path: String, content: String) {
        repo.resolve(path).also { it.parent.createDirectories() }.writeText(content)
    }

    private fun commit(repo: Path, message: String): String {
        val g = Git(repo)
        g.run("add", "--all")
        g.run("commit", "-q", "-m", message)
        return g.headSha()
    }

    fun `test matching head on a different branch still offers checkout`() {
        git.checkout("another-branch", headSha)
        session.refresh()
        assertFalse(session.require().checkedOut)
        session.checkout { false }
        assertEquals(session.require().pr.checkoutBranch, git.currentBranch())
        assertTrue(session.require().checkedOut)
    }

    fun `test current panel exposes checkout review window and worktree buttons`() {
        val panel = com.fabianreyes.smartprreview.ui.CurrentPullRequestPanel(project, session)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, panel)
        val buttons = com.intellij.util.ui.UIUtil.findComponentsOfType(panel, javax.swing.JButton::class.java)
        val labels = buttons.map { it.text }
        assertTrue(labels.toString(), labels.containsAll(listOf("Checkout Pull Request", "Open Review Window", "Create Review Worktree", "Review Worktrees…")))
    }

    private fun configure(repo: Path) {
        val g = Git(repo)
        g.run("config", "user.name", "Test")
        g.run("config", "user.email", "test@example.com")
        g.run("config", "commit.gpgsign", "false")
    }

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("smart-pr-review-")
        remote = dir.resolve("remote.git")
        val author = dir.resolve("author")
        root = dir.resolve("work")
        val top = Git(dir)
        top.run("init", "--quiet", "--bare", "--initial-branch=main", remote.toString())
        top.run("init", "--quiet", "--initial-branch=main", author.toString())
        configure(author)
        Git(author).run("remote", "add", "origin", remote.toString())
        val lines = MutableList(10) { "line ${it + 1}" }
        write(author, "src/app.ts", lines.joinToString("\n") + "\n")
        write(author, "docs/old name.md", "# Docs\n\nThis file is renamed by the pull request.\n")
        write(author, "keep.txt", "Deleted by the pull request.\n")
        baseSha = commit(author, "base")
        Git(author).run("push", "--quiet", "origin", "main")
        lines[4] = "changed line 5"
        write(author, "src/app.ts", lines.joinToString("\n") + "\n")
        write(author, "src/new file.ts", "export const added = true;\n")
        Git(author).run("mv", "docs/old name.md", "docs/new name.md")
        Git(author).run("rm", "--quiet", "keep.txt")
        headSha = commit(author, "head")
        Git(author).run("push", "--quiet", "origin", "HEAD:refs/pull/1/head")
        // --no-local: a local clone would copy every object, including the unfetched pull request commit.
        top.run("clone", "--quiet", "--no-local", remote.toString(), root.toString())
        configure(root)
        git = Git(root)

        provider = FakeProvider().also { it.baseSha = baseSha; it.headSha = headSha }
        storage = MemoryStore()
        session = ReviewSession(project)
        session.notify = { it() } // no EDT in these tests
        session.draftStorage = storage
        session.onChange(testRootDisposable) { redraws++ }
        session.attach(ready())
        session.open(1)
        redraws = 0
    }

    override fun tearDown() {
        try {
            session.dispose()
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testOpensAPullRequestFromItsRemoteRefs() {
        provider.viewed += "src/app.ts"
        session.open(1)
        val c = session.require()
        assertEquals(baseSha, c.mergeBase)
        assertEquals(listOf("docs/new name.md", "keep.txt", "src/app.ts", "src/new file.ts"), c.files.map { it.path }.sorted())
        assertEquals(setOf("src/app.ts"), c.viewed)
        assertFalse(c.checkedOut)
    }

    fun testRequireBeforeOpeningSaysSo() {
        session.close()
        val e = thrown(IllegalStateException::class.java) { session.require() }
        assertEquals("Open a pull request first.", e.message)
    }

    fun testASlowerOlderOpenDoesNotReplaceTheLastOne() {
        val gate = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        provider.onGetPullRequest = { id ->
            if (id == 7) {
                firstStarted.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
        }
        var failure: Throwable? = null
        val slow = java.lang.Thread { try { session.open(7) } catch (e: Throwable) { failure = e } }
        slow.start()
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS))
        session.open(8)
        gate.countDown()
        slow.join(20_000)
        assertNull(failure)
        assertEquals(8, session.require().pr.id)
    }

    fun testCloseDuringAnOpenInFlightLeavesNothingOpen() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        provider.onGetPullRequest = { started.countDown(); gate.await(10, TimeUnit.SECONDS) }
        val opening = java.lang.Thread { session.open(1) }
        opening.start()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        session.close()
        gate.countDown()
        opening.join(20_000)
        assertNull(session.current)
    }

    fun testFetchesTheHeadFromAnotherRepositoryWhenTheProviderSaysSo() {
        provider.headFetch = PullRequest.HeadFetch(remote.toUri().toString(), "+refs/pull/1/head:refs/smart-pr-review/1/head")
        session.open(1)
        assertEquals(4, session.require().files.size)
    }

    fun testRefusesAHeadFetchUrlThatBecomesAGitOption() {
        provider.headFetch = PullRequest.HeadFetch("--upload-pack=touch /tmp/pwned", "x")
        val e = thrown(IllegalArgumentException::class.java) { session.open(1) }
        assertTrue(e.message, e.message!!.startsWith("Unsupported repository URL"))
        assertEquals("a@b.c:path", safeRepositoryUrl("a@b.c:path"))
        assertEquals("HTTPS://x", safeRepositoryUrl("HTTPS://x"))
        thrown(IllegalArgumentException::class.java) { safeRepositoryUrl("a@b.c:-x") }
    }

    fun testLimitsCommentableLinesToTheDiffWhenTheProviderRequiresIt() {
        assertEquals(listOf(LineRange(2, 8)), session.commentableRanges("src/app.ts"))
        assertEquals(emptyList<LineRange>(), session.commentableRanges("keep.txt"))
        provider.commentableLines = CommentableLines.FILE
        assertNull(session.commentableRanges("src/app.ts"))
    }

    fun testMarksFilesAsViewed() {
        session.setViewed("src/app.ts", true)
        assertTrue("src/app.ts" in provider.viewed)
        assertTrue("src/app.ts" in session.require().viewed)
        assertEquals(1, redraws)
    }

    fun testRedrawsTheViewedCheckboxWhenSavingFails() {
        provider.setFileViewedError = RuntimeException("offline")
        val e = thrown(RuntimeException::class.java) { session.setViewed("src/app.ts", true) }
        assertEquals("offline", e.message)
        assertEquals(1, redraws)
        assertFalse("src/app.ts" in session.require().viewed)
    }

    fun testDraftsAndSummaryPersistThroughTheStore() {
        val d = session.addDraft("src/app.ts", 5, 5, "Nit")
        session.updateDraft(d.id, "Nit!")
        session.appendSummary("one")
        session.appendSummary("two")
        val again = DraftStore(storage, "github:octo/app:1")
        assertEquals(listOf("Nit!"), again.all().map { it.body })
        assertEquals(headSha, again.all().single().headSha)
        assertEquals("one\n\ntwo", again.summary())
        session.removeDrafts(listOf(d.id))
        assertEquals(emptyList<Any>(), again.all())
    }

    fun testPublishesDraftsAndClearsThem() {
        session.addDraft("src/app.ts", 5, 5, "Nit")
        session.require().threads = emptyList()
        val result = session.publish("Looks good", Vote.APPROVE) { fail("no stale drafts expected"); true }!!
        assertEquals(emptyList<Any>(), result.failed)
        assertEquals("Nit", provider.published[0].second[0].body)
        assertEquals(Vote.APPROVE, provider.published[0].third)
        assertEquals(emptyList<Any>(), session.require().drafts.all())
        assertEquals("", session.require().drafts.summary())
    }

    fun testPublishRefusesWhenTheDraftsChangedSincePreview() {
        session.addDraft("src/app.ts", 5, 5, "Nit")
        val shown = publishRevision(session.require())
        session.addDraft("src/app.ts", 6, 6, "AI added meanwhile")
        thrown(DraftsChangedException::class.java) { session.publish("", Vote.COMMENT, shown) { true } }
        assertTrue(provider.published.isEmpty())
        assertEquals(2, session.require().drafts.all().size)
        session.publish("", Vote.COMMENT, publishRevision(session.require())) { true }
        assertEquals(2, provider.published.single().second.size)
    }

    fun testAsksBeforePublishingDraftsWrittenAgainstAnOlderHead() {
        session.addDraft("src/app.ts", 5, 5, "Nit")
        provider.headSha = "f".repeat(40)
        var asked = 0
        val result = session.publish("", Vote.COMMENT) { asked = it; false }
        assertNull(result)
        assertEquals(1, asked)
        assertTrue(provider.published.isEmpty())
        assertEquals(1, session.require().drafts.all().size)
    }

    fun testKeepsDraftsThatFailedToPublishAndTheSummary() {
        session.addDraft("src/app.ts", 5, 5, "ok")
        val bad = session.addDraft("src/app.ts", 6, 6, "bad")
        provider.failing += bad.id
        val result = session.publish("Summary", Vote.COMMENT) { true }!!
        assertEquals(listOf(bad.id), result.failed.map { it.id })
        assertEquals(listOf(bad.id), session.require().drafts.all().map { it.id })
        assertEquals("Summary", session.require().drafts.summary())
    }

    fun testPublishReloadsThreads() {
        provider.threads = listOf(com.fabianreyes.smartprreview.providers.Thread("t", "src/app.ts", com.fabianreyes.smartprreview.providers.Side.HEAD, 5, null, false, null, false, emptyList()))
        session.publish("", Vote.COMMENT) { true }
        assertEquals(listOf("t"), session.require().threads.map { it.id })
    }

    fun testKeepsEveryDraftWhenTheProviderRejectsTheWholeReview() {
        session.addDraft("src/app.ts", 5, 5, "Nit")
        provider.publishError = RuntimeException("Can not approve your own pull request")
        val e = thrown(RuntimeException::class.java) { session.publish("LGTM", Vote.APPROVE) { true } }
        assertTrue(e.message!!.contains("own pull request"))
        assertEquals(1, session.require().drafts.all().size)
        assertEquals("LGTM", session.require().drafts.summary())
    }

    fun testRefusesToCheckOutOverUncommittedChanges() {
        write(root, "src/app.ts", "dirty\n")
        val e = thrown(IllegalStateException::class.java) { session.checkout { true } }
        assertTrue(e.message, e.message!!.contains("uncommitted changes"))
    }

    fun testChecksOutThePullRequestHead() {
        session.addDraft("src/app.ts", 5, 5, "Nit")
        session.checkout { fail("a new branch needs no reset"); true }
        assertEquals(headSha, git.headSha())
        assertTrue(session.require().checkedOut)
        assertEquals(listOf("Nit"), session.require().drafts.all().map { it.body })
    }

    fun testChecksOutTheBranchOfASameRepositoryPullRequestTrackingTheRemote() {
        git.run("push", "--quiet", "origin", "$headSha:refs/heads/feature")
        provider.headBranch = "feature"
        session.open(1)
        session.checkout { fail("a new branch needs no reset"); true }
        assertEquals("feature", git.run("branch", "--show-current").trim())
        assertEquals("origin/feature", git.run("rev-parse", "--abbrev-ref", "feature@{upstream}").trim())
    }

    /** Leaves pr/1 with a commit the pull request does not have, as after the author force-pushes. */
    private fun divergePullRequestBranch() {
        session.checkout { true }
        write(root, "local.txt", "only on the local branch\n")
        git.run("add", "local.txt")
        git.run("commit", "--quiet", "-m", "local")
        git.run("checkout", "--quiet", "main")
    }

    fun testResetsADivergedBranchWhenTheUserConfirms() {
        divergePullRequestBranch()
        var asked = ""
        session.checkout { asked = it; true }
        assertEquals("pr/1", asked)
        assertEquals(headSha, git.headSha())
        assertEquals("pr/1", git.run("branch", "--show-current").trim())
    }

    fun testLeavesADivergedBranchAloneWhenTheResetIsDeclined() {
        divergePullRequestBranch()
        val tip = git.run("rev-parse", "pr/1").trim()
        session.checkout { false }
        assertEquals("main", git.run("branch", "--show-current").trim())
        assertEquals(tip, git.run("rev-parse", "pr/1").trim())
    }

    fun testClosingKeepsDraftsForTheNextOpen() {
        session.addDraft("src/app.ts", 5, 5, "Nit")
        session.close()
        assertNull(session.current)
        session.open(1)
        assertEquals(listOf("Nit"), session.require().drafts.all().map { it.body })
    }

    fun testAttachingTheSameRepositoryKeepsTheReviewAndSwapsTheProvider() {
        val other = FakeProvider().also { it.baseSha = baseSha; it.headSha = headSha }
        other.viewed += "keep.txt"
        session.attach(ready(other))
        assertNotNull(session.current)
        session.refresh()
        assertEquals(setOf("keep.txt"), session.require().viewed)
    }

    fun testAttachingAnotherRepositoryClosesTheReview() {
        session.attach(ready(info = remoteInfo.copy(repo = "other")))
        assertNull(session.current)
    }
}
