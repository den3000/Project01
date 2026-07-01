package ru.den.writes.code.project01.cliJvm.cliargs

/** Task FSM stages, in order — the legal endpoints of a `stages <from..to>` range. */
private val STAGE_ORDER = listOf("clarification", "planning", "execution", "validation", "done")

/**
 * What value (if any) a control accepts after its token, validated declaratively.
 * [validate] returns null when [raw] is acceptable, otherwise a short reason; the
 * [placeholder] is what the usage line shows (`<name>`, `<int>`, `<gemini|…>`).
 */
sealed interface ValueKind {
    val placeholder: String
    fun validate(raw: String): String?

    /** Identifier: alphanumeric / `_` / `-`, up to 64 chars (session / profile / task / rule-id names). */
    data object Name : ValueKind {
        private val re = Regex("^[A-Za-z0-9_-]{1,64}$")
        override val placeholder = "<name>"
        override fun validate(raw: String) = if (re.matches(raw)) null else "alphanumeric / '_' / '-', up to 64 chars"
    }

    /** Any non-blank free text. */
    data object Text : ValueKind {
        override val placeholder = "<text>"
        override fun validate(raw: String) = if (raw.isNotBlank()) null else "non-empty text"
    }

    /** A filesystem path (existence isn't checked here — that's a runtime concern). */
    data object Path : ValueKind {
        override val placeholder = "<path>"
        override fun validate(raw: String) = if (raw.isNotBlank()) null else "a path"
    }

    /** An integer with a lower bound and optional upper bound. */
    data class IntRange(val min: Int, val max: Int? = null) : ValueKind {
        override val placeholder = "<int>"
        override fun validate(raw: String): String? {
            val n = raw.toIntOrNull() ?: return "an integer"
            if (n < min) return "an integer >= $min"
            if (max != null && n > max) return "an integer in $min..$max"
            return null
        }
    }

    /** A decimal in [[min], [max]]. */
    data class Decimal(val min: Double, val max: Double) : ValueKind {
        override val placeholder = "<num>"
        override fun validate(raw: String): String? {
            val d = raw.toDoubleOrNull() ?: return "a decimal number"
            return if (d in min..max) null else "a number in $min..$max"
        }
    }

    /** One of a fixed set of words. */
    data class OneOf(val options: Set<String>) : ValueKind {
        override val placeholder = options.joinToString("|", "<", ">")
        override fun validate(raw: String) = if (raw in options) null else "one of: ${options.joinToString(", ")}"
    }

    /** A task-stage range `from..to`: both ends known stages, `from` no later than `to`. */
    data object StageRange : ValueKind {
        override val placeholder = "<from..to>"
        override fun validate(raw: String): String? {
            val parts = raw.split("..")
            if (parts.size != 2) return "a stage range like clarification..planning"
            val from = STAGE_ORDER.indexOf(parts[0])
            val to = STAGE_ORDER.indexOf(parts[1])
            if (from < 0 || to < 0) return "a stage range like clarification..planning"
            if (from > to) return "a stage range with from no later than to"
            return null
        }
    }
}

/** Whether a control takes a value and which [kind] — [required] forces one to follow. */
data class ValueSpec(val kind: ValueKind, val required: Boolean)

/**
 * One control descriptor — the single source of truth for one token's grammar.
 * The catalog ([CliArgs.all]) is a flat list of these; the parser consults it
 * instead of branching on hard-coded constants. This is the data layer; what the
 * user actually invoked is a [ParsedArg] (the result layer).
 *
 * @property arg the token this describes.
 * @property surfaces where it may appear (only checked for a top-level head; subs
 *   inherit their parent's surface context).
 * @property parent the exact ancestor chain for a subcommand (e.g. `style`'s parent
 *   is `[PROFILE]`); null/empty = a top-level control.
 * @property value the value it accepts, or null for a bare flag.
 * @property valueSurfaces surfaces on which a value is allowed — usually [surfaces],
 *   but e.g. `session` accepts a name only at startup (`{FLAG}`), so `/session foo`
 *   is rejected while `/session` (list) is fine.
 * @property requires / [excludes] declarative cross-control constraints (checked by
 *   the batch validator), replacing today's scattered `if (x in values) throw …`.
 * @property parentValueIn for a sub: the parent values it's valid under (e.g.
 *   `summarizeEvery` only under `strategy summary`); null = no such constraint.
 */
data class ArgSpec(
    val arg: CliArg,
    val surfaces: Set<Surface>,
    val parent: List<CliArg>? = null,
    val value: ValueSpec? = null,
    val valueSurfaces: Set<Surface> = surfaces,
    val requires: Set<CliArg> = emptySet(),
    val excludes: Set<CliArg> = emptySet(),
    val parentValueIn: Set<String>? = null,
    val usage: String = "",
) {
    val token: String get() = arg.title
    val isTopLevel: Boolean get() = parent.isNullOrEmpty()
}
