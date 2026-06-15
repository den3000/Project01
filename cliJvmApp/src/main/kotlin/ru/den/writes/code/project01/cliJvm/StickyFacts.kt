package ru.den.writes.code.project01.cliJvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ru.den.writes.code.project01.cliJvm.db.HistoryStore

/**
 * Sticky-facts strategy (Day-10): keeps a compact key-value memory of the
 * conversation that survives the sliding window. After every user message it
 * asks the model to fold anything important into a JSON facts blob (a separate
 * LLM call, accounted as overhead — not a turn), and on each turn it sends
 * `[facts frame] + last keepLast messages` instead of the full history.
 *
 * Unlike [ContextStrategy.SlidingWindow] (which simply forgets old turns), the
 * facts block carries durable details (goals, constraints, names, numbers,
 * decisions) forward even after they scroll out of the window.
 *
 * [keepLast] is snapped down to even so the verbatim tail stays USER-first; the
 * injected facts pair (USER frame + ASSISTANT ack) preserves role alternation.
 */
internal class StickyFacts(keepLast: Int) : ContextStrategy {
    private val keepLast: Int = evenDown(keepLast)

    /** Latest facts JSON, or null until the first successful extraction / rebind. */
    private var currentFactsJson: String? = null

    override fun planContext(history: List<Message>): List<Message> {
        val tail = history.takeLast(keepLast)
        val facts = currentFactsJson ?: return tail
        return listOf(
            Message(Role.USER, FactsExtractor.FACTS_FRAME_PREFIX + facts),
            Message(Role.ASSISTANT, FactsExtractor.FACTS_ACK_TEXT),
        ) + tail
    }

    override suspend fun onTurn(ctx: TurnContext) {
        val prior = currentFactsJson
        val result = ctx.llmApi.send(
            messages = listOf(Message(Role.USER, FactsExtractor.buildExtractionPrompt(prior, ctx.userText))),
            params = GenerationParams(maxTokens = FactsExtractor.FACTS_MAX_TOKENS),
        )
        val merged = FactsExtractor.mergeOrKeep(prior, result.text)
        currentFactsJson = merged
        // Persist + fold the extraction call's token overhead (not a turn). A
        // failed/blank extraction degrades to the prior facts; we still record
        // what the call cost when usage came back.
        if (merged != null) {
            ctx.store.saveFacts(factsJson = merged, modelId = ctx.modelId, usage = result.usage)
        }
    }

    override suspend fun rebind(store: HistoryStore) {
        currentFactsJson = store.loadFacts()?.factsJson
    }
}

/**
 * Pure helpers for the sticky-facts extraction call — kept separate from the
 * strategy so prompt-building and the (failure-tolerant) JSON parsing are
 * unit-testable without a network or a store.
 */
internal object FactsExtractor {
    const val FACTS_FRAME_PREFIX: String = "[Known facts so far]\n"
    const val FACTS_ACK_TEXT: String = "Understood. I'll use these facts as context for what follows."
    const val FACTS_MAX_TOKENS: Int = 256

    /** Build the single USER turn that asks the model to update the facts JSON. */
    fun buildExtractionPrompt(priorFactsJson: String?, userMessage: String): String = buildString {
        appendLine("You maintain a compact JSON object of durable FACTS about the user and task")
        appendLine("(goals, constraints, preferences, decisions, key names/numbers). Fold anything")
        appendLine("important from the new user message into it; keep prior facts unless contradicted.")
        appendLine()
        appendLine("Current facts JSON:")
        appendLine(priorFactsJson ?: "{}")
        appendLine()
        appendLine("New user message:")
        appendLine("\"\"\"")
        appendLine(userMessage)
        appendLine("\"\"\"")
        appendLine()
        append("Return ONLY the updated JSON object — no prose, no code fences.")
    }

    /**
     * Validate + canonicalise the model's reply into a facts JSON string.
     * Returns the new object's compact JSON on success; on ANY failure (null /
     * blank / not a JSON object / parse error) returns [priorJson] unchanged
     * (which may itself be null when nothing has been extracted yet). Tolerates
     * a ```json fence the model may add despite the instruction.
     */
    fun mergeOrKeep(priorJson: String?, replyText: String?): String? {
        val text = replyText?.takeIf { it.isNotBlank() } ?: return priorJson
        return parseObjectOrNull(text)?.toString() ?: priorJson
    }

    private fun parseObjectOrNull(raw: String): JsonObject? {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }
}
