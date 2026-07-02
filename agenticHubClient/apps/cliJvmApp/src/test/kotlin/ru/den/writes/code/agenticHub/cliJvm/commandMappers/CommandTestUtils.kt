package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.cliJvm.ApiKeys
import ru.den.writes.code.agenticHub.cliJvm.ModelProviderFactory
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError
import ru.den.writes.code.agenticHub.cliJvm.cliargs.toArgsList
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.CliArgToSessionCommandMapper
import ru.den.writes.code.agenticHub.cliJvm.commandMappers.CliArgsToStartCommandMapper
import kotlin.test.assertFalse

/**
 * Shared helpers for the command-mapping tests (`ParsedArg → StartCommand` front).
 * The `argv → ParsedArg` grammar is covered in `cliargs/grammar`; here the
 * input is a single command line, split via [toArgsArray], and the assertions are
 * about the mapped [ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand].
 */

internal fun createCliArgsToStartCommandMapper(): CliArgsToStartCommandMapper =
    CliArgsToStartCommandMapper(
        CliArgsParser(),
        ModelProviderFactory(
            ApiKeys(
                DUMMY_GEMINI_KEY,
                DUMMY_OPENROUTER_KEY,
                DUMMY_HUGGINGFACE_KEY
            )
        ),
    )

/** The in-session `/`-command mapper for the mapping tests. */
internal fun createCliArgToSessionCommandMapper(): CliArgToSessionCommandMapper =
    CliArgToSessionCommandMapper(CliArgsParser())

/** Split a command line into argv, honouring double-quoted spans (delegates to the cliargs tokenizer). */
internal fun String.toArgsArray(): Array<String> = toArgsList().toTypedArray()

/** Parse [args] expecting success, returning the mapped [StartCommand]. */
internal fun CliArgsToStartCommandMapper.parseOk(args: Array<String>): StartCommand =
    (parse(args) as ParsedStartCommand.Ok).command

/** Parse [args] expecting a rejection, returning the [ParseError]. */
internal fun CliArgsToStartCommandMapper.parseErr(args: Array<String>): ParseError =
    (parse(args) as ParsedStartCommand.Err).error

/**
 * Parse [args] expecting an invalid-value rejection — one that is NOT a
 * [ParseError.MissingArg] (so `main` would not print USAGE). Covers both the
 * parser's rich variants (bad value, wrong surface, …) and the mapper's semantic
 * [ParseError.Invalid]; the exact variant is an implementation detail here.
 */
internal fun CliArgsToStartCommandMapper.assertInvalid(args: Array<String>, message: String? = null): ParseError {
    val error = parseErr(args)
    assertFalse(error is ParseError.MissingArg, message ?: "expected a non-missing rejection, got $error")
    return error
}

private const val DUMMY_GEMINI_KEY = "test-gemini-key"
private const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
private const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
