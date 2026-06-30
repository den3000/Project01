package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.clicontrols.toArgsList

/**
 * Shared helpers for the command-mapping tests (`ParsedControl → CliCommand` front).
 * The `argv → ParsedControl` grammar is covered in `clicontrols/grammar`; here the
 * input is a single command line, split via [toArgsArray], and the assertions are
 * about the mapped [CliCommand].
 */

internal fun createCommandsParser(): CliControlsCommandParser =
    CliControlsCommandParser(ApiKeys(DUMMY_GEMINI_KEY, DUMMY_OPENROUTER_KEY, DUMMY_HUGGINGFACE_KEY))

/** Split a command line into argv, honouring double-quoted spans (delegates to the clicontrols tokenizer). */
internal fun String.toArgsArray(): Array<String> = toArgsList().toTypedArray()

private const val DUMMY_GEMINI_KEY = "test-gemini-key"
private const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
private const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
