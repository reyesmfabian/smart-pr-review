package com.fabianreyes.smartprreview.git

import com.fabianreyes.smartprreview.git.ChangedFile.Status.A
import com.fabianreyes.smartprreview.git.ChangedFile.Status.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class GitTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val windows = System.getProperty("os.name").startsWith("Windows")
    private lateinit var repo: Path
    private lateinit var git: Git

    @Before
    fun init() {
        repo = tmp.newFolder("repo").toPath()
        Git(tmp.root.toPath()).run("init", "-q", "-b", "main", repo.toString())
        git = Git(repo)
        git.run("config", "user.email", "t@example.com")
        git.run("config", "user.name", "t")
        git.run("config", "commit.gpgsign", "false")
        commit("a.txt", "one\ntwo\nthree\n", "first")
    }

    private fun commit(file: String, content: String, message: String): String {
        repo.resolve(file).also { it.parent.createDirectories() }.writeText(content)
        git.run("add", "-A")
        git.run("commit", "-q", "-m", message)
        return git.headSha()
    }

    @Test
    fun `open spells the root through the directory it was given`() {
        val sub = repo.resolve("src").createDirectories()
        assertEquals(repo.toRealPath(), Git.open(sub)!!.root.toRealPath())
        assertNull(Git.open(tmp.newFolder("plain").toPath()))
        assumeFalse(windows) // symlinks need privileges there
        val link = Files.createSymbolicLink(tmp.root.toPath().resolve("link"), repo)
        assertEquals(link, Git.open(link)!!.root)
        // The root (not the subfolder), spelled through the symlink.
        assertEquals(link, Git.open(link.resolve("src"))!!.root)
    }

    @Test
    fun `failures carry git's stderr`() {
        val error = assertThrows(GitException::class.java) { git.run("rev-parse", "--verify", "nope") }
        assertTrue(error.message, error.message!!.startsWith("git rev-parse: "))
    }

    @Test
    fun `picks upstream over origin and reads changed files, ranges and contents`() {
        assertNull(git.pickRemote())
        git.run("remote", "add", "origin", "https://github.com/o/r.git")
        assertEquals(Remote("origin", "https://github.com/o/r.git"), git.pickRemote())
        git.run("remote", "add", "upstream", "git@github.com:u/r.git")
        assertEquals(Remote("upstream", "git@github.com:u/r.git"), git.pickRemote())

        val base = git.headSha()
        git.run("mv", "a.txt", "b.txt")
        val head = commit("c.txt", "new\n", "second")
        val files = git.changedFiles(base, head)
        assertEquals(listOf(ChangedFile(R, "b.txt", oldPath = "a.txt"), ChangedFile(A, "c.txt")), files)
        assertEquals(listOf(LineRange(1, 1)), git.changedRanges(base, head, files[1]))
        assertEquals(listOf(LineRange(1, 1)), git.diffRanges(base, head, files[1]))
        assertEquals(emptyList<LineRange>(), git.diffRanges(base, head, files[0])) // pure rename: no hunks
        assertEquals("one\ntwo\nthree\n", git.showFile(base, "a.txt"))
        assertEquals("", git.showFile(base, "missing.txt"))
        assertEquals(base, git.mergeBase(base, head))
        assertTrue(git.hasCommit(head))
        assertFalse(git.hasCommit("0000000000000000000000000000000000000000"))
        assertTrue(git.diffText(base, head, listOf("c.txt")).contains("+new"))
        assertFalse(git.isDirty())
    }

    @Test
    fun `checkout creates, fast-forwards and resets`() {
        val first = git.headSha()
        val second = commit("d.txt", "d\n", "third")
        git.checkout("pr/1", first)
        assertEquals(first, git.headSha())
        git.checkout("pr/1", second) // fast-forward
        assertEquals(second, git.headSha())
        assertTrue(git.canFastForward("pr/1", second))
        assertFalse(git.canFastForward("pr/1", first))
        git.checkout("pr/1", first) // `first` is already contained: git says "Already up to date", HEAD stays
        assertEquals(second, git.headSha())
        git.checkout("pr/1", first, reset = true)
        assertEquals(first, git.headSha())
        git.checkout("pr/1", second, upstream = "main") // a local branch stands in for "origin/x" here
        assertEquals("main", git.run("rev-parse", "--abbrev-ref", "pr/1@{upstream}").trim())
    }

    @Test
    fun `worktree is detached, skips hooks and can be removed`() {
        assumeFalse(windows) // the hook is a shell script
        val marker = tmp.root.toPath().resolve("hook-ran")
        val hook = repo.resolve(".git/hooks").createDirectories().resolve("post-checkout")
        hook.writeText("#!/bin/sh\ntouch '$marker'\n")
        hook.toFile().setExecutable(true)
        val dir = tmp.root.toPath().resolve("wt")
        git.addWorktree(dir, git.headSha())
        assertTrue(dir.resolve("a.txt").exists())
        assertFalse("post-checkout hook ran inside the worktree", marker.exists())
        assertEquals("HEAD", Git(dir).run("rev-parse", "--abbrev-ref", "HEAD").trim())
        git.removeWorktree(dir)
        assertFalse(dir.exists())
        git.pruneWorktrees()
    }
}
