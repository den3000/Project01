package ru.den.writes.code.project01.cliJvm.clicontrols

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun top(
    arg: CliControlsArg,
    surface: Surface,
    value: String? = null,
    subs: List<ParsedControl> = emptyList(),
): ParsedControl = ParsedControl(requireNotNull(CliControls.topLevel(arg, surface)), value, subs)

internal fun sub(
    parent: CliControlsArg,
    arg: CliControlsArg,
    value: String? = null,
    subs: List<ParsedControl> = emptyList(),
): ParsedControl = ParsedControl(requireNotNull(CliControls.subOf(listOf(parent), arg.title)), value, subs)

data class ExpectedControl(
    val surface: Surface,
    val arg: CliControlsArg,
    val subs: List<ParsedControl> = emptyList(),
    val value: String? = null,
)

internal fun assertMatchParserCmd(
    input: String,
    expected: ExpectedControl,
    parser: CliControlsParser,
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
    parser: CliControlsParser,
) {
    val actual = parser.parse(input, surface)

    // then
    assertIs<ParseResult.Err>(actual)
    assertEquals(expected, actual.error)
}

internal fun assertMatchParserFlag(
    input: List<String>,
    expected: List<ParsedControl>,
    parser: CliControlsParser,
) {
    val actual = parser.parseArgv(input)
    assertTrue(actual.errors.isEmpty(), actual.errors.joinToString { it.toString() + it.message })
    assertEquals(expected, actual.controls)
}

internal fun assertMatchParserFlag(
    input: List<String>,
    expected: ParsedControl,
    parser: CliControlsParser,
) {
    assertMatchParserFlag(input, listOf(expected), parser)
}

internal fun assertMatchParserError(
    input: List<String>,
    errors: List<ParseError>,
    parser: CliControlsParser,
) {
    val actual = parser.parseArgv(input)
    assertEquals(errors, actual.errors)
}

internal fun assertMatchParserError(
    input: List<String>,
    error: ParseError,
    parser: CliControlsParser,
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