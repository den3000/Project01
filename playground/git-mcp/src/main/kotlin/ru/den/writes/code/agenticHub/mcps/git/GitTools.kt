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
 *
 * [defaultBase] is the branch point a review compares against (a PR's base). When set,
 * [diff] and [changedFiles] describe that range unless a call overrides it, so a caller
 * that already knows the base — a CI pipeline — configures it once and the model needs
 * no ref argument at all. Unset, they fall back to the working tree.
 */
class GitRepo(
    private val root: String,
    private val runner: CommandRunner,
    private val defaultBase: String? = null,
) {

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

    /**
     * The diff: over the [base]…[head] range when a base is given (or [defaultBase] is
     * set), else the working tree — or the staged (index) diff when [staged]. Empty
     * output becomes a clear notice.
     */
    fun diff(base: String? = null, head: String? = null, staged: Boolean = false): String {
        val range = rangeOf(base, head)
        val args = when {
            range != null -> git("diff", range)
            staged -> git("diff", "--staged")
            else -> git("diff")
        }
        return runner.run(args).ifBlank { emptyNotice(range, staged, "changes") }
    }

    /** The changed files (one path per line) over the same range [diff] describes. */
    fun changedFiles(base: String? = null, head: String? = null): String {
        val range = rangeOf(base, head)
        val args = if (range != null) git("diff", "--name-only", range) else git("diff", "--name-only")
        return runner.run(args).trim().ifEmpty { emptyNotice(range, staged = false, what = "changed files") }
    }

    /**
     * `<base>...<head>` — three dots: what [head] changed *since it forked from* [base],
     * ignoring what landed on the base meanwhile. That is what reviewing a PR means.
     * `null` when no base is known (neither the call nor [defaultBase] supplies one).
     */
    private fun rangeOf(base: String?, head: String?): String? {
        val from = base?.takeIf { it.isNotBlank() } ?: defaultBase?.takeIf { it.isNotBlank() } ?: return null
        return "$from...${head?.takeIf { it.isNotBlank() } ?: "HEAD"}"
    }

    private fun emptyNotice(range: String?, staged: Boolean, what: String): String = when {
        range != null -> "(no $what in $range)"
        staged -> "(no staged $what)"
        else -> "(no unstaged $what)"
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
