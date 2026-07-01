package ru.den.writes.code.project01.cliJvm.commandMappers

import ru.den.writes.code.project01.cliJvm.ApiKeys
import ru.den.writes.code.project01.cliJvm.ModelProviderFactory
import ru.den.writes.code.project01.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.project01.cliJvm.cliargs.toArgsList
import ru.den.writes.code.project01.cliJvm.commandMappers.CliArgToSessionCommandMapper
import ru.den.writes.code.project01.cliJvm.commandMappers.CliArgsToStartCommandMapper

/**
 * Shared helpers for the command-mapping tests (`ParsedArg → StartCommand` front).
 * The `argv → ParsedArg` grammar is covered in `cliargs/grammar`; here the
 * input is a single command line, split via [toArgsArray], and the assertions are
 * about the mapped [ru.den.writes.code.project01.cliJvm.command.StartCommand].
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

private const val DUMMY_GEMINI_KEY = "test-gemini-key"
private const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
private const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
