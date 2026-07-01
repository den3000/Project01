package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.cliargs.CliArg
import ru.den.writes.code.project01.cliJvm.cliargs.ParsedArg

/**
 * Small read helpers over the parsed cliargs controls, shared by the command
 * mapper and the model-provider factory.
 */
internal fun ParsedArg.subValue(arg: CliArg): String? = sub(arg)?.value
internal fun List<ParsedArg>.last(arg: CliArg): ParsedArg? = lastOrNull { it.arg == arg }
internal fun List<ParsedArg>.has(arg: CliArg): Boolean = any { it.arg == arg }
