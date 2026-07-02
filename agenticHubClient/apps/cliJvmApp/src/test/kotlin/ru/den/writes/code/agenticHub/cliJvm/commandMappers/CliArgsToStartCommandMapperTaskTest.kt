package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError

import ru.den.writes.code.agenticHub.features.viewmodel.SessionCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** task entity: startup `-task` → MemoryOp, in-session `/task` → SessionCommand. */
class CliArgsToStartCommandMapperTaskTest {

    //region flags
    @Test
    fun `when task flags are used - then they map to the matching MemoryOp`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-task auth" to ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction.SetTask("auth"),
            "-task auth pause" to ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction.PauseTask("auth"),
            "-task auth resume" to ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction.ResumeTask("auth"),
            "-task clear auth" to ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction.DeleteTask("auth"),
            "-task clear" to ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction.ClearTasks,
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
    fun `when task flags are invalid - then rejected`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-task auth note \"did x\"", // task note has no startup target — not expressible
        )

        // when - then
        cases.forEach { input ->
            mapper.assertInvalid(input.toArgsArray(), input)
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a task command is used - then it behaves accordingly`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
        val cases = listOf(
            "/task auth" to SessionCommand.SetTask("auth"),
            "/task note \"did x\"" to SessionCommand.AppendTaskNote("did x"),
            "/task pause" to SessionCommand.PauseTask,
            "/task resume" to SessionCommand.ResumeTask,
            "/task clear auth" to SessionCommand.DeleteTask("auth"),
            "/task clear" to SessionCommand.ClearTasks,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
