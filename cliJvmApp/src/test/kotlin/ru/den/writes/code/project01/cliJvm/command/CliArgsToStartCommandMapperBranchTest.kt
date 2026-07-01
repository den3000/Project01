package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.SessionCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** branch entity: command-only — no startup `-branch`; in-session `/branch` → SessionCommand. */
class CliArgsToStartCommandMapperBranchTest {

    //region flags
    @Test
    fun `when branch is used as a startup flag - then rejected (command-only)`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-prompt hi -branch exp", // branch is CMD-only — wrong surface as a flag
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { mapper.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a branch command is used - then it behaves accordingly`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
        val cases: List<Pair<String, SessionCommand?>> = listOf(
            "/branch" to SessionCommand.ListBranches,
            "/branch exp" to SessionCommand.Branch("exp"),
            "/branch switch exp" to SessionCommand.Switch("exp"),
            "/branch show" to SessionCommand.Checkpoint,
            "/branch clear exp" to SessionCommand.DeleteBranch("exp"),
            "/branch clear" to SessionCommand.ClearBranches,
            "/branch exp clear" to null, // wrong order → not a command
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
