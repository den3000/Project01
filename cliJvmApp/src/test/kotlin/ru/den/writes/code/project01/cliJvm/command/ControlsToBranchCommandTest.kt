package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ControlsToBranchCommand] turns a typed REPL line into the in-session
 * [BranchCommand] (or null → a normal prompt), parsing against the shared catalog
 * on the command front. Each case pins the exact mapped command.
 */
class ControlsToBranchCommandTest {

    private val mapper = ControlsToBranchCommand()

    private fun cmd(line: String): BranchCommand? = mapper.parse(line)

    @Test
    fun `when a branch command is typed - then it maps to the branch action`() {
        // when - then — bare lists, a name forks, switch moves, check is the old checkpoint
        assertEquals(BranchCommand.ListBranches, cmd("/branch"))
        assertEquals(BranchCommand.Branch("exp"), cmd("/branch exp"))
        assertEquals(BranchCommand.Switch("exp"), cmd("/branch switch exp"))
        assertEquals(BranchCommand.Checkpoint, cmd("/branch check"))
    }

    @Test
    fun `when a memory command is typed - then it shows the layer or flips the mode`() {
        // when - then
        assertEquals(BranchCommand.ShowMemory, cmd("/memory"))
        assertEquals(BranchCommand.SetMemoryMode(MemoryMode.SYSTEM), cmd("/memory-mode system"))
        assertEquals(BranchCommand.SetMemoryMode(MemoryMode.PREAMBLE), cmd("/memory-mode preamble"))
    }

    @Test
    fun `when an unnamed profile is edited - then section ops map without a name`() {
        // when - then — bare lists, section+text appends, section alone clears, clean drops all
        assertEquals(BranchCommand.ListProfiles, cmd("/profile"))
        assertEquals(BranchCommand.AddProfileItem(ProfileSection.STYLE, "be terse"), cmd("/profile style \"be terse\""))
        assertEquals(BranchCommand.ClearProfileSection(ProfileSection.STYLE), cmd("/profile style"))
        assertEquals(BranchCommand.ClearProfile, cmd("/profile clean"))
    }

    @Test
    fun `when a named profile is edited - then the name carries through`() {
        // when - then — a bare name activates it (select = use)
        assertEquals(BranchCommand.SwitchProfile("work"), cmd("/profile work"))
        assertEquals(BranchCommand.AddNamedProfileItem("work", ProfileSection.FORMAT, "bullets"), cmd("/profile work format bullets"))
        assertEquals(BranchCommand.ClearNamedProfileSection("work", ProfileSection.FORMAT), cmd("/profile work format"))
        assertEquals(BranchCommand.ClearNamedProfile("work"), cmd("/profile work clean"))
    }

    @Test
    fun `when a profile is shown - then a name shows one and bare lists`() {
        // when - then
        assertEquals(BranchCommand.ShowProfile("work"), cmd("/profile show work"))
        assertEquals(BranchCommand.ListProfiles, cmd("/profile show"))
    }

    @Test
    fun `when a rule is added or removed - then it maps to the rule action`() {
        // when - then
        assertEquals(BranchCommand.AddRule("always kotlin"), cmd("/rule \"always kotlin\""))
        assertEquals(BranchCommand.RemoveRule("003"), cmd("/rule rm 003"))
    }

    @Test
    fun `when a task command is typed - then subs act on the active task`() {
        // when - then — a bare value selects/creates; pause/resume/note carry no id
        assertEquals(BranchCommand.SetTask("auth"), cmd("/task auth"))
        assertEquals(BranchCommand.AppendTaskNote("did x"), cmd("/task note \"did x\""))
        assertEquals(BranchCommand.PauseTask, cmd("/task pause"))
        assertEquals(BranchCommand.ResumeTask, cmd("/task resume"))
    }

    @Test
    fun `when the line is not an in-session command - then it maps to null (a normal prompt)`() {
        // when - then — plain text, unknown control, bad value, and a valid-but-not-in-session control
        assertNull(cmd("hello there"))
        assertNull(cmd("/nope"))
        assertNull(cmd("/memory-mode loud"))
        assertNull(cmd("/session"))
    }
}
