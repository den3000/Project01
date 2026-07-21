package ru.den.writes.code.agenticHub.mcps.projectfs

import java.io.File

/**
 * File names that carry credentials. Closed for reading: whatever a tool returns lands in
 * the model's context verbatim, so a single careless read leaks a key into a transcript.
 */
private val SECRET_NAMES = setOf("local.properties", ".env", "id_rsa", "id_ed25519", "secring.gpg")

/** Extensions of credential stores — same reasoning as [SECRET_NAMES]. */
private val SECRET_EXTENSIONS = setOf("jks", "keystore", "p12", "pem", "key")

/**
 * The path gate for one project root: turns a model-supplied path into an absolute path
 * *proven* to sit inside the root, or into an explanation of why it doesn't. Every tool
 * resolves here before touching disk, so [FileIo] only ever sees vetted paths.
 *
 * [canonicalize] is injected so the whole gate — traversal, symlinks, sibling-root
 * prefixes — is unit-tested with no real filesystem; production passes `canonicalPath`.
 * Canonicalising is what makes the check sound: it expands `..`, `.` *and* symlinks
 * before containment is decided, so a link pointing out of the tree can't smuggle a read.
 *
 * [writeExtensions] narrows what may be written — e.g. `md` during a documentation task,
 * which makes an incidental edit to a `.kt` file impossible rather than merely discouraged.
 * Empty means any extension.
 */
class ProjectPaths(
    root: String,
    private val writeExtensions: Set<String> = emptySet(),
    private val canonicalize: (String) -> String = { File(it).canonicalPath },
) {

    /** The root itself, canonical and without a trailing slash — the prefix every path must match. */
    private val canonicalRoot: String = canonicalize(root).trimEnd('/')

    /** Either a vetted path or the reason it was refused; the reason is shown to the model verbatim. */
    sealed interface Resolved {
        /** [rel] is the path relative to the root (how the model should refer to it), [absolute] is for I/O. */
        data class Ok(val rel: String, val absolute: String) : Resolved
        data class Denied(val reason: String) : Resolved
    }

    /**
     * Absolute path for a [rel] that came out of [FileIo.walk] — already inside the root
     * by construction, so no canonicalisation is needed.
     *
     * This is the cheap path, and it exists to stay cheap: routing a whole listing through
     * [resolveRead] would spend a `canonicalPath` syscall per file to re-prove something
     * the walk established once.
     */
    fun absoluteOf(rel: String): String = "$canonicalRoot/$rel"

    /**
     * Whether [rel] is closed to the tools — the `.git` directory or a credential file.
     *
     * A pure predicate over a path already known to be inside the root, so a listing can
     * drop closed paths outright. Marking them instead would report the existence of every
     * secret in the tree, which is most of what a name like `.env` gives away.
     */
    fun isClosed(rel: String): Boolean {
        if (".git" in rel.split('/')) return true
        val name = rel.substringAfterLast('/')
        return name in SECRET_NAMES || name.substringAfterLast('.', "").lowercase() in SECRET_EXTENSIONS
    }

    /**
     * Resolve [path] for reading. Absolute paths inside the root are accepted and rebased —
     * models supply them constantly, and refusing would burn a tool round on a path the
     * gate can perfectly well understand.
     */
    fun resolveRead(path: String): Resolved {
        val resolved = when (val candidate = resolve(path)) {
            is Resolved.Denied -> return candidate
            is Resolved.Ok -> candidate
        }
        if (isClosed(resolved.rel)) {
            // .git/config can hold `https://user:token@host/...`, and local.properties holds
            // API keys — reading either would put a credential straight into the model's context.
            return Resolved.Denied("'${resolved.rel}': путь закрыт (секреты или .git)")
        }
        return resolved
    }

    /**
     * Resolve [path] for writing: everything [resolveRead] demands, plus no writes under
     * [NOISE_SEGMENTS] — build output and tool state are generated, not authored — and,
     * when [writeExtensions] is set, only those extensions.
     */
    fun resolveWrite(path: String): Resolved {
        val resolved = when (val candidate = resolveRead(path)) {
            is Resolved.Denied -> return candidate
            is Resolved.Ok -> candidate
        }
        val noise = resolved.rel.split('/').dropLast(1).firstOrNull { it in NOISE_SEGMENTS }
        if (noise != null) {
            return Resolved.Denied("'${resolved.rel}': запись под '$noise/' запрещена")
        }
        if (writeExtensions.isNotEmpty() && extensionOf(resolved.rel) !in writeExtensions) {
            val allowed = writeExtensions.sorted().joinToString(", ") { ".$it" }
            return Resolved.Denied("'${resolved.rel}': запись разрешена только для $allowed")
        }
        return resolved
    }

    /** Canonicalise, then prove the root is a prefix — the containment check itself. */
    private fun resolve(path: String): Resolved {
        if (path.isBlank()) return Resolved.Denied("пустой путь")
        val candidate = if (path.startsWith("/")) path else "$canonicalRoot/$path"
        val absolute = canonicalize(candidate).trimEnd('/')
        // The separator is load-bearing: a bare startsWith(root) would also accept the
        // sibling directory "/repo-evil" when the root is "/repo".
        if (absolute != canonicalRoot && !absolute.startsWith("$canonicalRoot/")) {
            return Resolved.Denied("'$path' вне корня проекта")
        }
        val rel = absolute.removePrefix(canonicalRoot).trimStart('/')
        if (rel.isEmpty()) return Resolved.Denied("'$path' — это сам корень, а не файл")
        return Resolved.Ok(rel = rel, absolute = absolute)
    }
}
