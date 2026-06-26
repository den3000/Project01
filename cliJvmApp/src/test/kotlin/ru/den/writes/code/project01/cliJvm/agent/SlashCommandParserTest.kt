package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.parseSlashCommand
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Direct coverage for [parseSlashCommand], the REPL `/`-command classifier shared
 * by the stdin REPL and the TUI intent source. It delegates to the clicontrols
 * catalog on the command front, so these cases pin the catalog grammar's
 * `/`-spellings (multi-word values are quoted) and the fall-through-to-prompt
 * behaviour for non-commands.
 */
class SlashCommandParserTest {

    //region branch family

    @Test
    fun `when branch bare - then ListBranches`() {
        // when - then
        assertEquals(BranchCommand.ListBranches, parseSlashCommand("/branch"))
    }

    @Test
    fun `when branch with a name - then Branch forks`() {
        // when - then
        assertEquals(BranchCommand.Branch("exp"), parseSlashCommand("/branch exp"))
    }

    @Test
    fun `when branch switch with a name - then Switch`() {
        // when - then
        assertEquals(BranchCommand.Switch("exp"), parseSlashCommand("/branch switch exp"))
    }

    @Test
    fun `when branch show - then Checkpoint`() {
        // when - then — `branch show` = current branch + message count
        assertEquals(BranchCommand.Checkpoint, parseSlashCommand("/branch show"))
    }
    //endregion

    //region memory + profile family

    @Test
    fun `when memory - then ShowMemory`() {
        // when - then
        assertEquals(BranchCommand.ShowMemory, parseSlashCommand("/memory"))
    }

    @Test
    fun `when a profile section carries quoted text - then AddProfileItem`() {
        // when - then — multi-word values are quoted under the catalog grammar
        assertEquals(
            BranchCommand.AddProfileItem(ProfileSection.STYLE, "be terse"),
            parseSlashCommand("/profile style \"be terse\""),
        )
    }

    @Test
    fun `when a profile section omits text - then ClearProfileSection`() {
        // when - then
        assertEquals(
            BranchCommand.ClearProfileSection(ProfileSection.STYLE),
            parseSlashCommand("/profile style"),
        )
    }

    @Test
    fun `when profile clear bare - then ClearAllProfiles`() {
        // when - then — bare clear nukes every profile (named + unnamed)
        assertEquals(BranchCommand.ClearAllProfiles, parseSlashCommand("/profile clear"))
    }

    @Test
    fun `when profile with a bare name - then SwitchProfile activates it`() {
        // when - then
        assertEquals(BranchCommand.SwitchProfile("work"), parseSlashCommand("/profile work"))
    }

    @Test
    fun `when profile show with a name - then ShowProfile (verb-then-name)`() {
        // when - then — name follows the verb; `/profile work show` (wrong order) is not a command
        assertEquals(BranchCommand.ShowProfile("work"), parseSlashCommand("/profile show work"))
        assertNull(parseSlashCommand("/profile work show"))
    }

    @Test
    fun `when profile bare - then ListProfiles`() {
        // when - then
        assertEquals(BranchCommand.ListProfiles, parseSlashCommand("/profile"))
    }
    //endregion

    //region rule + task + agent mode

    @Test
    fun `when rule with quoted text - then AddRule`() {
        // when - then
        assertEquals(BranchCommand.AddRule("no emojis"), parseSlashCommand("/rule \"no emojis\""))
    }

    @Test
    fun `when rule clear with an id - then RemoveRule`() {
        // when - then
        assertEquals(BranchCommand.RemoveRule("003"), parseSlashCommand("/rule clear 003"))
    }

    @Test
    fun `when task with an id - then SetTask`() {
        // when - then
        assertEquals(BranchCommand.SetTask("fix-bug"), parseSlashCommand("/task fix-bug"))
    }

    @Test
    fun `when task pause - then PauseTask`() {
        // when - then
        assertEquals(BranchCommand.PauseTask, parseSlashCommand("/task pause"))
    }

    @Test
    fun `when agent mode system - then SetMemoryMode SYSTEM`() {
        // when - then
        assertEquals(BranchCommand.SetMemoryMode(MemoryMode.SYSTEM), parseSlashCommand("/agent mode system"))
    }

    @Test
    fun `when agent mode has a bad argument - then null (falls through to a prompt)`() {
        // when - then
        assertNull(parseSlashCommand("/agent mode garbage"))
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
