package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** profile entity: startup `-profile` → MemoryOp, in-session `/profile` → BranchCommand. */
class ControlsToCommandProfileTest {

    //region flags
    @Test
    fun `when profile flags are used - then they map to the matching MemoryOp`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-profile" to MemoryAction.ListProfiles,
            "-profile kotlin-senior" to MemoryAction.TouchProfile("kotlin-senior"),
            "-profile work style terse" to MemoryAction.AddNamedProfileItem("work", ProfileSection.STYLE, "terse"),
            "-profile work style" to MemoryAction.ClearNamedProfileSection("work", ProfileSection.STYLE),
            "-profile style x" to MemoryAction.AddProfileItem(ProfileSection.STYLE, "x"),
            "-profile clear work" to MemoryAction.ClearNamedProfile("work"),
            "-profile clear" to MemoryAction.ClearAllProfiles,
            "-profile show kotlin-senior" to MemoryAction.ShowProfile("kotlin-senior"),
        )

        // when - then
        cases.forEach { (input, action) ->
            assertEquals(CliCommand.MemoryOp(action), parser.parse(input.toArgsArray()), input)
        }
    }

    @Test
    fun `when profile flags are invalid - then rejected`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-profile kotlin-senior show", // verb-then-name only; wrong order is not expressible
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { parser.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a profile command is used - then it behaves accordingly`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases: List<Pair<String, BranchCommand?>> = listOf(
            "/profile" to BranchCommand.ListProfiles,
            "/profile work" to BranchCommand.SwitchProfile("work"),
            "/profile style \"be terse\"" to BranchCommand.AddProfileItem(ProfileSection.STYLE, "be terse"),
            "/profile style" to BranchCommand.ClearProfileSection(ProfileSection.STYLE),
            "/profile clear" to BranchCommand.ClearAllProfiles,
            "/profile work format bullets" to BranchCommand.AddNamedProfileItem("work", ProfileSection.FORMAT, "bullets"),
            "/profile work format" to BranchCommand.ClearNamedProfileSection("work", ProfileSection.FORMAT),
            "/profile clear work" to BranchCommand.ClearNamedProfile("work"),
            "/profile show work" to BranchCommand.ShowProfile("work"),
            "/profile show" to BranchCommand.ListProfiles,
            "/profile work show" to null, // wrong order → not a command
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
