package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** branch entity: command-only — no startup `-branch`; in-session `/branch` → BranchCommand. */
class ControlsToCommandBranchTest {

    //region flags
    @Test
    fun `when branch is used as a startup flag - then rejected (command-only)`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-prompt hi -branch exp", // branch is CMD-only — wrong surface as a flag
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { parser.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a branch command is used - then it behaves accordingly`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases: List<Pair<String, BranchCommand?>> = listOf(
            "/branch" to BranchCommand.ListBranches,
            "/branch exp" to BranchCommand.Branch("exp"),
            "/branch switch exp" to BranchCommand.Switch("exp"),
            "/branch show" to BranchCommand.Checkpoint,
            "/branch clear exp" to BranchCommand.DeleteBranch("exp"),
            "/branch clear" to BranchCommand.ClearBranches,
            "/branch exp clear" to null, // wrong order → not a command
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
