package ru.den.writes.code.agenticHub.features.agent.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.agent.StageAgentSpec
import ru.den.writes.code.agenticHub.features.agent.StageAgentSpecs
import ru.den.writes.code.agenticHub.features.agent.StageJudgeSpec
import ru.den.writes.code.agenticHub.features.agent.StageJudgeSpecs
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.memory.TaskBinding
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the Koin wiring of the multi-agent factories against two generic-erasure traps:
 * a `List` spec passed via `parametersOf` being resolved as the factory's own `List<…>`
 * return value (skipping the builder), and the two `List<…>` factories colliding on the
 * erased `java.util.List` key. Both once produced a `StageAgentSpec cannot be cast to
 * RoutedAgent` at the first turn.
 */
class AgentModuleTest {

    private val binding = TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE)

    @AfterTest
    fun tearDown() = stopKoin()

    private fun koin() = startKoin {
        // A real HttpClient is never called — the builders only stash it inside each LlmApi.
        modules(agentModule, module { single { HttpClient(Java) } })
    }.koin

    @Test
    fun `when resolving routed agents - then the factory runs and returns RoutedAgents, not specs`() {
        // given
        val koin = koin()
        val specs = StageAgentSpecs(listOf(StageAgentSpec(binding, ModelProvider.LocalOllama(), profileName = "support")))

        // when
        val agents: List<RoutedAgent> =
            koin.get(named(ROUTED_AGENTS)) { parametersOf(specs, GenerationParams()) }

        // then — a real RoutedAgent (accessing .profileName would CCE if it were a spec)
        assertEquals(1, agents.size)
        assertEquals("support", agents[0].profileName)
        assertEquals(binding, agents[0].binding)
    }

    @Test
    fun `when resolving routed judges - then the qualifier keeps them distinct from agents`() {
        // given
        val koin = koin()
        val specs = StageJudgeSpecs(listOf(StageJudgeSpec(binding, ModelProvider.LocalOllama())))

        // when
        val judges: List<RoutedJudge> =
            koin.get(named(ROUTED_JUDGES)) { parametersOf(specs) }

        // then
        assertEquals(1, judges.size)
        assertEquals(binding, judges[0].binding)
    }

    @Test
    fun `when the spec lists are empty - then both bindings resolve to empty lists`() {
        // given
        val koin = koin()

        // when
        val agents: List<RoutedAgent> =
            koin.get(named(ROUTED_AGENTS)) { parametersOf(StageAgentSpecs(emptyList()), GenerationParams()) }
        val judges: List<RoutedJudge> =
            koin.get(named(ROUTED_JUDGES)) { parametersOf(StageJudgeSpecs(emptyList())) }

        // then
        assertEquals(emptyList(), agents)
        assertEquals(emptyList(), judges)
    }
}
