package ru.den.writes.code.project01.cliJvm.cliargs

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun top(
    arg: CliArg,
    surface: Surface,
    value: String? = null,
    subs: List<ParsedArg> = emptyList(),
): ParsedArg = ParsedArg(requireNotNull(CliArgs.topLevel(arg, surface)), value, subs)

internal fun sub(
    parent: CliArg,
    arg: CliArg,
    value: String? = null,
    subs: List<ParsedArg> = emptyList(),
): ParsedArg = ParsedArg(requireNotNull(CliArgs.subOf(listOf(parent), arg.title)), value, subs)

data class ExpectedControl(
    val surface: Surface,
    val arg: CliArg,
    val subs: List<ParsedArg> = emptyList(),
    val value: String? = null,
)

internal fun assertMatchParserCmd(
    input: String,
    expected: ExpectedControl,
    parser: CliArgsParser,
) {
    val actual = parser.parse(input, Surface.CMD)

    // then
    assertIs<ParseResult.Ok>(actual)
    assertEquals(top(expected.arg, expected.surface, expected.value, expected.subs ), actual.control)
}

internal fun assertMatchParserError(
    surface: Surface,
    input: String,
    expected: ParseError,
    parser: CliArgsParser,
) {
    val actual = parser.parse(input, surface)

    // then
    assertIs<ParseResult.Err>(actual)
    assertEquals(expected, actual.error)
}

internal fun assertMatchParserFlag(
    input: List<String>,
    expected: List<ParsedArg>,
    parser: CliArgsParser,
) {
    val actual = parser.parseArgv(input)
    assertTrue(actual.errors.isEmpty(), actual.errors.joinToString { it.toString() + it.message })
    assertEquals(expected, actual.controls)
}

internal fun assertMatchParserFlag(
    input: List<String>,
    expected: ParsedArg,
    parser: CliArgsParser,
) {
    assertMatchParserFlag(input, listOf(expected), parser)
}

internal fun assertMatchParserError(
    input: List<String>,
    errors: List<ParseError>,
    parser: CliArgsParser,
) {
    val actual = parser.parseArgv(input)
    assertEquals(errors, actual.errors)
}

internal fun assertMatchParserError(
    input: List<String>,
    error: ParseError,
    parser: CliArgsParser,
) {
    assertMatchParserError(input, listOf(error), parser)
}

internal fun String.toArgsList(): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuote = false
    for (c in this.trim()) {
        when {
            c == '"' -> inQuote = !inQuote
            c.isWhitespace() && !inQuote -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}
