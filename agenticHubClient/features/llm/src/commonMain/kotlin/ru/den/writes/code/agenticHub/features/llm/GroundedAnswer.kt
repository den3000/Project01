package ru.den.writes.code.agenticHub.features.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * One grounded citation: which retrieved chunk it points at ([source]/[section]/[chunkId], straight
 * off the chunk's `[source: …]` tag) plus the verbatim [quote] copied out of that chunk. A quote
 * without a source is useless, so the two travel together.
 */
public data class Citation(
    val source: String,
    val section: String?,
    val chunkId: Int,
    val quote: String,
)

/**
 * A RAG answer with its provenance made mandatory: the [answer] text, the [citations] backing it
 * (each a source and a verbatim [Citation.quote] from the retrieved context), and [isKnown] — false
 * when there was no relevant context and the assistant must ask the user to clarify instead of
 * guessing. The anti-hallucination contract: no confident answer without citations, and no citation
 * whose quote is not actually present in a retrieved chunk (verified by the caller).
 */
public data class GroundedAnswer(
    val answer: String,
    val citations: List<Citation>,
    val isKnown: Boolean,
)

/**
 * Build the messages for a grounded, cited answer: the retrieved [chunks] as tagged context plus a
 * strict JSON contract, and the [question]. The model must answer ONLY from the context, copy a
 * verbatim quote per source, and set `"known": false` (asking the user to clarify) when the context
 * doesn't carry the answer. Parse the reply with [parseGroundedAnswer].
 */
public fun groundedAnswerPrompt(question: String, chunks: List<ScoredChunk>): List<Message> {
    val context = chunks.joinToString("\n\n") { scored ->
        val m = scored.chunk.metadata
        val section = m.section?.let { " › $it" } ?: ""
        "[source: ${m.source}$section #${m.chunkId}]\n${scored.chunk.text}"
    }
    val system = Message(
        role = Role.SYSTEM,
        text = """
            Answer the question using ONLY the context below. Reply with a single JSON object and
            nothing else, shaped exactly like:
            {"answer": "...", "known": true, "citations": [{"source": "...", "section": "...", "chunk_id": 0, "quote": "..."}]}
            Rules:
            - Every "quote" MUST be copied verbatim from the context — never paraphrase or invent one.
            - Cite every source you relied on; "source", "section" and "chunk_id" come from its
              [source: …] tag.
            - If the context does not contain the answer, set "known" to false, leave "citations"
              empty, and in "answer" say you don't know and ask the user to clarify or rephrase.

            Context:
            $context
        """.trimIndent(),
    )
    return listOf(system, Message(Role.USER, question))
}

/**
 * Parse a model reply into a [GroundedAnswer]. Tolerates prose or ```json fences around the object
 * (it extracts the outermost `{ … }`). Citations missing a source or quote are dropped. On any
 * parse failure it falls back to a safe "not known" answer — better to ask the user than to surface
 * an unparseable or ungrounded response.
 */
public fun parseGroundedAnswer(reply: String): GroundedAnswer {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return notKnown(reply)
    return try {
        val dto = groundedJson.decodeFromString(GroundedAnswerDto.serializer(), reply.substring(start, end + 1))
        val citations = dto.citations
            .filter { it.source.isNotBlank() && it.quote.isNotBlank() }
            .map { Citation(it.source, it.section?.takeIf(String::isNotBlank), it.chunkId, it.quote) }
        GroundedAnswer(
            answer = dto.answer,
            citations = citations,
            isKnown = dto.known && dto.answer.isNotBlank(),
        )
    } catch (_: Exception) {
        notKnown(reply)
    }
}

/**
 * Anchor an answer's citations to reality: for each citation find the retrieved chunk whose text
 * actually contains the quote (whitespace-insensitive, verbatim), and rewrite its
 * source/section/chunkId from that chunk's real metadata — so provenance comes from the corpus, not
 * from whatever the model claimed. Citations whose quote appears in NO chunk are dropped as
 * hallucinated. The model supplies the answer and the quote; the code decides where it came from.
 */
public fun GroundedAnswer.groundedIn(chunks: List<ScoredChunk>): GroundedAnswer {
    val verified = citations.mapNotNull { c ->
        val quote = normalizeWhitespace(c.quote)
        val match = chunks.firstOrNull { quote.isNotBlank() && normalizeWhitespace(it.chunk.text).contains(quote) }
        match?.let {
            val m = it.chunk.metadata
            c.copy(source = m.source, section = m.section, chunkId = m.chunkId)
        }
    }
    return copy(citations = verified)
}

private fun normalizeWhitespace(text: String): String =
    text.lowercase().replace(WHITESPACE, " ").trim()

private val WHITESPACE = Regex("\\s+")

private fun notKnown(reply: String): GroundedAnswer =
    GroundedAnswer(
        answer = reply.ifBlank { "I don't know based on the available context. Could you clarify or rephrase?" },
        citations = emptyList(),
        isKnown = false,
    )

private val groundedJson = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
private data class GroundedAnswerDto(
    val answer: String = "",
    val known: Boolean = true,
    val citations: List<CitationDto> = emptyList(),
)

@Serializable
private data class CitationDto(
    val source: String = "",
    val section: String? = null,
    @SerialName("chunk_id") val chunkId: Int = -1,
    val quote: String = "",
)
