package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Hard ceiling on any tool's output — the last line of defence, see [respond]. */
internal const val MAX_OUTPUT_CHARS = 24_000

/**
 * A tool argument that is absent or unusable. Thrown rather than returned so every
 * extraction site is a single expression; [ProjectFsTools.respond] turns it into the same
 * `projectfs error:` text any other failure produces.
 */
internal class MissingArgument(name: String) : IllegalArgumentException("обязателен аргумент '$name'")

/**
 * The dispatch layer: JSON arguments in, model-facing text out.
 *
 * It sits between the MCP plumbing and the tool bodies so the two properties nothing
 * downstream provides are guaranteed in one place, and can be tested without a transport:
 *
 * - **total** — an exception thrown out of a tool tears down the client's whole turn (the
 *   host's `execute` has no catch), so a failure has to come back as text, the way
 *   `git error: …` does in git-mcp;
 * - **bounded** — nothing between here and the model truncates anything, so this is the
 *   only place where the size of a result can be contained at all.
 */
class ProjectFsTools(
    private val listing: ProjectListing,
    private val reader: ProjectReader,
    private val search: ProjectSearch,
    private val writer: ProjectWriter,
) {

    fun listProjectFiles(arguments: JsonObject?): String = respond {
        listing.list(
            subdir = arguments.string("subdir"),
            ext = arguments.string("ext"),
            limit = arguments.int("limit"),
        )
    }

    fun readProjectFile(arguments: JsonObject?): String = respond {
        reader.read(
            path = arguments.nonBlank("path"),
            offset = arguments.int("offset"),
            limit = arguments.int("limit"),
        )
    }

    fun searchProjectFiles(arguments: JsonObject?): String = respond {
        search.search(
            query = arguments.nonBlank("query"),
            subdir = arguments.string("subdir"),
            ext = arguments.string("ext"),
            regex = arguments.bool("regex", default = false),
            ignoreCase = arguments.bool("ignoreCase", default = false),
            filesOnly = arguments.bool("filesOnly", default = false),
            maxMatches = arguments.int("maxMatches"),
        )
    }

    fun writeProjectFile(arguments: JsonObject?): String = respond {
        writer.write(
            path = arguments.nonBlank("path"),
            content = arguments.present("content"),
        )
    }

    fun replaceInProjectFile(arguments: JsonObject?): String = respond {
        writer.replace(
            path = arguments.nonBlank("path"),
            old = arguments.present("old"),
            new = arguments.present("new"),
            replaceAll = arguments.bool("replaceAll", default = false),
        )
    }

    /**
     * Run [body], turning any failure into text and capping what comes back.
     *
     * A blank message is treated as no message: `IllegalStateException("")` is common
     * enough (any `error("")`) and "projectfs error: " tells the model nothing, where the
     * exception's type at least names what broke.
     */
    private fun respond(body: () -> String): String =
        runCatching(body)
            .getOrElse { failure ->
                val cause = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName
                "projectfs error: $cause"
            }
            .clampTo(MAX_OUTPUT_CHARS)
}

/** Optional string argument; absent, null and JSON-null all read as "not given". */
internal fun JsonObject?.string(name: String): String? = this?.get(name)?.jsonPrimitive?.contentOrNull

/**
 * Optional integer argument.
 *
 * A model that sends `"limit": "20"` gets the number rather than a refusal — the string
 * form is common enough that rejecting it would burn a tool round on nothing.
 */
internal fun JsonObject?.int(name: String): Int? = string(name)?.trim()?.toIntOrNull()

/** Optional boolean argument, tolerating the string form for the same reason as [int]. */
internal fun JsonObject?.bool(name: String, default: Boolean): Boolean =
    when (string(name)?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }

/** A required argument that also has to carry something — a path, a query. */
internal fun JsonObject?.nonBlank(name: String): String =
    string(name)?.takeIf { it.isNotBlank() } ?: throw MissingArgument(name)

/**
 * A required argument that may legitimately be empty — `new` in a replacement, where the
 * empty string means "delete this fragment".
 */
internal fun JsonObject?.present(name: String): String = string(name) ?: throw MissingArgument(name)

/** Truncate to [max] characters without splitting a surrogate pair, and say so. */
internal fun String.clampTo(max: Int): String {
    if (length <= max) return this
    val cut = if (this[max - 1].isHighSurrogate()) max - 1 else max
    return take(cut) + "\n… (вывод обрезан: $cut из $length символов)"
}
