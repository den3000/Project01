package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError

import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Command-only controls reuse / exit / help: as a startup flag they are WrongSurface;
 * the mapper leaves them to the REPL (`null`) — `StdinPromptSource` handles `/quit`,
 * `/exit`, `/reuse` directly, ahead of the catalog classifier.
 */
class CliArgsToStartCommandMapperCommandOnlyTest {

    //region flags
    @Test
    fun `when a command-only control is used as a startup flag - then rejected`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-prompt hi -reuse",
            "-prompt hi -exit",
            "-prompt hi -help",
        )

        // when - then
        cases.forEach { input ->
            mapper.assertInvalid(input.toArgsArray(), input)
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a command-only control is typed - then the mapper leaves it to the REPL`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
        val cases: List<Pair<String, SessionCommand?>> = listOf(
            "/reuse" to null,
            "/exit" to null,
            "/help" to null,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
