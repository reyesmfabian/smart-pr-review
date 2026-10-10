package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.auth.Auth
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.http.HttpError
import com.fabianreyes.smartprreview.http.describeError
import com.fabianreyes.smartprreview.providers.FakeProvider
import com.fabianreyes.smartprreview.providers.PullRequestFilter
import com.fabianreyes.smartprreview.remote.parseRemote
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class PullRequestTreeTest : BasePlatformTestCase() {
    private val fake = FakeProvider()
    private fun model() = PullRequestTreeModel(fake, load = { it() })
    private val filter = PullRequestFilter.ASSIGNED

    fun testLoadingThenItems() {
        val m = model()
        assertEquals(listOf(PrNode.Loading), m.children(filter))
        m.loadGroup(filter)
        assertEquals(listOf(PrNode.Item(fake.summary)), m.children(filter))
    }

    fun testUnauthorizedOffersSignIn() {
        fake.list = { throw HttpError(401, "x") }
        val m = model()
        m.loadGroup(filter)
        assertEquals(listOf(PrNode.Error(describeError(HttpError(401, "x")), ErrorAction.SIGN_IN)), m.children(filter))
    }

    fun testOtherErrorOffersRetry() {
        fake.list = { throw IllegalStateException("boom") }
        val m = model()
        m.loadGroup(filter)
        assertEquals(listOf(PrNode.Error("boom", ErrorAction.RETRY)), m.children(filter))
    }

    fun testEmpty() {
        fake.list = { emptyList() }
        val m = model()
        m.loadGroup(filter)
        assertEquals(listOf(PrNode.Empty), m.children(filter))
    }

    fun testOnChangeFires() {
        val m = model()
        val seen = mutableListOf<PullRequestFilter>()
        m.onChange { seen += it }
        m.loadGroup(PullRequestFilter.CREATED)
        assertTrue(PullRequestFilter.CREATED in seen)
    }

    private var signIns = 0

    private fun panel(onOpen: (Int) -> Unit = {}): PullRequestTreePanel {
        val auth = object : Auth {
            override fun isSignedIn() = true
            override fun signIn(force: Boolean) {
                if (force) signIns++
            }
            override fun authorization() = "token x"
        }
        val remote = parseRemote("https://github.com/o/r.git", emptyList())!!
        val ready = RepositoryState.Ready(Git(Path.of(".")), "origin", remote, auth, fake)
        return PullRequestTreePanel(project, ready, PullRequestTreeModel(fake, load = { it() }, post = { it() }), onOpen)
    }

    private fun PullRequestTreePanel.path(f: PullRequestFilter) = TreePath(groupNodes.getValue(f).path)

    fun testPanelExpandsAndLoadsGroups() {
        val p = panel()
        assertTrue(p.tree.isExpanded(p.path(PullRequestFilter.ASSIGNED)))
        assertTrue(p.tree.isExpanded(p.path(PullRequestFilter.CREATED)))
        assertFalse(p.tree.isExpanded(p.path(PullRequestFilter.ALL)))
        val assigned = p.groupNodes.getValue(PullRequestFilter.ASSIGNED)
        assertEquals(1, assigned.childCount)
        assertEquals(PrNode.Item(fake.summary), (assigned.firstChild as DefaultMutableTreeNode).userObject)
        val all = p.groupNodes.getValue(PullRequestFilter.ALL)
        assertEquals(0, all.childCount)
        p.tree.expandPath(p.path(PullRequestFilter.ALL))
        assertEquals(PrNode.Item(fake.summary), (all.firstChild as DefaultMutableTreeNode).userObject)
    }

    private fun errorPath(p: PullRequestTreePanel): TreePath {
        val group = p.groupNodes.getValue(PullRequestFilter.ASSIGNED)
        return TreePath((group.firstChild as DefaultMutableTreeNode).path)
    }

    fun testActivatingSignInErrorSignsIn() {
        fake.list = { throw HttpError(401, "x") }
        val p = panel()
        val path = errorPath(p)
        assertTrue((path.lastPathComponent as DefaultMutableTreeNode).userObject is PrNode.Error)
        assertTrue(p.activate(path))
        assertEquals(1, signIns)
    }

    fun testActivatingRetryErrorReloadsGroup() {
        var calls = 0
        fake.list = { if (it == PullRequestFilter.ASSIGNED) calls++; throw IllegalStateException("boom") }
        val p = panel()
        assertEquals(1, calls)
        assertTrue(p.activate(errorPath(p)))
        assertEquals(2, calls)
    }

    fun testBrowserUrlOnlyHttp() {
        assertEquals("https://github.com/o/r/pull/1", browserUrl("https://github.com/o/r/pull/1"))
        assertNotNull(browserUrl("http://tfs:8080/x"))
        assertNull(browserUrl("javascript:alert(1)"))
        assertNull(browserUrl("file:///etc/passwd"))
        assertNull(browserUrl("not a url"))
    }

    fun testOneClickOnTheSignInLinkSignsIn() {
        fake.list = { throw HttpError(401, "x") }
        val p = panel()
        p.tree.setSize(400, 400)
        val b = p.tree.getPathBounds(errorPath(p))!!
        val y = b.y + b.height / 2
        p.tree.dispatchEvent(MouseEvent(p.tree, MouseEvent.MOUSE_PRESSED, 1_000, InputEvent.BUTTON1_DOWN_MASK, b.x + 2, y, 1, false, MouseEvent.BUTTON1))
        p.tree.dispatchEvent(MouseEvent(p.tree, MouseEvent.MOUSE_RELEASED, 1_001, 0, b.x + 2, y, 1, false, MouseEvent.BUTTON1))
        assertEquals(1, signIns)
    }

    fun testActivatingAPullRequestHandsItsNumberToTheOpener() {
        val opened = mutableListOf<Int>()
        val p = panel { opened += it }
        val item = p.groupNodes.getValue(PullRequestFilter.ASSIGNED).firstChild as DefaultMutableTreeNode
        assertTrue(p.activate(TreePath(item.path)))
        assertEquals(listOf(fake.summary.id), opened)
    }
}
