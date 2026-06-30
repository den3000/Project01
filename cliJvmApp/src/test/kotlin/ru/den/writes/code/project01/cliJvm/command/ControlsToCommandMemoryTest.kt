package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals

/** ControlsToCommand: profile / rule / task entity ops (no prompt) → MemoryOp. */
class ControlsToCommandMemoryTest {

    @Test
    fun `when a profile, rule or task entity op is used - then MemoryOp with the matching action`() {
        // given
        val parser = createCommandsParser()
        val cases = listOf(
            "-profile" to MemoryAction.ListProfiles,
            "-profile kotlin-senior" to MemoryAction.TouchProfile("kotlin-senior"),
            "-profile work style terse" to MemoryAction.AddNamedProfileItem("work", ProfileSection.STYLE, "terse"),
            "-profile work style" to MemoryAction.ClearNamedProfileSection("work", ProfileSection.STYLE),
            "-profile style x" to MemoryAction.AddProfileItem(ProfileSection.STYLE, "x"),
            "-profile clear work" to MemoryAction.ClearNamedProfile("work"),
            "-profile clear" to MemoryAction.ClearAllProfiles,
            "-profile show kotlin-senior" to MemoryAction.ShowProfile("kotlin-senior"),
            "-rule \"always kotlin\"" to MemoryAction.AddRule("always kotlin"),
            "-rule clear 003" to MemoryAction.RemoveRule("003"),
            "-rule clear" to MemoryAction.ClearRules,
            "-task auth" to MemoryAction.SetTask("auth"),
            "-task auth pause" to MemoryAction.PauseTask("auth"),
            "-task auth resume" to MemoryAction.ResumeTask("auth"),
            "-task clear auth" to MemoryAction.DeleteTask("auth"),
            "-task clear" to MemoryAction.ClearTasks,
        )

        // when - then — one invariant (entity op → MemoryOp) over an extending list (rule §11.E)
        cases.forEach { (input, action) ->
            assertEquals(CliCommand.MemoryOp(action), parser.parse(input.toArgsArray()), input)
        }
    }
}
