package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.cliJvm.SessionCommand
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Direct coverage for [CliArgToSessionCommandMapper], the REPL `/`-command classifier shared
 * by the stdin REPL and the TUI intent source. It delegates to the cliargs
 * catalog on the command front, so these cases pin the catalog grammar's
 * `/`-spellings (multi-word values are quoted) and the fall-through-to-prompt
 * behaviour for non-commands.
 */
class SlashCommandParserTest {

    //region branch family

    @Test
    fun `when branch bare - then ListBranches`() {
        // when - then
        assertEquals(SessionCommand.ListBranches, testSessionMapper.parse("/branch"))
    }

    @Test
    fun `when branch with a name - then Branch forks`() {
        // when - then
        assertEquals(SessionCommand.Branch("exp"), testSessionMapper.parse("/branch exp"))
    }

    @Test
    fun `when branch switch with a name - then Switch`() {
        // when - then
        assertEquals(SessionCommand.Switch("exp"), testSessionMapper.parse("/branch switch exp"))
    }

    @Test
    fun `when branch show - then Checkpoint`() {
        // when - then — `branch show` = current branch + message count
        assertEquals(SessionCommand.Checkpoint, testSessionMapper.parse("/branch show"))
    }
    //endregion

    //region memory + profile family

    @Test
    fun `when memory - then ShowMemory`() {
        // when - then
        assertEquals(SessionCommand.ShowMemory, testSessionMapper.parse("/memory"))
    }

    @Test
    fun `when a profile section carries quoted text - then AddProfileItem`() {
        // when - then — multi-word values are quoted under the catalog grammar
        assertEquals(
            SessionCommand.AddProfileItem(ProfileSection.STYLE, "be terse"),
            testSessionMapper.parse("/profile style \"be terse\""),
        )
    }

    @Test
    fun `when a profile section omits text - then ClearProfileSection`() {
        // when - then
        assertEquals(
            SessionCommand.ClearProfileSection(ProfileSection.STYLE),
            testSessionMapper.parse("/profile style"),
        )
    }

    @Test
    fun `when profile clear bare - then ClearAllProfiles`() {
        // when - then — bare clear nukes every profile (named + unnamed)
        assertEquals(SessionCommand.ClearAllProfiles, testSessionMapper.parse("/profile clear"))
    }

    @Test
    fun `when profile with a bare name - then SwitchProfile activates it`() {
        // when - then
        assertEquals(SessionCommand.SwitchProfile("work"), testSessionMapper.parse("/profile work"))
    }

    @Test
    fun `when profile show with a name - then ShowProfile (verb-then-name)`() {
        // when - then — name follows the verb; `/profile work show` (wrong order) is not a command
        assertEquals(SessionCommand.ShowProfile("work"), testSessionMapper.parse("/profile show work"))
        assertNull(testSessionMapper.parse("/profile work show"))
    }

    @Test
    fun `when profile bare - then ListProfiles`() {
        // when - then
        assertEquals(SessionCommand.ListProfiles, testSessionMapper.parse("/profile"))
    }
    //endregion

    //region rule + task + agent mode

    @Test
    fun `when rule with quoted text - then AddRule`() {
        // when - then
        assertEquals(SessionCommand.AddRule("no emojis"), testSessionMapper.parse("/rule \"no emojis\""))
    }

    @Test
    fun `when rule clear with an id - then RemoveRule`() {
        // when - then
        assertEquals(SessionCommand.RemoveRule("003"), testSessionMapper.parse("/rule clear 003"))
    }

    @Test
    fun `when task with an id - then SetTask`() {
        // when - then
        assertEquals(SessionCommand.SetTask("fix-bug"), testSessionMapper.parse("/task fix-bug"))
    }

    @Test
    fun `when task pause - then PauseTask`() {
        // when - then
        assertEquals(SessionCommand.PauseTask, testSessionMapper.parse("/task pause"))
    }

    @Test
    fun `when agent mode system - then SetMemoryMode SYSTEM`() {
        // when - then
        assertEquals(SessionCommand.SetMemoryMode(MemoryMode.SYSTEM), testSessionMapper.parse("/agent mode system"))
    }

    @Test
    fun `when agent mode has a bad argument - then null (falls through to a prompt)`() {
        // when - then
        assertNull(testSessionMapper.parse("/agent mode garbage"))
    }
    //endregion

    //region non-commands

    @Test
    fun `when an unknown slash word - then null`() {
        // when - then
        assertNull(testSessionMapper.parse("/nope do things"))
    }

    @Test
    fun `when ordinary prose - then null (it is a normal prompt)`() {
        // when - then
        assertNull(testSessionMapper.parse("hello there, how are you"))
    }
    //endregion
}
