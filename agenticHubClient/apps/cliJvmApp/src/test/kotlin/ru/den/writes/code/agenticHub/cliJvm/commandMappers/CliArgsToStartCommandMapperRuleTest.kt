package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError

import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** rule entity: startup `-rule` → MemoryOp, in-session `/rule` → SessionCommand. */
class CliArgsToStartCommandMapperRuleTest {

    //region flags
    @Test
    fun `when rule flags are used - then they map to the matching MemoryOp`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-rule \"always kotlin\"" to ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction.AddRule("always kotlin"),
            "-rule clear 003" to ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction.RemoveRule("003"),
            "-rule clear" to ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction.ClearRules,
        )

        // when - then
        cases.forEach { (input, action) ->
            kotlin.test.assertEquals(
                ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand.MemoryOp(
                    action
                ), mapper.parseOk(input.toArgsArray()), input
            )
        }
    }

    @Test
    fun `when rule flags are invalid - then rejected`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-rule", // bare rule list has no startup target — not expressible
        )

        // when - then
        cases.forEach { input ->
            mapper.assertInvalid(input.toArgsArray(), input)
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a rule command is used - then it behaves accordingly`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
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
