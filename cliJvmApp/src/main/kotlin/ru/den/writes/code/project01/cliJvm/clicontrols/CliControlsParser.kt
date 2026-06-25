package ru.den.writes.code.project01.cliJvm.clicontrols

/**
 * The ONE parser for both fronts. It owns no per-control knowledge — it walks
 * [CliControls] (the descriptor catalog) and produces a [ParsedControl]. The same
 * recursion handles a startup flag (`-profile work style "terse"`) and the
 * identical in-session command (`/profile work style "terse"`); only the leading
 * prefix differs.
 *
 * Grammar (per control): `head [value] (sub)*`, where a token is a *sub* of the
 * current chain iff the catalog says so — otherwise it's this level's value, or it
 * belongs to an ancestor (which is how `agent`'s flat option bag terminates each
 * sub). Subs are a LIST, so both nested chains and flat bags fall out of one loop.
 */
internal class CliControlsParser {

    /** Parse a single typed line like `/profile work` ([CMD]) or `-prompt hi` ([FLAG]). */
    fun parse(line: String, surface: Surface): ParseResult = parseTokens(tokenize(line), surface)

    /**
     * Parse a whole startup argv: split into control groups (each starts at a
     * `-`-prefixed token), parse each on [FLAG], then run declarative
     * cross-validation (`requires` / `excludes`). Collects ALL errors.
     */
    fun parseArgv(args: List<String>): BatchResult {
        val groups = splitGroups(args)
        val controls = mutableListOf<ParsedControl>()
        val errors = mutableListOf<ParseError>()
        for (group in groups) {
            when (val r = parseTokens(group, Surface.FLAG)) {
                is ParseResult.Ok -> controls.add(r.control)
                is ParseResult.Err -> errors.add(r.error)
            }
        }
        errors += crossValidate(controls)
        return BatchResult(controls, errors)
    }

    // ---- core -------------------------------------------------------------

    private fun parseTokens(tokens: List<String>, surface: Surface): ParseResult {
        if (tokens.isEmpty()) return ParseResult.Err(ParseError.Empty)
        val head = stripPrefix(tokens[0], surface)
            ?: return ParseResult.Err(ParseError.BadPrefix(tokens[0], surface))
        val arg = CliControlsArg.of(head) ?: return ParseResult.Err(ParseError.UnknownControl(head))
        val spec = CliControls.topLevel(arg, surface) ?: return ParseResult.Err(ParseError.WrongSurface(head, surface))

        return when (val node = parseNode(spec, listOf(arg), tokens.drop(1), surface)) {
            is NodeResult.Err -> ParseResult.Err(node.error)
            is NodeResult.Ok -> {
                val leftover = tokens.drop(1).drop(node.consumed)
                if (leftover.isNotEmpty()) ParseResult.Err(ParseError.UnexpectedToken(leftover.first()))
                else ParseResult.Ok(node.control)
            }
        }
    }

    /**
     * Parse [spec] (whose ancestor [chain] ends at it) over [tokens]: consume a
     * value if the next token isn't a sub of [chain], then loop over subs of
     * [chain]. Returns the node and how many of [tokens] it ate (so the caller
     * resumes after it).
     */
    private fun parseNode(
        spec: ControlSpec,
        chain: List<CliControlsArg>,
        tokens: List<String>,
        surface: Surface,
    ): NodeResult {
        var pos = 0
        var value: String? = null

        if (spec.value != null) {
            val next = tokens.getOrNull(0)
            val nextIsSub = next != null && CliControls.subOf(chain, next) != null
            when {
                next != null && !nextIsSub -> {
                    // valueSurfaces only gates a top-level head (e.g. session name = startup only);
                    // a sub inherits its parent's front, so it isn't re-checked here.
                    if (spec.isTopLevel && surface !in spec.valueSurfaces) {
                        return NodeResult.Err(ParseError.ValueNotAllowedHere(spec.arg, surface))
                    }
                    spec.value.kind.validate(next)?.let { return NodeResult.Err(ParseError.BadValue(spec.arg, next, it)) }
                    value = next
                    pos = 1
                }
                spec.value.required -> return NodeResult.Err(ParseError.MissingValue(spec.arg))
            }
        }

        val subs = mutableListOf<ParsedControl>()
        while (pos < tokens.size) {
            val token = tokens[pos]
            val subSpec = CliControls.subOf(chain, token) ?: break // not ours → an ancestor's, or leftover
            subSpec.parentValueIn?.let { allowed ->
                if (value !in allowed) {
                    return NodeResult.Err(ParseError.WrongParentValue(subSpec.arg, spec.arg, value, allowed))
                }
            }
            when (val child = parseNode(subSpec, chain + subSpec.arg, tokens.drop(pos + 1), surface)) {
                is NodeResult.Err -> return child
                is NodeResult.Ok -> {
                    subs.add(child.control)
                    pos += 1 + child.consumed
                }
            }
        }
        return NodeResult.Ok(ParsedControl(spec, value, subs), pos)
    }

    private sealed interface NodeResult {
        data class Ok(val control: ParsedControl, val consumed: Int) : NodeResult
        data class Err(val error: ParseError) : NodeResult
    }

    // ---- helpers ----------------------------------------------------------

    /** Strip a head token's surface prefix, or null if it doesn't have it. */
    private fun stripPrefix(token: String, surface: Surface): String? =
        if (surface.prefix.isNotEmpty() && token.startsWith(surface.prefix)) token.substring(surface.prefix.length)
        else if (surface.prefix.isEmpty()) token
        else null

    /**
     * Split argv into groups, each beginning at a flag head. A `-`-prefixed token
     * opens a new group only if it *names* a known control; otherwise it's a value
     * of the current group — so a `-`-leading value (`-3`, `-v`) reaches its flag
     * instead of being mistaken for a new one. (A value literally equal to
     * `-<known-flag>` is still misread; that's rare — quote it.)
     */
    private fun splitGroups(args: List<String>): List<List<String>> {
        val groups = mutableListOf<MutableList<String>>()
        for (a in args) {
            if (groups.isEmpty() || startsControl(a)) groups.add(mutableListOf(a))
            else groups.last().add(a)
        }
        return groups
    }

    /** A `-`-prefixed token whose remainder is a known control word (so it heads a group). */
    private fun startsControl(token: String): Boolean =
        token.startsWith(Surface.FLAG.prefix) &&
            CliControlsArg.of(token.substring(Surface.FLAG.prefix.length)) != null

    /** Cross-control constraints, evaluated over every arg present anywhere in the parse. */
    private fun crossValidate(controls: List<ParsedControl>): List<ParseError> {
        val present = mutableSetOf<CliControlsArg>()
        fun collect(c: ParsedControl) { present.add(c.arg); c.subs.forEach(::collect) }
        controls.forEach(::collect)

        val errors = mutableListOf<ParseError>()
        fun check(c: ParsedControl) {
            c.spec.requires.forEach { if (it !in present) errors.add(ParseError.Requires(c.arg, it)) }
            c.spec.excludes.forEach { if (it in present) errors.add(ParseError.Conflicts(c.arg, it)) }
            c.subs.forEach(::check)
        }
        controls.forEach(::check)
        return errors
    }

    /** Whitespace tokenizer that keeps `"double quoted"` segments together (for values with spaces). */
    private fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in input.trim()) {
            when {
                c == '"' -> inQuote = !inQuote
                c.isWhitespace() && !inQuote -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
