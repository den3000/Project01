package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.SessionCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** rule entity: startup `-rule` → MemoryOp, in-session `/rule` → SessionCommand. */
class CliArgsToStartCommandMapperRuleTest {

    //region flags
    @Test
    fun `when rule flags are used - then they map to the matching MemoryOp`() {
        // given
        val parser = createMapper()
        val cases = listOf(
            "-rule \"always kotlin\"" to MemoryAction.AddRule("always kotlin"),
            "-rule clear 003" to MemoryAction.RemoveRule("003"),
            "-rule clear" to MemoryAction.ClearRules,
        )

        // when - then
        cases.forEach { (input, action) ->
            assertEquals(StartCommand.MemoryOp(action), parser.parse(input.toArgsArray()), input)
        }
    }

    @Test
    fun `when rule flags are invalid - then rejected`() {
        // given
        val parser = createMapper()
        val cases = listOf(
            "-rule", // bare rule list has no startup target — not expressible
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { parser.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a rule command is used - then it behaves accordingly`() {
        // given
        val mapper = ControlsToIntent()
        val cases = listOf(
            "/rule \"always kotlin\"" to SessionCommand.AddRule("always kotlin"),
            "/rule clear 003" to SessionCommand.RemoveRule("003"),
            "/rule clear" to SessionCommand.ClearRules,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
