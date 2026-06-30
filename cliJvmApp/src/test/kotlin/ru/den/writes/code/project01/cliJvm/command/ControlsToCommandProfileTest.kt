package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** profile entity: startup `-profile` → MemoryOp, in-session `/profile` → BranchCommand. */
class ControlsToCommandProfileTest {

    private val sections = listOf(
        "style" to ProfileSection.STYLE,
        "format" to ProfileSection.FORMAT,
        "constraints" to ProfileSection.CONSTRAINTS,
        "context" to ProfileSection.CONTEXT,
    )

    //region flags
    @Test
    fun `when profile entity flags are used - then they map to the matching MemoryOp`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-profile" to MemoryAction.ListProfiles,
            "-profile kotlin-senior" to MemoryAction.TouchProfile("kotlin-senior"),
            "-profile show kotlin-senior" to MemoryAction.ShowProfile("kotlin-senior"),
            "-profile clear work" to MemoryAction.ClearNamedProfile("work"),
            "-profile clear" to MemoryAction.ClearAllProfiles,
        )

        // when - then
        cases.forEach { (input, action) ->
            assertEquals(CliCommand.MemoryOp(action), parser.parse(input.toArgsArray()), input)
        }
    }

    @Test
    fun `when profile section flags are used - then every section maps on named and unnamed`() {
        // given
        val parser = createCommandsParser()

        // when - then — text present = append, absent = clear; with a name = named, without = unnamed
        sections.forEach { (kw, sec) ->
            val cases = listOf(
                "-profile work $kw \"some text\"" to MemoryAction.AddNamedProfileItem("work", sec, "some text"),
                "-profile work $kw" to MemoryAction.ClearNamedProfileSection("work", sec),
                "-profile $kw \"some text\"" to MemoryAction.AddProfileItem(sec, "some text"),
                "-profile $kw" to MemoryAction.ClearProfileSection(sec),
            )
            cases.forEach { (input, action) ->
                assertEquals(CliCommand.MemoryOp(action), parser.parse(input.toArgsArray()), input)
            }
        }
    }

    @Test
    fun `when profile flags are invalid - then rejected`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-profile kotlin-senior show", // wrong order — verb-then-name only
            "-profile show",               // show-all has no startup target
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { parser.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a profile entity command is used - then it behaves accordingly`() {
        // given
        val mapper = ControlsToBranchCommand()
        val cases: List<Pair<String, BranchCommand?>> = listOf(
            "/profile" to BranchCommand.ListProfiles,
            "/profile work" to BranchCommand.SwitchProfile("work"),
            "/profile show work" to BranchCommand.ShowProfile("work"),
            "/profile show" to BranchCommand.ListProfiles,
            "/profile clear" to BranchCommand.ClearAllProfiles,
            "/profile clear work" to BranchCommand.ClearNamedProfile("work"),
            "/profile work show" to null, // wrong order → not a command
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a profile section command is used - then every section maps on named and unnamed`() {
        // given
        val mapper = ControlsToBranchCommand()

        // when - then
        sections.forEach { (kw, sec) ->
            val cases = listOf(
                "/profile work $kw \"some text\"" to BranchCommand.AddNamedProfileItem("work", sec, "some text"),
                "/profile work $kw" to BranchCommand.ClearNamedProfileSection("work", sec),
                "/profile $kw \"some text\"" to BranchCommand.AddProfileItem(sec, "some text"),
                "/profile $kw" to BranchCommand.ClearProfileSection(sec),
            )
            cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
        }
    }
    //endregion
}
