package ru.den.writes.code.agenticHub.features.llm.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.LlmResult
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.Usage
import ru.den.writes.code.agenticHub.features.llm.buildLlmApi

/**
 * Koin module for the LLM layer. [ModelProvider] is runtime-derived (picked from
 * CLI args), so it's a factory parameter; the shared [io.ktor.client.HttpClient]
 * is pulled from the graph (its owner is the app — engine is app-specific).
 * A [LlmApi] per provider → factory, not single.
 */
public val llmModule: Module = module {
    factory<LlmApi> { (mp: ModelProvider) -> buildLlmApi(mp, get()) }
}

/**
 * Test counterpart of [llmModule]: binds [LlmApi] to a scripted fake that never
 * touches the network. Compose it in place of [llmModule] in an integration
 * graph (the fake ignores any `parametersOf` provider, so it's a drop-in for
 * `get<LlmApi> { parametersOf(mp) }`). Because Koin hands the fake out under the
 * [LlmApi] interface — too thin to script through — the queue lives in a public
 * [FakeLlmScript] `single`: configure it cross-module via
 * `koin.get<FakeLlmScript>().queueText(...)`. Plain `common` module → runs on
 * every target. See agenticHubClient/DI.md.
 */
public val llmTestModule: Module = module {
    single { FakeLlmScript() }
    single<LlmApi> { ScriptedLlmApi(get()) }
}

/**
 * Scriptable/inspectable driver behind [llmTestModule]'s fake [LlmApi]. Public so
 * tests (any module) can configure it off the graph; the [LlmApi] itself
 * ([ScriptedLlmApi]) stays `internal`. Mirrors `FakeLlmApi` from `:testing`, but
 * as a graph-resident holder rather than a hand-constructed stub.
 */
public class FakeLlmScript {
    private val responses = ArrayDeque<LlmResult>()

    /** One recorded `send`, with a defensive copy of the messages. */
    public data class Call(val messages: List<Message>, val params: GenerationParams)

    /** Every `send()` seen by the fake, in order. */
    public val calls: MutableList<Call> = mutableListOf()

    /** Append literal [LlmResult]s to the queue, consumed one per `send()`. */
    public fun queue(vararg results: LlmResult) {
        responses += results
    }

    /** Convenience: queue a successful reply with default-shaped usage counts. */
    public fun queueText(
        text: String,
        promptTokens: Int = 10,
        outputTokens: Int = 5,
        thoughtsTokens: Int = 0,
    ) {
        responses += LlmResult(
            text = text,
            usage = Usage(
                promptTokens = promptTokens,
                outputTokens = outputTokens,
                thoughtsTokens = thoughtsTokens,
                totalTokens = promptTokens + outputTokens + thoughtsTokens,
            ),
        )
    }

    internal fun record(messages: List<Message>, params: GenerationParams) {
        calls += Call(messages.toList(), params)
    }

    internal fun next(): LlmResult =
        if (responses.isEmpty()) {
            LlmResult(text = null, error = "FakeLlmScript: no scripted response")
        } else {
            responses.removeFirst()
        }
}

/**
 * Fake [LlmApi] driven by a [FakeLlmScript]. `internal` — only reachable via
 * [llmTestModule], under the [LlmApi] interface. Empty script → synthetic error
 * result (matches the production «failed call, already logged» contract).
 */
internal class ScriptedLlmApi(private val script: FakeLlmScript) : LlmApi {
    override suspend fun send(messages: List<Message>, params: GenerationParams): LlmResult {
        script.record(messages, params)
        return script.next()
    }
}
