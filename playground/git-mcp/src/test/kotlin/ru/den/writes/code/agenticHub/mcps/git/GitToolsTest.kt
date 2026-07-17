package ru.den.writes.code.agenticHub.mcps.git

import kotlin.test.Test
import kotlin.test.assertEquals

/** Records the args it was handed and replays a canned stdout — no real git process. */
private class FakeRunner(private val output: String) : CommandRunner {
    var lastArgs: List<String> = emptyList()
        private set

    override fun run(args: List<String>): String {
        lastArgs = args
        return output
    }
}

class GitToolsTest {

    @Test
    fun `when on a branch - then currentBranch trims the git output`() {
        // given
        val runner = FakeRunner("main\n")

        // when
        val branch = GitRepo("/repo", runner).currentBranch()

        // then
        assertEquals("main", branch)
        assertEquals(listOf("git", "-C", "/repo", "rev-parse", "--abbrev-ref", "HEAD"), runner.lastArgs)
    }

    @Test
    fun `when HEAD is detached - then currentBranch reports it`() {
        // given
        val runner = FakeRunner("HEAD\n")

        // when - then
        assertEquals("HEAD (detached)", GitRepo("/repo", runner).currentBranch())
    }

    @Test
    fun `when no subdir - then listFiles runs bare ls-files`() {
        // given
        val runner = FakeRunner("README.md\nsrc/Main.kt\n")

        // when
        val files = GitRepo("/repo", runner).listFiles(null)

        // then
        assertEquals("README.md\nsrc/Main.kt", files)
        assertEquals(listOf("git", "-C", "/repo", "ls-files"), runner.lastArgs)
    }

    @Test
    fun `when subdir given - then listFiles scopes ls-files to it`() {
        // given
        val runner = FakeRunner("docs/a.md\n")

        // when
        GitRepo("/repo", runner).listFiles("docs")

        // then
        assertEquals(listOf("git", "-C", "/repo", "ls-files", "docs"), runner.lastArgs)
    }

    @Test
    fun `when no tracked files - then listFiles returns a notice`() {
        // given
        val runner = FakeRunner("   \n")

        // when - then
        assertEquals("(no tracked files)", GitRepo("/repo", runner).listFiles(null))
    }

    @Test
    fun `when working-tree diff requested - then diff runs without --staged`() {
        // given
        val runner = FakeRunner("diff --git a/x b/x\n")

        // when
        val diff = GitRepo("/repo", runner).diff(staged = false)

        // then
        assertEquals("diff --git a/x b/x\n", diff)
        assertEquals(listOf("git", "-C", "/repo", "diff"), runner.lastArgs)
    }

    @Test
    fun `when staged diff requested - then diff appends --staged`() {
        // given
        val runner = FakeRunner("diff --git a/x b/x\n")

        // when
        GitRepo("/repo", runner).diff(staged = true)

        // then
        assertEquals(listOf("git", "-C", "/repo", "diff", "--staged"), runner.lastArgs)
    }

    @Test
    fun `when diff is empty - then a clear notice per mode`() {
        // when - then
        assertEquals("(no unstaged changes)", GitRepo("/repo", FakeRunner("")).diff(staged = false))
        assertEquals("(no staged changes)", GitRepo("/repo", FakeRunner("")).diff(staged = true))
    }
}
