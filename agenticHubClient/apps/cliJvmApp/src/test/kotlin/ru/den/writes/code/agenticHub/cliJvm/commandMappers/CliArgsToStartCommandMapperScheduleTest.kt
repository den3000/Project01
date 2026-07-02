package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.features.lifecycle.session.SessionCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs

/**
 * schedule entity: startup `-schedule` → RunChat.schedules (list of ScheduleSpec),
 * in-session `/schedule` → SessionCommand. `after` = one-shot, `every` = periodic;
 * collect calls an MCP tool (needs -mcpServer), agent runs a prompt.
 */
class CliArgsToStartCommandMapperScheduleTest {

    //region flags
    @Test
    fun `when schedule flags are used - then they map to RunChat schedules`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val collect = assertIs<ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand.RunChat>(
            mapper.parseOk("-prompt hi -mcpServer \"lab\" -schedule collect tool weather args \"{}\" after 30".toArgsArray()),
        )
        val agent = assertIs<ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand.RunChat>(
            mapper.parseOk("-prompt hi -schedule agent prompt \"do x\" every 60".toArgsArray()),
        )
        val multiple = assertIs<ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand.RunChat>(
            mapper.parseOk("-prompt hi -schedule agent prompt \"a\" after 10 -schedule agent prompt \"b\" every 20".toArgsArray()),
        )

        // then — after = one-shot (periodic false), every = periodic true; repeated -schedule accumulates
        assertEquals(listOf(ru.den.writes.code.agenticHub.features.lifecycle.command.ScheduleSpec.Collect("weather", "{}", 30, false)), collect.config.schedules)
        assertEquals(listOf(ru.den.writes.code.agenticHub.features.lifecycle.command.ScheduleSpec.Agent("do x", 60, true)), agent.config.schedules)
        assertEquals(listOf(ru.den.writes.code.agenticHub.features.lifecycle.command.ScheduleSpec.Agent("a", 10, false), ru.den.writes.code.agenticHub.features.lifecycle.command.ScheduleSpec.Agent("b", 20, true)), multiple.config.schedules)
    }

    @Test
    fun `when schedule flags are invalid - then rejected`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-prompt hi -mcpServer \"lab\" -schedule collect tool X after 10 every 20", // after + every
            "-prompt hi -schedule collect after 30",   // collect needs a tool
            "-prompt hi -schedule agent after 30",      // agent needs a prompt
            "-prompt hi -schedule agent prompt \"x\"",  // needs after or every
        )

        // when - then
        cases.forEach { input ->
            assertNotNull(mapper.parseErr(input.toArgsArray()), input)
        }
    }
    //endregion

    //region commands
    @Test
    fun `when a schedule command is used - then it behaves accordingly`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
        val cases = listOf(
            "/schedule" to SessionCommand.ListSchedules,
            "/schedule clear" to SessionCommand.ClearSchedules,
            "/schedule clear 001" to SessionCommand.CancelSchedule("001"),
            "/schedule collect tool weather args \"{}\" after 30" to SessionCommand.Schedule(
                ru.den.writes.code.agenticHub.features.lifecycle.command.ScheduleSpec.Collect("weather", "{}", 30, false)),
            "/schedule agent prompt \"do x\" every 60" to SessionCommand.Schedule(ru.den.writes.code.agenticHub.features.lifecycle.command.ScheduleSpec.Agent("do x", 60, true)),
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }

    @Test
    fun `when a schedule command is invalid - then it falls through to a prompt`() {
        // given
        val mapper = createCliArgToSessionCommandMapper()
        val cases: List<Pair<String, SessionCommand?>> = listOf(
            "/schedule collect after 30" to null,                  // no tool
            "/schedule agent prompt \"x\"" to null,                // no timing
            "/schedule collect tool X after 10 every 20" to null,  // both after and every
        )

        // when - then
        cases.forEach { (input, expected) -> assertEquals(expected, mapper.parse(input), input) }
    }
    //endregion
}
