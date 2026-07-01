package ru.den.writes.code.project01.cliJvm.commandMappers

import ru.den.writes.code.project01.cliJvm.SessionCommand
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** profile entity: startup `-profile` → MemoryOp, in-session `/profile` → SessionCommand. */
class CliArgsToStartCommandMapperProfileTest {

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
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-profile" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.ListProfiles,
            "-profile kotlin-senior" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.TouchProfile("kotlin-senior"),
            "-profile show kotlin-senior" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.ShowProfile("kotlin-senior"),
            "-profile clear work" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.ClearNamedProfile("work"),
            "-profile clear" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.ClearAllProfiles,
        )

        // when - then
        cases.forEach { (input, action) ->
            kotlin.test.assertEquals(
                ru.den.writes.code.project01.cliJvm.command.StartCommand.MemoryOp(
                    action
                ), mapper.parse(input.toArgsArray()), input
            )
        }
    }

    @Test
    fun `when profile section flags are used - then every section maps on named and unnamed`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when - then — text present = append, absent = clear; with a name = named, without = unnamed
        sections.forEach { (kw, sec) ->
            val cases = listOf(
                "-profile work $kw \"some text\"" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.AddNamedProfileItem("work", sec, "some text"),
                "-profile work $kw" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.ClearNamedProfileSection("work", sec),
                "-profile $kw \"some text\"" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.AddProfileItem(sec, "some text"),
                "-profile $kw" to ru.den.writes.code.project01.cliJvm.command.MemoryAction.ClearProfileSection(sec),
            )
            cases.forEach { (input, action) ->
                kotlin.test.assertEquals(
                    ru.den.writes.code.project01.cliJvm.command.StartCommand.MemoryOp(
                        action
                    ), mapper.parse(input.toArgsArray()), input
                )
            }
        }
    }

    @Test
    fun `when profile flags are invalid - then rejected`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-profile kotlin-senior show", // wrong order — verb-then-name only
            "-profile show",               // show-all has no startup target
        )

        // when - then
        cases.forEach { input ->
            assertFailsWith<CliArgsException.InvalidArgumentValue>(input) { mapper.parse(input.toArgsArray()) }
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a profile entity command is used - then it behaves accordingly`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
        val cases: List<Pair<String, SessionCommand?>> = listOf(
            "/profile" to SessionCommand.ListProfiles,
            "/profile work" to SessionCommand.SwitchProfile("work"),
            "/profile show work" to SessionCommand.ShowProfile("work"),
            "/profile show" to SessionCommand.ListProfiles,
            "/profile clear" to SessionCommand.ClearAllProfiles,
            "/profile clear work" to SessionCommand.ClearNamedProfile("work"),
            "/profile work show" to null, // wrong order → not a command
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a profile section command is used - then every section maps on named and unnamed`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()

        // when - then
        sections.forEach { (kw, sec) ->
            val cases = listOf(
                "/profile work $kw \"some text\"" to SessionCommand.AddNamedProfileItem("work", sec, "some text"),
                "/profile work $kw" to SessionCommand.ClearNamedProfileSection("work", sec),
                "/profile $kw \"some text\"" to SessionCommand.AddProfileItem(sec, "some text"),
                "/profile $kw" to SessionCommand.ClearProfileSection(sec),
            )
            cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
        }
    }
    //endregion
}
