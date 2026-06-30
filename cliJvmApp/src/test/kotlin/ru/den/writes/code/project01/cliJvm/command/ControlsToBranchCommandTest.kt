package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ControlsToBranchCommand] turns a typed REPL line into the in-session
 * [BranchCommand] (or null → a normal prompt), parsing against the shared catalog
 * on the command front. Each case pins the exact mapped command.
 */
class ControlsToBranchCommandTest {

    @Test
    fun `when a branch command is typed - then it maps to the branch action`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases = listOf(
            "/branch" to BranchCommand.ListBranches,
            "/branch exp" to BranchCommand.Branch("exp"),
            "/branch switch exp" to BranchCommand.Switch("exp"),
            "/branch show" to BranchCommand.Checkpoint,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a branch is cleared - then verb-then-name deletes one, bare clears all, wrong order rejected`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases: List<Pair<String, BranchCommand?>> = listOf(
            "/branch clear exp" to BranchCommand.DeleteBranch("exp"),
            "/branch clear" to BranchCommand.ClearBranches,
            "/branch exp clear" to null,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a memory command is typed - then it shows the layer or flips the mode`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases = listOf(
            "/memory" to BranchCommand.ShowMemory,
            "/agent mode system" to BranchCommand.SetMemoryMode(MemoryMode.SYSTEM),
            "/agent mode preamble" to BranchCommand.SetMemoryMode(MemoryMode.PREAMBLE),
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when an unnamed profile is edited - then section ops map without a name`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases = listOf(
            "/profile" to BranchCommand.ListProfiles,
            "/profile style \"be terse\"" to BranchCommand.AddProfileItem(ProfileSection.STYLE, "be terse"),
            "/profile style" to BranchCommand.ClearProfileSection(ProfileSection.STYLE),
            "/profile clear" to BranchCommand.ClearAllProfiles,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a named profile is edited - then the name carries through`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases = listOf(
            "/profile work" to BranchCommand.SwitchProfile("work"),
            "/profile work format bullets" to BranchCommand.AddNamedProfileItem("work", ProfileSection.FORMAT, "bullets"),
            "/profile work format" to BranchCommand.ClearNamedProfileSection("work", ProfileSection.FORMAT),
            "/profile clear work" to BranchCommand.ClearNamedProfile("work"),
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a profile is shown - then verb-then-name shows one, bare lists, wrong order rejected`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases: List<Pair<String, BranchCommand?>> = listOf(
            "/profile show work" to BranchCommand.ShowProfile("work"),
            "/profile show" to BranchCommand.ListProfiles,
            "/profile work show" to null,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a rule is added or cleared - then it maps to the rule action`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases = listOf(
            "/rule \"always kotlin\"" to BranchCommand.AddRule("always kotlin"),
            "/rule clear 003" to BranchCommand.RemoveRule("003"),
            "/rule clear" to BranchCommand.ClearRules,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a task command is typed - then subs act on the active task or clear by id`() {
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

    @Test
    fun `when the line is not an in-session command - then it maps to null (a normal prompt)`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases: List<Pair<String, BranchCommand?>> = listOf(
            "hello there" to null,
            "/nope" to null,
            "/agent mode loud" to null,
            "/session" to null,
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
}
