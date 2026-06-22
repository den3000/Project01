package ru.den.writes.code.project01.cliJvm.agent

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CommandRunner
import ru.den.writes.code.project01.cliJvm.ContextStrategy
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.IntentSource
import ru.den.writes.code.project01.cliJvm.SessionViewModel
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.TurnEngine
import ru.den.writes.code.project01.cliJvm.UiEffect
import ru.den.writes.code.project01.cliJvm.UiIntent
import ru.den.writes.code.project01.cliJvm.UiLine
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offline tests for [SessionViewModel] — intent → state, no terminal. Drives
 * the loop with scripted [IntentSource]s and asserts on [SessionViewModel.state]
 * lines, [SessionViewModel.lastReply] and the Exit effect.
 */
class SessionViewModelTest {

    @Test
    fun `when an opening turn runs then Exit - then state has the Turn, a summary, and Exit`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi().apply { queueText("reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val vm = newVm(newChat("hi", "s"), fake, store)

            // when
            vm.run(intents(UiIntent.Exit))

            // then
            val lines = vm.state.value.lines
            assertTrue(lines.any { it is UiLine.Assistant && it.reply == "reply" })
            assertFalse(vm.state.value.busy)
            assertEquals("reply", vm.lastReply)
            assertTrue(lines.any { it is UiLine.Notice && it.text.startsWith("[session-summary]") })
            assertEquals(UiEffect.Exit, vm.effects.receive())
        }
    }

    @Test
    fun `when resuming with prior history - then the first line is the resumed notice`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            HistoryStore(dao, sessionId = "s").apply {
                append(Message(Role.USER, "old"))
                append(
                    Message(Role.ASSISTANT, "older"),
                    usage = Usage(promptTokens = 10, outputTokens = 5, thoughtsTokens = 0, totalTokens = 15),
                    modelId = "golden-stub",
                )
            }
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, HistoryStore(dao, sessionId = "s"))

            // when
            vm.run(intents(UiIntent.Exit))

            // then
            val first = vm.state.value.lines.first()
            assertTrue(
                first is UiLine.Notice && first.text.startsWith("[session] resumed: 1 prior turn(s)"),
                "expected the resumed banner first, was $first",
            )
        }
    }

    @Test
    fun `when the opening turn fails - then state gains an Error line`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi() // empty queue → error
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val vm = newVm(newChat("hi", "s"), fake, store)

            // when
            vm.run(intents(UiIntent.Exit))

            // then
            assertTrue(
                vm.state.value.lines.any {
                    it is UiLine.Error && it.reason == "FakeLlmApi: no scripted response"
                },
            )
        }
    }

    @Test
    fun `when a slash command runs - then its notice is appended`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi().apply { queueText("reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val vm = newVm(newChat("hi", "s"), fake, store)

            // when
            vm.run(intents(UiIntent.SlashCommand(BranchCommand.Checkpoint), UiIntent.Exit))

            // then
            assertTrue(
                vm.state.value.lines.any { it is UiLine.Notice && it.text.startsWith("[checkpoint] branch 'main'") },
            )
        }
    }

    @Test
    fun `when a feed hands off to a REPL - then interim, continuing and final notices are ordered`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi().apply { queueText("reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val vm = newVm(newChat("hi", "s"), fake, store)

            // when — both sources stop immediately
            vm.run(primary = intents(), followUp = intents())

            // then
            val notices = vm.state.value.lines.filterIsInstance<UiLine.Notice>().map { it.text }
            val interim = notices.indexOfFirst { it.startsWith("[feed done — interim summary]") }
            val continuing = notices.indexOfFirst { it.startsWith("[continuing in REPL") }
            val finalIdx = notices.indexOfFirst { it.startsWith("[session-summary]") }
            assertTrue(interim >= 0 && continuing > interim && finalIdx > continuing, "notices: $notices")
        }
    }

    @Test
    fun `when OneShot - then the opening turn runs with no summary, then Exit`() = runTest {
        // given — OneShot has no history
        val fake = FakeLlmApi().apply { queueText("reply") }
        val vm = newVm(oneShot("hi"), fake, store = null)

        // when
        vm.run(intents())

        // then
        val lines = vm.state.value.lines
        assertTrue(lines.any { it is UiLine.Turn })
        assertFalse(lines.any { it is UiLine.Notice && it.text.startsWith("[session-summary]") })
        assertEquals(UiEffect.Exit, vm.effects.receive())
    }

    //region helpers

    private fun newVm(
        chat: CliArgs.PromptCommand,
        api: LlmApi,
        store: HistoryStore?,
        strategy: ContextStrategy = ContextStrategy.FullHistory,
    ): SessionViewModel {
        val engine = TurnEngine(chat, api, store, strategy)
        val runner = CommandRunner(store, memory = null, strategy = strategy)
        return SessionViewModel(chat, engine, runner, store, memory = null, strategy = strategy, multiAgent = false)
    }

    private fun oneShot(prompt: String): CliArgs.OneShot = CliArgs.OneShot(
        prompt = prompt,
        maxTokens = null,
        stopSequences = null,
        endSequence = null,
        temperature = null,
        modelProvider = dummyGeminiProvider(),
    )

    private fun intents(vararg items: UiIntent): IntentSource = object : IntentSource {
        private val queue = ArrayDeque(items.toList())
        override suspend fun next(): UiIntent? = queue.removeFirstOrNull()
    }
    //endregion
}
