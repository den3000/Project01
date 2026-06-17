package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.llm.GeminiModel
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.Role
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.shared.memory.MemoryLayer
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.shared.memory.ProfileData
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.TaskNotes
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

    @Test
    fun `slash task-note without an active task does not crash and writes nothing`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat, fake, store,
                    promptSource = stdinSource("/task-note stranded\n/exit\n"),
                    memory = memory,
                ).run()

                assertEquals(emptyList(), memStore.listTaskIds(), "no task should have been created")
            }
        }
    }

    @Test
    fun `slash profile with no text is rejected and leaves the store empty`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat, fake, store,
                    promptSource = stdinSource("/profile\n/exit\n"),
                    memory = memory,
                ).run()

                assertEquals(null, memStore.loadProfile())
            }
        }
    }

    @Test
    fun `slash memory-mode with garbage falls through as a normal prompt`() = runTest {
        // /memory-mode without a valid value isn't a recognised command, so
        // parseBranchCommand returns null and the line travels as a user
        // prompt — the agent sends a second turn and the mode stays put.
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root).apply { saveProfile("anything") }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)

                val fake = FakeLlmApi().apply {
                    queueText("first")
                    queueText("second")
                }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat, fake, store,
                    promptSource = stdinSource("/memory-mode shrug\n/exit\n"),
                    memory = memory,
                ).run()

                assertEquals(MemoryMode.PREAMBLE, memory.currentMode())
                assertEquals(2, fake.calls.size, "garbage /memory-mode landed as a real prompt")
            }
        }
    }

    @Test
    fun `slash memory and friends without a memory provider do not crash`() = runTest {
        // Agent without MemoryProvider: the memory commands should print an
        // explanatory line and become no-ops, not throw or persist anything.
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply { queueText("ok") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
            val chat = newChat(prompt = "hi", session = "demo")

            Agent(
                chat, fake, store,
                promptSource = stdinSource("/memory\n/profile yo\n/rule no\n/exit\n"),
                memory = null,
            ).run()

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
                val memStore = MemoryStore(root).apply {
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
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(chat, fake, store, promptSource = stdinSource("/exit\n"), memory = memory).run()

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
                    val memStore = MemoryStore(root).apply { saveProfileData(profile) }
                    val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                    val fake = FakeLlmApi().apply { queueText("ok") }
                    val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                    val chat = newChat(prompt = "Как реализовать кэш?", session = "demo")
                    Agent(chat, fake, store, promptSource = stdinSource("/exit\n"), memory = memory).run()
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
                val memStore = MemoryStore(root)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat,
                    fake,
                    store,
                    promptSource = stdinSource("/profile style кратко на русском\n/exit\n"),
                    memory = memory,
                ).run()

                val data = memStore.loadProfileData()
                assertEquals(listOf("кратко на русском"), data?.style)
            }
        }
    }

    @Test
    fun `slash profile clear drops every section including legacy free text`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root).apply {
                    saveProfile("legacy free text")
                    addProfileItem(ProfileSection.STYLE, "кратко")
                }
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat,
                    fake,
                    store,
                    promptSource = stdinSource("/profile clear\n/exit\n"),
                    memory = memory,
                ).run()

                assertEquals(null, memStore.loadProfileData())
            }
        }
    }

    // --- multi-profile ----------------------------------------------

    @Test
    fun `dash profile flag pre-selects the active named profile`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root).apply {
                    addNamedProfileItem("kotlin-senior", ProfileSection.STYLE, "кратко")
                    addNamedProfileItem("kotlin-senior", ProfileSection.CONSTRAINTS, "Kotlin")
                }
                val memory = MemoryProvider(
                    memStore,
                    initialMode = MemoryMode.PREAMBLE,
                    initialProfileName = "kotlin-senior",
                )
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(chat, fake, store, promptSource = stdinSource("/exit\n"), memory = memory).run()

                val frame = fake.calls.single().messages.first().text
                assertTrue(frame.contains("Style:\n- кратко"))
                assertTrue(frame.contains("Constraints:\n- Kotlin"))
            }
        }
    }

    @Test
    fun `slash profile-use switches the active profile and the next turn picks the new wire`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                val memStore = MemoryStore(root).apply {
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
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "first", session = "demo")

                Agent(
                    chat,
                    fake,
                    store,
                    promptSource = stdinSource("/profile-use python-junior\nsecond\n/exit\n"),
                    memory = memory,
                ).run()

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
                val memStore = MemoryStore(root)
                val memory = MemoryProvider(memStore, initialMode = MemoryMode.PREAMBLE)
                val fake = FakeLlmApi().apply { queueText("ok") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                val chat = newChat(prompt = "hi", session = "demo")

                Agent(
                    chat,
                    fake,
                    store,
                    promptSource = stdinSource(
                        "/profile kotlin-senior style кратко\n" +
                            "/profile kotlin-senior constraints Kotlin\n" +
                            "/exit\n"
                    ),
                    memory = memory,
                ).run()

                val data = assertNotNull(memStore.loadNamedProfile("kotlin-senior"))
                assertEquals(listOf("кратко"), data.style)
                assertEquals(listOf("Kotlin"), data.constraints)
                // Active profile is still null — unnamed fallback path.
                assertEquals(null, memory.activeProfileName())
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
        task = null,
        profile = null,
        memoryMode = null,
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
