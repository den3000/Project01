package ru.den.writes.code.project01.cliJvm.agent

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CommandRunner
import ru.den.writes.code.project01.cliJvm.ContextStrategy
import ru.den.writes.code.project01.cliJvm.commandCatalog
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.IntentSource
import ru.den.writes.code.project01.cliJvm.Overlay
import ru.den.writes.code.project01.cliJvm.PickerKind
import ru.den.writes.code.project01.cliJvm.SessionViewModel
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.TurnEngine
import ru.den.writes.code.project01.cliJvm.UiEffect
import ru.den.writes.code.project01.cliJvm.UiIntent
import ru.den.writes.code.project01.cliJvm.UiLine
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.memory.MemoryMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            assertTrue(lines.any { it is UiLine.Summary && it.text.startsWith("[session-summary]") })
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
                first is UiLine.State && first.text.startsWith("[session] resumed: 1 prior turn(s)"),
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
    fun `when a slash command runs - then its result lands in the state lane`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi().apply { queueText("reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val vm = newVm(newChat("hi", "s"), fake, store)

            // when
            vm.run(intents(UiIntent.SlashCommand(BranchCommand.Checkpoint), UiIntent.Exit))

            // then — a command result is a state line, so the TUI columns it like the resume banner
            assertTrue(
                vm.state.value.lines.any { it is UiLine.State && it.text.startsWith("[checkpoint] branch 'main'") },
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

            // then — interim summary + continuing notice, then the final Summary line, in order
            val texts = vm.state.value.lines.mapNotNull {
                when (it) {
                    is UiLine.Notice -> it.text
                    is UiLine.Summary -> it.text
                    is UiLine.State -> it.text
                    else -> null
                }
            }
            val interim = texts.indexOfFirst { it.startsWith("[feed done — interim summary]") }
            val continuing = texts.indexOfFirst { it.startsWith("[continuing in REPL") }
            val finalIdx = texts.indexOfFirst { it.startsWith("[session-summary]") }
            assertTrue(interim >= 0 && continuing > interim && finalIdx > continuing, "texts: $texts")
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
        assertFalse(lines.any { it is UiLine.Summary })
        assertEquals(UiEffect.Exit, vm.effects.receive())
    }

    //region picker

    @Test
    fun `when a branch picker opens - then its options list the branches`() = runTest {
        TestDb().use { harness ->
            // given — the opening turn populates 'main' with messages
            val fake = FakeLlmApi().apply { queueText("reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val vm = newVm(newChat("hi", "s"), fake, store)

            // when
            vm.run(intents(UiIntent.OpenPicker(PickerKind.Branch), UiIntent.Exit))

            // then
            val overlay = vm.state.value.overlay
            assertTrue(
                overlay is Overlay.Picker && overlay.kind == PickerKind.Branch && "main" in overlay.options,
                "expected a branch picker listing 'main', was $overlay",
            )
        }
    }

    @Test
    fun `when the picker cursor moves down - then the cursor advances`() = runTest {
        TestDb().use { harness ->
            // given — two profiles so the cursor has somewhere to go
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, store, memory = tempMemory("home", "work"))

            // when
            vm.run(intents(UiIntent.OpenPicker(PickerKind.Profile), UiIntent.OverlayDown, UiIntent.Exit))

            // then
            assertEquals(1, vm.state.value.overlay?.cursor)
        }
    }

    @Test
    fun `when a profile is picked by number - then the active profile switches`() = runTest {
        TestDb().use { harness ->
            // given — listProfileNames is sorted, so row 2 is 'work'
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val memory = tempMemory("home", "work")
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, store, memory = memory)

            // when — open, then pick row 2
            vm.run(intents(UiIntent.OpenPicker(PickerKind.Profile), UiIntent.Submit("2"), UiIntent.Exit))

            // then — picker closed, the existing SwitchProfile command ran
            assertNull(vm.state.value.overlay)
            assertTrue(
                vm.state.value.lines.any { it is UiLine.State && it.text == "[memory] active profile → work" },
                "lines: ${vm.state.value.lines}",
            )
            assertEquals("work", memory.activeProfileName())
        }
    }

    @Test
    fun `when a profile picker opens without memory - then it explains and stays closed`() = runTest {
        TestDb().use { harness ->
            // given — no memory provider
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, store)

            // when
            vm.run(intents(UiIntent.OpenPicker(PickerKind.Profile), UiIntent.Exit))

            // then
            assertNull(vm.state.value.overlay)
            assertTrue(
                vm.state.value.lines.any {
                    it is UiLine.State && it.text.startsWith("[memory] memory commands need")
                },
            )
        }
    }

    @Test
    fun `when the picker is cancelled - then it closes and runs no command`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val memory = tempMemory("home")
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, store, memory = memory)

            // when
            vm.run(intents(UiIntent.OpenPicker(PickerKind.Profile), UiIntent.OverlayCancel, UiIntent.Exit))

            // then
            assertNull(vm.state.value.overlay)
            assertNull(memory.activeProfileName())
        }
    }
    //endregion

    //region palette

    @Test
    fun `when the palette opens - then it lists the command catalog`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, store)

            // when
            vm.run(intents(UiIntent.OpenPalette, UiIntent.Exit))

            // then
            val overlay = vm.state.value.overlay
            assertTrue(overlay is Overlay.Palette && overlay.entries == commandCatalog(), "was $overlay")
        }
    }

    @Test
    fun `when a no-argument command is chosen from the palette - then it runs`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, store)
            val row = commandCatalog().indexOfFirst { it.name == "/checkpoint" } + 1

            // when
            vm.run(intents(UiIntent.OpenPalette, UiIntent.Submit("$row"), UiIntent.Exit))

            // then
            assertNull(vm.state.value.overlay)
            assertTrue(vm.state.value.lines.any { it is UiLine.State && it.text.startsWith("[checkpoint]") })
        }
    }

    @Test
    fun `when a picker command is chosen from the palette - then that picker opens`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, store, memory = tempMemory("home"))
            val row = commandCatalog().indexOfFirst { it.name == "/profile-use" } + 1

            // when
            vm.run(intents(UiIntent.OpenPalette, UiIntent.Submit("$row"), UiIntent.Exit))

            // then — the palette handed off to the profile picker
            val overlay = vm.state.value.overlay
            assertTrue(overlay is Overlay.Picker && overlay.kind == PickerKind.Profile, "was $overlay")
        }
    }

    @Test
    fun `when a free-text command is chosen from the palette - then a prefill effect is emitted`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            val fake = FakeLlmApi().apply { queueText("reply") }
            val vm = newVm(newChat("hi", "s"), fake, store)
            val row = commandCatalog().indexOfFirst { it.name == "/rule" } + 1

            // when
            vm.run(intents(UiIntent.OpenPalette, UiIntent.Submit("$row"), UiIntent.Exit))

            // then — the first effect is the prefill stub
            assertEquals(UiEffect.Prefill("/rule "), vm.effects.receive())
        }
    }
    //endregion

    //region helpers

    private fun newVm(
        chat: CliArgs.PromptCommand,
        api: LlmApi,
        store: HistoryStore?,
        strategy: ContextStrategy = ContextStrategy.FullHistory,
        memory: MemoryProvider? = null,
    ): SessionViewModel {
        val engine = TurnEngine(chat, api, store, strategy, memory)
        val runner = CommandRunner(store, memory = memory, strategy = strategy)
        return SessionViewModel(chat, engine, runner, store, memory = memory, strategy = strategy, multiAgent = false)
    }

    /** A memory provider over a throwaway temp dir, pre-seeded with named profiles. */
    private fun tempMemory(vararg profiles: String): MemoryProvider {
        val root = Files.createTempDirectory("project01-vm-picker-").toFile().apply { deleteOnExit() }
        val store = MemoryStore(root).apply { profiles.forEach { touchNamedProfile(it) } }
        return MemoryProvider(store, MemoryMode.PREAMBLE)
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
