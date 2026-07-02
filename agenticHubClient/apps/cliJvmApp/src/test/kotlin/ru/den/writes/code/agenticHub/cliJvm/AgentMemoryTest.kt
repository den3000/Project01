package ru.den.writes.code.agenticHub.cliJvm

import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
import ru.den.writes.code.agenticHub.cliJvm.agent.createStdinPromptSource

import ru.den.writes.code.agenticHub.cliJvm.agent.runSessionForTest
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.Role
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.viewmodel.command.StartCommand
import ru.den.writes.code.agenticHub.features.viewmodel.command.SessionConfig
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.features.agent.memory.MemoryLayer
import ru.den.writes.code.agenticHub.features.agent.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.agent.memory.ProfileData
import ru.den.writes.code.agenticHub.features.agent.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.agent.memory.TaskNotes
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentMemoryTest {

    @Test
    fun `PREAMBLE mode prepends a USER memory frame and ASSISTANT ack to the wire list`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    saveProfile("I write Kotlin")
                    addRule("No Spring")
                }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(chat, fake, store, promptSource = createStdinPromptSource("/exit\n"), memory = memory)

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
                val memStore = FileMemoryStore(root.absolutePath).apply {
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
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(chat, fake, store, promptSource = createStdinPromptSource("/exit\n"), memory = memory)

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
                val memStore = FileMemoryStore(root.absolutePath).apply { saveProfile("anything") }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.SYSTEM)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val dao = harness.db.messageDao()
                val store = RoomHistoryStore(dao, sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(chat, fake, store, promptSource = createStdinPromptSource("/exit\n"), memory = memory)

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
                val memStore = FileMemoryStore(root.absolutePath)  // empty profile / rules / tasks
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(chat, fake, store, promptSource = createStdinPromptSource("/exit\n"), memory = memory)

                // No memory frame at all — opening prompt is the lone entry.
                assertEquals(listOf(Message(Role.USER, "hi")), fake.calls.single().messages)
            }
        }
    }

    @Test
    fun `slash agent mode flips the next turn's wire shape mid-session`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath).apply { saveProfile("Kotlin only") }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply {
                    queueText("first")
                    queueText("second")
                }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat, fake, store,
                    promptSource = createStdinPromptSource("/agent mode system\ngo on\n/exit\n"),
                    memory = memory,
                )

                // calls[0] — opening turn was PREAMBLE (USER frame + ASSISTANT ack + prompt)
                assertEquals(Role.USER, fake.calls[0].messages[0].role)
                assertEquals(Role.ASSISTANT, fake.calls[0].messages[1].role)
                // calls[1] — after /agent mode system, the next turn carries Role.SYSTEM
                val secondWire = fake.calls[1].messages
                assertEquals(Role.SYSTEM, secondWire[0].role)
                assertTrue(secondWire[0].text.startsWith(MemoryLayer.PROFILE_HEADING))
            }
        }
    }

    @Test
    fun `slash rule adds a numbered rule file`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat, fake, store,
                    promptSource = createStdinPromptSource("/rule \"No Spring Boot\"\n/exit\n"),
                    memory = memory,
                )

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
                val memStore = FileMemoryStore(root.absolutePath)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat, fake, store,
                    promptSource = createStdinPromptSource("/task auth\n/task note \"Ktor + JWT chosen\"\n/exit\n"),
                    memory = memory,
                )

                assertEquals("auth", memory.activeTaskId())
                val task = memStore.loadTask("auth")
                assertTrue(task != null)
                assertEquals(listOf("Ktor + JWT chosen"), task.notes)
            }
        }
    }

    @Test
    fun `slash task note without an active task does not crash and writes nothing`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat, fake, store,
                    promptSource = createStdinPromptSource("/task note stranded\n/exit\n"),
                    memory = memory,
                )

                assertEquals(emptyList(), memStore.listTaskIds(), "no task should have been created")
            }
        }
    }

    @Test
    fun `slash profile bare lists profiles and leaves the store empty`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat, fake, store,
                    promptSource = createStdinPromptSource("/profile\n/exit\n"),
                    memory = memory,
                )

                assertEquals(null, memStore.loadProfile())
            }
        }
    }

    @Test
    fun `slash agent mode with garbage falls through as a normal prompt`() = runTest {
        // /agent mode without a valid value isn't a recognised command, so
        // parseSessionCommand returns null and the line travels as a user
        // prompt — the agent sends a second turn and the mode stays put.
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath).apply { saveProfile("anything") }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply {
                    queueText("first")
                    queueText("second")
                }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat, fake, store,
                    promptSource = createStdinPromptSource("/agent mode shrug\n/exit\n"),
                    memory = memory,
                )

                assertEquals(MemoryMode.PREAMBLE, memory.currentMode())
                assertEquals(2, fake.calls.size, "garbage /agent mode landed as a real prompt")
            }
        }
    }

    @Test
    fun `slash memory and friends without a memory provider do not crash`() = runTest {
        // Agent without MemoryProvider: the memory commands should print an
        // explanatory line and become no-ops, not throw or persist anything.
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply { queueText("ok") }
            val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
            val chat = newChat(prompt = "hi", session = "demo")

            runSessionForTest(
                chat, fake, store,
                promptSource = createStdinPromptSource("/memory\n/profile yo\n/rule no\n/exit\n"),
                memory = null,
            )

            // Only the opening turn went out; the three memory commands were
            // recognised but bounced because no MemoryProvider was wired.
            assertEquals(1, fake.calls.size)
        }
    }

    // --- personalization --------------------------------------------

    @Test
    fun `structured profile renders subsection labels into the wire`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    saveProfileData(
                        ProfileData(
                            style = listOf("кратко", "русский"),
                            format = listOf("code-first"),
                            constraints = listOf("Kotlin only"),
                            context = listOf("Android dev"),
                        )
                    )
                }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(chat, fake, store, promptSource = createStdinPromptSource("/exit\n"), memory = memory)

                val frame = fake.calls.single().messages.first().text
                assertTrue(frame.startsWith(MemoryLayer.PROFILE_HEADING))
                assertTrue(frame.contains("Style:\n- кратко\n- русский"), "missing style block:\n$frame")
                assertTrue(frame.contains("Format:\n- code-first"))
                assertTrue(frame.contains("Constraints:\n- Kotlin only"))
                assertTrue(frame.contains("Context:\n- Android dev"))
            }
        }
    }

    @Test
    fun `different profiles produce different wire payloads for the same prompt`() = runTest {
        suspend fun captureFrame(profile: ProfileData): String {
            var captured: String? = null
            TestDb().use { harness ->
                withTempMemoryRoot { root ->
                    val memStore = FileMemoryStore(root.absolutePath).apply { saveProfileData(profile) }
                    val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                    val fake = FakeLlmApi().apply { queueText("ok") }
                    val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                    val chat = newChat(prompt = "Как реализовать кэш?", session = "demo")
                    runSessionForTest(chat, fake, store, promptSource = createStdinPromptSource("/exit\n"), memory = memory)
                    captured = fake.calls.single().messages.first().text
                }
            }
            return captured!!
        }

        val frameA = captureFrame(
            ProfileData(
                style = listOf("кратко"),
                constraints = listOf("Kotlin"),
                context = listOf("senior KMP dev"),
            )
        )
        val frameB = captureFrame(
            ProfileData(
                style = listOf("подробно, с примерами"),
                constraints = listOf("Python"),
                context = listOf("junior backend dev"),
            )
        )

        assertTrue(frameA != frameB, "the same prompt with different profiles must produce different wire frames")
        assertTrue(frameA.contains("Style:\n- кратко"))
        assertTrue(frameB.contains("Style:\n- подробно, с примерами"))
        assertTrue(frameA.contains("Constraints:\n- Kotlin"))
        assertTrue(frameB.contains("Constraints:\n- Python"))
    }

    @Test
    fun `slash profile section appends a bullet to the store`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat,
                    fake,
                    store,
                    promptSource = createStdinPromptSource("/profile style \"кратко на русском\"\n/exit\n"),
                    memory = memory,
                )

                val data = memStore.loadProfileData()
                assertEquals(listOf("кратко на русском"), data?.style)
            }
        }
    }

    @Test
    fun `slash profile clear drops every section including legacy free text`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    saveProfile("legacy free text")
                    addProfileItem(ProfileSection.STYLE, "кратко")
                }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat,
                    fake,
                    store,
                    promptSource = createStdinPromptSource("/profile clear\n/exit\n"),
                    memory = memory,
                )

                assertEquals(null, memStore.loadProfileData())
            }
        }
    }

    // --- multi-profile ----------------------------------------------

    @Test
    fun `dash profile flag pre-selects the active named profile`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    addNamedProfileItem("kotlin-senior", ProfileSection.STYLE, "кратко")
                    addNamedProfileItem("kotlin-senior", ProfileSection.CONSTRAINTS, "Kotlin")
                }
                val memory = MemoryProvider(
                    memStore,
                    initialMode = MemoryMode.PREAMBLE,
                    initialProfileName = "kotlin-senior",
                )
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(chat, fake, store, promptSource = createStdinPromptSource("/exit\n"), memory = memory)

                val frame = fake.calls.single().messages.first().text
                assertTrue(frame.contains("Style:\n- кратко"))
                assertTrue(frame.contains("Constraints:\n- Kotlin"))
            }
        }
    }

    @Test
    fun `slash profile name switches the active profile and the next turn picks the new wire`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath).apply {
                    addNamedProfileItem("kotlin-senior", ProfileSection.STYLE, "кратко")
                    addNamedProfileItem("python-junior", ProfileSection.STYLE, "подробно")
                }
                val memory = MemoryProvider(
                    memStore,
                    initialMode = MemoryMode.PREAMBLE,
                    initialProfileName = "kotlin-senior",
                )
                val fake = FakeLlmApi().apply {
                    queueText("ok-1")
                    queueText("ok-2")
                }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "first", session = "demo")

                runSessionForTest(
                    chat,
                    fake,
                    store,
                    promptSource = createStdinPromptSource("/profile python-junior\nsecond\n/exit\n"),
                    memory = memory,
                )

                assertEquals(2, fake.calls.size, "expected two LLM turns")
                val firstFrame = fake.calls[0].messages.first().text
                val secondFrame = fake.calls[1].messages.first().text
                assertTrue(firstFrame.contains("Style:\n- кратко"), "first turn should use kotlin-senior:\n$firstFrame")
                assertTrue(secondFrame.contains("Style:\n- подробно"), "second turn should use python-junior:\n$secondFrame")
            }
        }
    }

    @Test
    fun `slash profile name section appends to the named profile even when it is not active`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = FileMemoryStore(root.absolutePath)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                runSessionForTest(
                    chat,
                    fake,
                    store,
                    promptSource = createStdinPromptSource(
                        "/profile kotlin-senior style кратко\n" +
                            "/profile kotlin-senior constraints Kotlin\n" +
                            "/exit\n"
                    ),
                    memory = memory,
                )

                val data = assertNotNull(memStore.loadNamedProfile("kotlin-senior"))
                assertEquals(listOf("кратко"), data.style)
                assertEquals(listOf("Kotlin"), data.constraints)
                // Active profile is still null — unnamed fallback path.
                assertEquals(null, memory.activeProfileName())
            }
        }
    }

    // --- helpers ----------------------------------------------------

    private fun newChat(prompt: String, session: String?): StartCommand.RunChat = StartCommand.RunChat(
        prompt = prompt,
        maxTokens = null,
        stopSequences = null,
        endSequence = null,
        temperature = null,
        modelProvider = ModelProvider.Gemini(
            model = GeminiModel.Known.Gemini25Flash,
            apiKey = "test-key",
        ),
        config = SessionConfig(
            session = session,
            feedFile = null,
            chunkChars = 2500,
            feedInstruction = "",
            byLine = false,
            strategy = ContextStrategyKind.FULL,
            keepLast = 6,
            summarizeEvery = 10,
            task = null,
            profile = null,
            memoryMode = null,
            stageAgents = emptyList(),
            tui = false,
            judgeAgents = emptyList(),
        ),
    )


    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-agent-memory-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
