package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** task entity: startup `-task` → MemoryOp, in-session `/task` → BranchCommand. */
class ControlsToCommandTaskTest {

    //region flags
    @Test
    fun `when task flags are used - then they map to the matching MemoryOp`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-task auth" to MemoryAction.SetTask("auth"),
            "-task auth pause" to MemoryAction.PauseTask("auth"),
            "-task auth resume" to MemoryAction.ResumeTask("auth"),
            "-task clear auth" to MemoryAction.DeleteTask("auth"),
            "-task clear" to MemoryAction.ClearTasks,
        )

        // when - then
        cases.forEach { (input, action) ->
            assertEquals(CliCommand.MemoryOp(action), parser.parse(input.toArgsArray()), input)
        }
    }

    @Test
    fun `when task flags are invalid - then rejected`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-task auth note \"did x\"", // task note has no startup target — not expressible
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { parser.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a task command is used - then it behaves accordingly`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases = listOf(
            "/task auth" to BranchCommand.SetTask("auth"),
            "/task note \"did x\"" to BranchCommand.AppendTaskNote("did x"),
            "/task pause" to BranchCommand.PauseTask,
            "/task resume" to BranchCommand.ResumeTask,
            "/task clear auth" to BranchCommand.DeleteTask("auth"),
            "/task clear" to BranchCommand.ClearTasks,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
