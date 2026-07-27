package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.features.memory.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `[fsm] no move:` line sent when the model names the stage it is already in — the
 * FSM's main lock, and the one the engine used to swallow in silence. It has to do what
 * the delayed stall nudge cannot: quote the marker back, say why it moved nothing, and
 * name where the stage can actually go, on the very next turn.
 */
class StageRepeatMessageTest {

    @Test
    fun `when the stage was re-signalled - then the note quotes the marker back`() {
        // given - when
        val note = stageRepeatMessage(repeatOf(TaskStage.VALIDATION)).text

        // then — the model is shown its own marker, not told about markers in the abstract
        assertTrue("[[stage:validation]]" in note, note)
    }

    @Test
    fun `when the stage was re-signalled - then every exit of that stage is named`() {
        NON_TERMINAL.forEach { stage ->
            // given
            val exits = TaskStateMachine.allowedNext(stage)

            // when
            val note = stageRepeatMessage(repeatOf(stage)).text

            // then — a model that dislikes its own output needs the back edge too, not just forward
            exits.forEach { exit -> assertTrue(exit.keyword in note, "$stage → $exit: $note") }
        }
    }

    @Test
    fun `when the stage was re-signalled - then the note is a system line tagged no-move`() {
        NON_TERMINAL.forEach { stage ->
            // given - when
            val note = stageRepeatMessage(repeatOf(stage))

            // then — its own tag, so it reads as a different instruction from the stall nudge
            assertEquals(Role.SYSTEM, note.role, "$stage")
            assertTrue(note.text.startsWith(FSM_NO_MOVE), "$stage: ${note.text}")
        }
    }

    private companion object {
        /** DONE is terminal — the engine never reports a re-signal out of it. */
        val NON_TERMINAL = TaskStage.entries.filter { it != TaskStage.DONE }

        fun repeatOf(stage: TaskStage) = StageAdvance.Repeated(stage, TaskStateMachine.allowedNext(stage))
    }
}
