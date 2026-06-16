package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryLayer
import ru.den.writes.code.project01.cliJvm.memory.MemoryMode
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.cliJvm.memory.TaskNotes
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentMemoryTest {

    @Test
    fun `PREAMBLE mode prepends a USER memory frame and ASSISTANT ack to the wire list`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root).apply {
                    saveProfile("I write Kotlin")
                    addRule("No Spring")
                }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(chat, fake, store, promptSource = stdinSource("/exit\n"), memory = memory).run()

                val msgs = fake.calls.single().messages
                assertEquals(3, msgs.size, "expected [USER frame, ASSISTANT ack, USER prompt]")
                assertEquals(Role.USER, msgs[0].role)
                assertTrue(msgs[0].text.startsWith(MemoryLayer.PROFILE_HEADING))
                assertTrue(msgs[0].text.contains("I write Kotlin"))
                assertTrue(msgs[0].text.contains("No Spring"))
                assertEquals(Role.ASSISTANT, msgs[1].role)
                assertEquals(MemoryLayer.PREAMBLE_ACK, msgs[1].text)
                assertEquals(Message(Role.USER, "hi"), msgs[2])
            }
        }
    }

    @Test
    fun `SYSTEM mode emits all Role-SYSTEM messages before any USER message`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root).apply {
                    saveProfile("I write Kotlin")
                    addRule("No Spring")
                    saveTask(TaskNotes(taskId = "auth", goal = "JWT login"))
                }
                val memory = MemoryProvider(
                    memStore,
                    initialMode = MemoryMode.SYSTEM,
                    initialTaskId = "auth",
                )

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(chat, fake, store, promptSource = stdinSource("/exit\n"), memory = memory).run()

                val msgs = fake.calls.single().messages
                val systemMsgs = msgs.takeWhile { it.role == Role.SYSTEM }
                assertEquals(3, systemMsgs.size, "expected one SYSTEM per non-empty section")
                assertTrue(systemMsgs[0].text.startsWith(MemoryLayer.PROFILE_HEADING))
                assertTrue(systemMsgs[1].text.startsWith(MemoryLayer.RULES_HEADING))
                assertTrue(systemMsgs[2].text.startsWith(MemoryLayer.TASK_HEADING))
                // The opening prompt is the only non-SYSTEM tail item.
                assertEquals(listOf(Message(Role.USER, "hi")), msgs.drop(3))
            }
        }
    }

    @Test
    fun `memory frames never land in the persisted history`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root).apply { saveProfile("anything") }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.SYSTEM)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val dao = harness.db.messageDao()
                val store = HistoryStore(dao, sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(chat, fake, store, promptSource = stdinSource("/exit\n"), memory = memory).run()

                val rows = dao.all(sessionId = "demo")
                assertEquals(2, rows.size, "exactly the user prompt and the model reply")
                assertTrue(rows.all { it.role == Role.USER.name || it.role == Role.ASSISTANT.name })
            }
        }
    }

    @Test
    fun `memory layer is empty when nothing is saved so wire shape stays untouched`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root)  // empty profile / rules / tasks
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(chat, fake, store, promptSource = stdinSource("/exit\n"), memory = memory).run()

                // No memory frame at all — opening prompt is the lone entry.
                assertEquals(listOf(Message(Role.USER, "hi")), fake.calls.single().messages)
            }
        }
    }

    @Test
    fun `slash memory-mode flips the next turn's wire shape mid-session`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root).apply { saveProfile("Kotlin only") }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply {
                    queueText("first")
                    queueText("second")
                }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat, fake, store,
                    promptSource = stdinSource("/memory-mode system\ngo on\n/exit\n"),
                    memory = memory,
                ).run()

                // calls[0] — opening turn was PREAMBLE (USER frame + ASSISTANT ack + prompt)
                assertEquals(Role.USER, fake.calls[0].messages[0].role)
                assertEquals(Role.ASSISTANT, fake.calls[0].messages[1].role)
                // calls[1] — after /memory-mode system, the next turn carries Role.SYSTEM
                val secondWire = fake.calls[1].messages
                assertEquals(Role.SYSTEM, secondWire[0].role)
                assertTrue(secondWire[0].text.startsWith(MemoryLayer.PROFILE_HEADING))
            }
        }
    }

    @Test
    fun `slash profile writes to the store`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat, fake, store,
                    promptSource = stdinSource("/profile I write Kotlin and Compose\n/exit\n"),
                    memory = memory,
                ).run()

                assertEquals("I write Kotlin and Compose", memStore.loadProfile())
            }
        }
    }

    @Test
    fun `slash rule adds a numbered rule file`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat, fake, store,
                    promptSource = stdinSource("/rule No Spring Boot\n/exit\n"),
                    memory = memory,
                ).run()

                val rules = memStore.listRules()
                assertEquals(1, rules.size)
                assertEquals("001", rules[0].id)
                assertEquals("No Spring Boot", rules[0].text)
            }
        }
    }

    @Test
    fun `slash task sets active id and slash task-note appends to it`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat, fake, store,
                    promptSource = stdinSource("/task auth\n/task-note Ktor + JWT chosen\n/exit\n"),
                    memory = memory,
                ).run()

                assertEquals("auth", memory.activeTaskId())
                val task = memStore.loadTask("auth")
                assertTrue(task != null)
                assertEquals(listOf("Ktor + JWT chosen"), task.notes)
            }
        }
    }

    // --- helpers ----------------------------------------------------

    private fun newChat(prompt: String, session: String?): CliArgs.Chat = CliArgs.Chat(
        prompt = prompt,
        maxTokens = null,
        stopSequences = null,
        endSequence = null,
        temperature = null,
        modelProvider = ModelProvider.Gemini(
            model = GeminiModel.Known.Gemini25Flash,
            apiKey = "test-key",
        ),
        session = session,
        feedFile = null,
        chunkChars = 2500,
        feedInstruction = "",
        byLine = false,
        strategy = ContextStrategyKind.FULL,
        keepLast = 6,
        summarizeEvery = 10,
    )

    private fun stdinSource(script: String): StdinPromptSource =
        StdinPromptSource(BufferedReader(StringReader(script)))

    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-agent-memory-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
