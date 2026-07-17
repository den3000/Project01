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

    //region range — the shape a pull-request review needs
    @Test
    fun `when a base is given - then diff spans base to HEAD with three dots`() {
        // given
        val runner = FakeRunner("diff --git a/x b/x\n")

        // when
        GitRepo("/repo", runner).diff(base = "abc123")

        // then — three dots: what HEAD changed since it forked from the base
        assertEquals(listOf("git", "-C", "/repo", "diff", "abc123...HEAD"), runner.lastArgs)
    }

    @Test
    fun `when base and head are given - then both ends are honoured`() {
        // given
        val runner = FakeRunner("diff\n")

        // when
        GitRepo("/repo", runner).diff(base = "main", head = "feature")

        // then
        assertEquals(listOf("git", "-C", "/repo", "diff", "main...feature"), runner.lastArgs)
    }

    @Test
    fun `when a default base is configured - then diff uses it without arguments`() {
        // given — a CI pipeline sets the base once; the model then passes nothing
        val runner = FakeRunner("diff\n")

        // when
        GitRepo("/repo", runner, defaultBase = "abc123").diff()

        // then
        assertEquals(listOf("git", "-C", "/repo", "diff", "abc123...HEAD"), runner.lastArgs)
    }

    @Test
    fun `when a call names a base - then it overrides the default`() {
        // given
        val runner = FakeRunner("diff\n")

        // when
        GitRepo("/repo", runner, defaultBase = "abc123").diff(base = "other")

        // then
        assertEquals(listOf("git", "-C", "/repo", "diff", "other...HEAD"), runner.lastArgs)
    }

    @Test
    fun `when a base is in play - then staged is ignored`() {
        // given — a range and the index are different questions; the range wins
        val runner = FakeRunner("diff\n")

        // when
        GitRepo("/repo", runner, defaultBase = "abc123").diff(staged = true)

        // then
        assertEquals(listOf("git", "-C", "/repo", "diff", "abc123...HEAD"), runner.lastArgs)
    }

    @Test
    fun `when a ref is blank - then it falls back rather than building a broken range`() {
        // given
        val runner = FakeRunner("diff\n")

        // when
        GitRepo("/repo", runner, defaultBase = "abc123").diff(base = "  ", head = "  ")

        // then
        assertEquals(listOf("git", "-C", "/repo", "diff", "abc123...HEAD"), runner.lastArgs)
    }

    @Test
    fun `when the range holds no changes - then the notice names it`() {
        // when - then
        assertEquals(
            "(no changes in abc123...HEAD)",
            GitRepo("/repo", FakeRunner("")).diff(base = "abc123"),
        )
    }
    //endregion

    //region changed_files
    @Test
    fun `when a base is given - then changedFiles lists the range's paths`() {
        // given
        val runner = FakeRunner("README.md\nsrc/Main.kt\n")

        // when
        val files = GitRepo("/repo", runner).changedFiles(base = "abc123")

        // then
        assertEquals("README.md\nsrc/Main.kt", files)
        assertEquals(listOf("git", "-C", "/repo", "diff", "--name-only", "abc123...HEAD"), runner.lastArgs)
    }

    @Test
    fun `when no base is known - then changedFiles falls back to the working tree`() {
        // given
        val runner = FakeRunner("Main.kt\n")

        // when
        GitRepo("/repo", runner).changedFiles()

        // then
        assertEquals(listOf("git", "-C", "/repo", "diff", "--name-only"), runner.lastArgs)
    }

    @Test
    fun `when a default base is configured - then changedFiles uses it without arguments`() {
        // given
        val runner = FakeRunner("Main.kt\n")

        // when
        GitRepo("/repo", runner, defaultBase = "abc123").changedFiles()

        // then
        assertEquals(listOf("git", "-C", "/repo", "diff", "--name-only", "abc123...HEAD"), runner.lastArgs)
    }

    @Test
    fun `when nothing changed - then changedFiles returns a notice`() {
        // when - then
        assertEquals(
            "(no changed files in abc123...HEAD)",
            GitRepo("/repo", FakeRunner("  \n")).changedFiles(base = "abc123"),
        )
        assertEquals("(no unstaged changed files)", GitRepo("/repo", FakeRunner("")).changedFiles())
    }
    //endregion
}
