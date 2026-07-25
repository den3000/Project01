package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `[fsm]` nudge sent once the model has sat in one stage for several turns. Its
 * wording is the whole mechanism — the engine only decides when to send it — so what
 * it names is pinned here: every stage is told the marker that leaves it, and
 * VALIDATION is told both of its exits. A live run locked there for seven turns,
 * rewording the deliverable and re-signalling validation, with only "done" offered.
 */
class StallHintMessageTest {

    @Test
    fun `when stalled in validation - then both exits are offered`() {
        // given - when
        val hint = stallHintMessage(TaskStage.VALIDATION).text

        // then — a deliverable that passes goes forward, one that does not goes back;
        // naming only the forward exit leaves a model unhappy with its own output no
        // move it is willing to make, so it stays put and rewords instead
        assertTrue(hint.contains("[[stage:done]]"), hint)
        assertTrue(hint.contains("[[stage:execution]]"), hint)
    }

    @Test
    fun `when stalled in validation - then the current stage marker is named as a no-op`() {
        // given - when
        val hint = stallHintMessage(TaskStage.VALIDATION).text

        // then — the observed lock is the model re-emitting exactly this marker
        assertTrue(hint.contains("[[stage:validation]]"), hint)
    }

    @Test
    fun `when stalled in a non-terminal stage - then the nudge names the stage that leaves it`() {
        NON_TERMINAL.forEach { stage ->
            // given — the forward exit is the next stage in order
            val forward = TaskStage.entries[stage.ordinal + 1]

            // when
            val hint = stallHintMessage(stage).text

            // then
            assertTrue(hint.contains("[[stage:${forward.keyword}]]"), "$stage: $hint")
        }
    }

    @Test
    fun `when stalled in a non-terminal stage - then the nudge is a system line tagged fsm`() {
        NON_TERMINAL.forEach { stage ->
            // given - when
            val hint = stallHintMessage(stage)

            // then
            assertEquals(Role.SYSTEM, hint.role, "$stage")
            assertTrue(hint.text.startsWith(FSM_STALLED), "$stage: ${hint.text}")
        }
    }

    private companion object {
        /** DONE is terminal — [TurnEngine] never nudges out of it. */
        val NON_TERMINAL = TaskStage.entries.filter { it != TaskStage.DONE }
    }
}
