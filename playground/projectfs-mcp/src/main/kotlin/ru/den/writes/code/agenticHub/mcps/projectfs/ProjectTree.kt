package ru.den.writes.code.agenticHub.mcps.projectfs

/**
 * Directory segments never worth walking into (build output, VCS, IDE and tool state).
 *
 * `.kotlin` earned its place the hard way. A KMP project keeps its metadata-transform cache
 * there, and in one live tree that was 524 files out of 677. Listings are sorted, `.kotlin`
 * sorts near the top, and the whole path budget went to `.klib` entries — the listing showed
 * the model not one file of the project it was sent to read, so it fell back to guessing
 * search terms instead of walking the tree.
 */
internal val NOISE_SEGMENTS =
    setOf("build", ".git", ".gradle", ".idea", ".kotlin", ".claude", "node_modules")

/**
 * Extensions whose bytes are useless as model context. Hidden from listings so a binary
 * can never be picked for reading in the first place.
 */
private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "ico", "svg", "pdf",
    "zip", "jar", "aar", "klib", "class", "so", "dylib", "dll", "exe", "bin",
    "ttf", "otf", "woff", "woff2", "kotlin_module", "kotlin_metadata",
)

/**
 * Files no project ever authored: the droppings a desktop OS leaves in every directory
 * it displays. They carry nothing readable, and a listing that shows `.DS_Store (1 line)`
 * spends a line and a `stat` on telling the model that macOS opened the folder.
 *
 * Matched by name, not extension — `.DS_Store` has no extension, its whole name is one.
 */
private val NOISE_FILES = setOf(".ds_store", "thumbs.db", "desktop.ini", ".localized")

/** Extension in lower case, empty when the name carries none. */
internal fun extensionOf(path: String): String =
    path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

/** `"md, kt"` / `".md,.kt"` → `{md, kt}`; blank input means "no filter". */
internal fun parseExtensions(ext: String?): Set<String> =
    ext?.split(',')
        ?.map { it.trim().removePrefix(".").lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

/**
 * Whether [rel] carries one of [extensions], matched as a filename **suffix**.
 *
 * Suffix and not "the part after the last dot": a model asking for `gradle.kts` means
 * `build.gradle.kts`, and comparing against the last segment alone (`kts`) silently
 * matched nothing. That is the worst possible failure here — the search then reports an
 * honest-looking "no matches", the model concludes the term is absent from the project,
 * and writes it up as a violation. Seen live: a whole invariants report of false breaches,
 * every one of them "grounded" in a search that never looked at a single file.
 */
internal fun matchesExtension(rel: String, extensions: Set<String>): Boolean {
    if (extensions.isEmpty()) return true
    val name = rel.substringAfterLast('/').lowercase()
    return extensions.any { name.endsWith(".$it") }
}

/**
 * Why a narrowing selected no files at all, or null when it selected something.
 *
 * An `ext` that matches nothing and a term that occurs nowhere produce the same empty
 * answer, and the difference decides whether "absent from the project" is a finding or a
 * typo. So the two are told apart, and the extensions actually present are named.
 */
internal fun FileIo.emptySelectionHint(paths: ProjectPaths, subdir: String?, ext: String?): String? {
    if (candidates(paths, subdir, ext).isNotEmpty()) return null
    val underSubdir = candidates(paths, subdir, ext = null)
    if (underSubdir.isEmpty()) {
        return "projectfs error: под '${subdir ?: "."}' нет файлов вовсе — проверь путь подкаталога."
    }
    val present = underSubdir.map { extensionOf(it) }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(EXTENSIONS_IN_HINT)
        .joinToString(", ") { ".${it.key} (${it.value})" }
    return "projectfs error: фильтр ext='$ext' не выбрал ни одного файла${subdir?.let { " под '$it'" } ?: ""} — " +
        "искать было негде, и пустой результат НЕ означает, что термина нет. " +
        "Там встречаются: $present. Расширение берётся суффиксом имени, поэтому и 'kts', и 'gradle.kts' подходят."
}

/** Extensions listed in the hint — enough to redirect, not a census. */
private const val EXTENSIONS_IN_HINT = 6

/**
 * Files a listing or a search may touch: no binaries, nothing closed, narrowed to
 * [subdir] and [ext], sorted.
 *
 * Noise directories are absent because [FileIo.walk] never descends into them — filtering
 * them again here would be a branch no input can reach. Closed paths are dropped rather
 * than marked: an entry saying `.env (закрыт)` still tells the model the secret is there.
 */
internal fun FileIo.candidates(paths: ProjectPaths, subdir: String?, ext: String?): List<String> {
    val prefix = subdir?.trim('/')?.takeIf { it.isNotEmpty() }
    val extensions = parseExtensions(ext)
    return walk()
        .filterNot { rel -> rel.substringAfterLast('/').lowercase() in NOISE_FILES }
        .filterNot { rel -> extensionOf(rel) in BINARY_EXTENSIONS }
        .filterNot { rel -> paths.isClosed(rel) }
        .filter { rel -> prefix == null || rel == prefix || rel.startsWith("$prefix/") }
        .filter { rel -> matchesExtension(rel, extensions) }
        .sorted()
}
