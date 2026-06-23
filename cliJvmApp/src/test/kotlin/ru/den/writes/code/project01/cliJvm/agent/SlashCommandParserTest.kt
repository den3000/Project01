package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.parseSlashCommand
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Direct coverage for [parseSlashCommand], the REPL `/`-command classifier
 * lifted out of `StdinPromptSource` so the stdin REPL and the TUI intent
 * source share one parser. Previously this logic was only exercised through
 * full REPL runs.
 */
class SlashCommandParserTest {

    //region branch family

    @Test
    fun `when checkpoint - then Checkpoint`() {
        // when - then
        assertEquals(BranchCommand.Checkpoint, parseSlashCommand("/checkpoint"))
    }

    @Test
    fun `when branches - then ListBranches`() {
        // when - then
        assertEquals(BranchCommand.ListBranches, parseSlashCommand("/branches"))
    }

    @Test
    fun `when branch with a name - then Branch carries the name`() {
        // when - then
        assertEquals(BranchCommand.Branch("exp"), parseSlashCommand("/branch exp"))
    }

    @Test
    fun `when switch with a name - then Switch carries the name`() {
        // when - then
        assertEquals(BranchCommand.Switch("exp"), parseSlashCommand("/switch exp"))
    }
    //endregion

    //region memory + profile family

    @Test
    fun `when memory - then ShowMemory`() {
        // when - then
        assertEquals(BranchCommand.ShowMemory, parseSlashCommand("/memory"))
    }

    @Test
    fun `when profile section with text - then AddProfileItem for that section`() {
        // when - then
        assertEquals(
            BranchCommand.AddProfileItem(ProfileSection.STYLE, "be terse"),
            parseSlashCommand("/profile style be terse"),
        )
    }

    @Test
    fun `when profile section clear - then ClearProfileSection`() {
        // when - then
        assertEquals(
            BranchCommand.ClearProfileSection(ProfileSection.STYLE),
            parseSlashCommand("/profile style clear"),
        )
    }

    @Test
    fun `when profile clear - then ClearProfile`() {
        // when - then
        assertEquals(BranchCommand.ClearProfile, parseSlashCommand("/profile clear"))
    }

    @Test
    fun `when profile with a bare name - then TouchProfile`() {
        // when - then
        assertEquals(BranchCommand.TouchProfile("work"), parseSlashCommand("/profile work"))
    }

    @Test
    fun `when profile-use with a name - then SwitchProfile`() {
        // when - then
        assertEquals(BranchCommand.SwitchProfile("work"), parseSlashCommand("/profile-use work"))
    }

    @Test
    fun `when profiles - then ListProfiles`() {
        // when - then
        assertEquals(BranchCommand.ListProfiles, parseSlashCommand("/profiles"))
    }
    //endregion

    //region task + memory-mode

    @Test
    fun `when rule with text - then AddRule`() {
        // when - then
        assertEquals(BranchCommand.AddRule("no emojis"), parseSlashCommand("/rule no emojis"))
    }

    @Test
    fun `when task with an id - then SetTask`() {
        // when - then
        assertEquals(BranchCommand.SetTask("fix-bug"), parseSlashCommand("/task fix-bug"))
    }

    @Test
    fun `when task-pause - then PauseTask`() {
        // when - then
        assertEquals(BranchCommand.PauseTask, parseSlashCommand("/task-pause"))
    }

    @Test
    fun `when memory-mode system - then SetMemoryMode SYSTEM`() {
        // when - then
        assertEquals(BranchCommand.SetMemoryMode(MemoryMode.SYSTEM), parseSlashCommand("/memory-mode system"))
    }

    @Test
    fun `when memory-mode has a bad argument - then null (falls through to a prompt)`() {
        // when - then
        assertNull(parseSlashCommand("/memory-mode garbage"))
    }
    //endregion

    //region non-commands

    @Test
    fun `when an unknown slash word - then null`() {
        // when - then
        assertNull(parseSlashCommand("/nope do things"))
    }

    @Test
    fun `when ordinary prose - then null (it is a normal prompt)`() {
        // when - then
        assertNull(parseSlashCommand("hello there, how are you"))
    }
    //endregion
}
