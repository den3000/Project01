package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.SessionCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
            "-session" to StartCommand.ListSessions,
            "-session clear" to StartCommand.CleanHistory,
            "-session clear demo" to StartCommand.CleanSession("demo"),
            "-inflate 5 -session demo" to StartCommand.InflateSession("demo", 5),
            "-memory" to StartCommand.MemoryOp(MemoryAction.Show),
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input.toArgsArray()), input) }
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
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { mapper.parse(input.toArgsArray()) }
        }
        // inflate without a session is a missing-required, not an invalid-value
        assertFailsWith<CliArgsException.MissingRequiredArgument> { mapper.parse("-inflate 5".toArgsArray()) }
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
