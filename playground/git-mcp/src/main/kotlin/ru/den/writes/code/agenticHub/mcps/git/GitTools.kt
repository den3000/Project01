package ru.den.writes.code.agenticHub.mcps.git

/**
 * Runs a fully-formed command line (e.g. `git -C <root> status`) and returns its
 * stdout. The impure edge of the server — factored behind a fun interface so the tool
 * logic (arg building + output shaping in [GitRepo]) is unit-tested with a fake runner,
 * with no real `git` process. The production impl is [ProcessCommandRunner].
 */
fun interface CommandRunner {
    fun run(args: List<String>): String
}

/**
 * Read-only git surface over one repository [root], backing the MCP tools. Every call
 * shells out through [runner]; arg construction and output shaping live here so they can
 * be exercised offline (see GitToolsTest). Never mutates the repo — only `rev-parse`,
 * `ls-files`, `diff`.
 */
class GitRepo(private val root: String, private val runner: CommandRunner) {

    /** Current branch name, or a detached-HEAD notice when not on a branch. */
    fun currentBranch(): String {
        val branch = runner.run(git("rev-parse", "--abbrev-ref", "HEAD")).trim()
        return if (branch == "HEAD") "HEAD (detached)" else branch
    }

    /** Tracked files (one path per line), optionally restricted to [subdir]. */
    fun listFiles(subdir: String?): String {
        val args = git("ls-files").let { if (subdir.isNullOrBlank()) it else it + subdir }
        return runner.run(args).trim().ifEmpty { "(no tracked files)" }
    }

    /** Working-tree diff, or the staged (index) diff when [staged]; empty → a clear notice. */
    fun diff(staged: Boolean): String {
        val args = git("diff").let { if (staged) it + "--staged" else it }
        return runner.run(args).ifBlank {
            if (staged) "(no staged changes)" else "(no unstaged changes)"
        }
    }

    /** `git -C <root> <sub…>` — every command is scoped to [root], never the cwd. */
    private fun git(vararg sub: String): List<String> = listOf("git", "-C", root) + sub
}

/**
 * Production [CommandRunner]: spawns the command as a subprocess, captures stdout, and
 * waits for exit. On a non-zero exit it returns the trimmed stderr prefixed with
 * `git error:` so the failure surfaces as tool output instead of throwing across the
 * JSON-RPC boundary.
 */
class ProcessCommandRunner : CommandRunner {
    override fun run(args: List<String>): String {
        val process = ProcessBuilder(args).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return if (code == 0) stdout else "git error: ${stderr.trim().ifEmpty { "exit $code" }}"
    }
}
