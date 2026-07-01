package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.project01.shared.agent.AgentConfig
import ru.den.writes.code.project01.shared.agent.AgentResponder
import ru.den.writes.code.project01.shared.invariant.LlmInvariantJudge
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.gemini.GeminiApi
import ru.den.writes.code.project01.shared.llm.huggingface.HuggingFaceApi
import ru.den.writes.code.project01.shared.llm.openrouter.OpenRouterApi
import java.util.UUID

/**
 * Builders for the objects a session runs on. Plain functions (no receiver) —
 * each constructs one runtime collaborator from a [StartCommand.RunChat] config
 * and/or a shared [HttpClient]. The getters that *derive* a value from the parsed
 * state live in `SessionAccessors`.
 */

/** Generous request timeout — LLM responses can take a while. */
internal const val REQUEST_TIMEOUT_MS = 300_000L

/**
 * One HTTP client for the whole session: avoids the cold-start race that killed
 * requests when the client closed too early, and keeps connections warm. Engine
 * Java (not CIO — CIO's chunked parser dies on long Gemini thinking responses).
 */
internal fun buildHttpClient(): HttpClient = HttpClient(Java) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

/** The concrete [LlmApi] for a provider, sharing the one [client]. */
internal fun buildLlmApi(mp: ModelProvider, client: HttpClient): LlmApi = when (mp) {
    is ModelProvider.Gemini -> GeminiApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
    is ModelProvider.OpenRouter -> OpenRouterApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
    is ModelProvider.HuggingFace -> HuggingFaceApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
}

/**
 * Per-stage agents: one [RoutedAgent] per spec, each its own [LlmApi] + fixed
 * profile, sharing [params]. Empty for single-agent sessions.
 */
internal fun buildRoutedAgents(
    chat: StartCommand.RunChat?,
    client: HttpClient,
    params: GenerationParams,
): List<RoutedAgent> = chat?.config?.stageAgents.orEmpty().map { spec ->
    RoutedAgent(
        binding = spec.binding,
        responder = AgentResponder(
            AgentConfig(buildLlmApi(spec.provider, client), params, spec.profileName),
        ),
        profileName = spec.profileName,
        modelId = spec.provider.modelId,
    )
}

/** Per-stage invariant judges: one [RoutedJudge] per spec on its own model. */
internal fun buildJudges(
    chat: StartCommand.RunChat?,
    client: HttpClient,
): List<RoutedJudge> = chat?.config?.judgeAgents.orEmpty().map { spec ->
    RoutedJudge(
        binding = spec.binding,
        checker = LlmInvariantJudge(buildLlmApi(spec.provider, client)),
        modelId = spec.provider.modelId,
    )
}

/** One [McpToolClient] per configured MCP server command (not yet connected). */
internal fun buildMcpToolClients(chat: StartCommand.RunChat?): List<McpToolClient> =
    chat?.config?.mcpServers.orEmpty().map { cmd ->
        McpToolClient(cmd.split(Regex("\\s+")).filter { it.isNotEmpty() })
    }

/**
 * A [McpToolRouter] fanning the model's tool calls out to the server that owns
 * each tool (names unique across servers), or null when there are no servers.
 * Assumes each client is already connected.
 */
internal suspend fun buildToolRouter(
    mcpClients: List<McpToolClient>,
    chat: StartCommand.RunChat?,
): McpToolRouter? = mcpClients
    .zip(chat?.config?.mcpServers.orEmpty())
    .map { (client, cmd) ->
        val defs = client.listToolDefinitions()
        System.err.println("[mcp] $cmd → tools: ${defs.joinToString { it.name }}")
        McpToolRouter.Route(client, defs)
    }
    .takeIf { it.isNotEmpty() }
    ?.let(::McpToolRouter)

/**
 * Eight-char hex slice off a random UUID — readable, easy to retype, ~4 billion
 * values. Matches `^[a-zA-Z0-9_-]+$`, so it's a valid `-session` to resume.
 */
internal fun generateSessionId(): String = UUID.randomUUID().toString().take(8)
