package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError

import ru.den.writes.code.agenticHub.features.viewmodel.SessionCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * session entity + adjacent admin (inflate / memory): startup → admin StartCommand;
 * `/session` isn't an in-session command (→ null), `/memory` shows the layer.
 */
class CliArgsToStartCommandMapperSessionTest {

    //region flags
    @Test
    fun `when session, inflate or memory flags are used - then they map to the admin command`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-session" to ru.den.writes.code.agenticHub.features.viewmodel.command.StartCommand.ListSessions,
            "-session clear" to ru.den.writes.code.agenticHub.features.viewmodel.command.StartCommand.CleanHistory,
            "-session clear demo" to ru.den.writes.code.agenticHub.features.viewmodel.command.StartCommand.CleanSession("demo"),
            "-inflate 5 -session demo" to ru.den.writes.code.agenticHub.features.viewmodel.command.StartCommand.InflateSession("demo", 5),
            "-memory" to ru.den.writes.code.agenticHub.features.viewmodel.command.StartCommand.MemoryOp(
                ru.den.writes.code.agenticHub.features.viewmodel.command.MemoryAction.Show),
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parseOk(input.toArgsArray()), input) }
    }

    @Test
    fun `when session or inflate flags are invalid - then rejected`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val notExpressible = listOf(
            "-session show", // per-session show has no startup target
        )

        // when - then
        notExpressible.forEach { input ->
            mapper.assertInvalid(input.toArgsArray(), input)
        }
        // inflate without a session is a missing-required, not an invalid-value
        assertIs<ParseError.MissingArg>(mapper.parseErr("-inflate 5".toArgsArray()))
    }
    //endregion

    //region commands
    @Test
    fun `when a session or memory command is used - then it behaves accordingly`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
        val cases: List<Pair<String, SessionCommand?>> = listOf(
            "/memory" to SessionCommand.ShowMemory,
            "/session" to null, // session is not an in-session command → a normal prompt
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
