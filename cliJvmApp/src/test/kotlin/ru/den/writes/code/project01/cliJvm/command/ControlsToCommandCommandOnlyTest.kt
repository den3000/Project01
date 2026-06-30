package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Command-only controls reuse / exit / help: as a startup flag they are WrongSurface;
 * the mapper leaves them to the REPL (`null`) — `StdinPromptSource` handles `/quit`,
 * `/exit`, `/reuse` directly, ahead of the catalog classifier.
 */
class ControlsToCommandCommandOnlyTest {

    //region flags
    @Test
    fun `when a command-only control is used as a startup flag - then rejected`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-prompt hi -reuse",
            "-prompt hi -exit",
            "-prompt hi -help",
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { parser.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a command-only control is typed - then the mapper leaves it to the REPL`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases: List<Pair<String, BranchCommand?>> = listOf(
            "/reuse" to null,
            "/exit" to null,
            "/help" to null,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
