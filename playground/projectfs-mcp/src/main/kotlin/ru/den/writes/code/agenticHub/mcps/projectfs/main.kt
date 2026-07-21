package ru.den.writes.code.agenticHub.mcps.projectfs

/**
 * Entry point: `projectfs-mcp <projectRoot> [--write-ext=md,txt]`.
 *
 * The root is the sandbox — every path a tool accepts is resolved against it and proven
 * to stay inside. Omitting it falls back to the client's working directory, which is
 * almost never what you want; pass it explicitly.
 *
 * `--write-ext` narrows what may be written. It is not a permission gate — reading and
 * writing are always on — but a blast radius: with `--write-ext=md` a documentation task
 * cannot touch a source file even if the model decides to.
 */
suspend fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val projectRoot = positional.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "."
    val writeExtensions = args.firstOrNull { it.startsWith("--write-ext=") }
        ?.removePrefix("--write-ext=")
        ?.let(::parseExtensions)
        .orEmpty()
    runProjectFsServer(projectRoot, writeExtensions)
}
