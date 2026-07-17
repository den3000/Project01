package ru.den.writes.code.agenticHub.features.rag.chunking

import ru.den.writes.code.agenticHub.features.rag.doc
import kotlin.test.Test
import kotlin.test.assertEquals

/** Marker strategy: emits one chunk whose text is [tag], so a test can name the route taken. */
private fun tagging(tag: String) = ChunkingStrategy { document ->
    listOf(
        Chunk(
            text = tag,
            metadata = ChunkMetadata(
                source = document.source,
                title = document.title,
                section = null,
                chunkId = 0,
            ),
        ),
    )
}

class ByExtensionChunkingTest {

    private val router = ByExtensionChunking(
        default = tagging("default"),
        byExtension = mapOf("md" to tagging("md"), "kt" to tagging("kt")),
    )

    private fun routeOf(source: String): String =
        router.chunk(doc(text = "body", source = source)).single().text

    @Test
    fun `when extension is registered - then its strategy handles the document`() {
        // when - then
        assertEquals("md", routeOf("README.md"))
        assertEquals("kt", routeOf("src/Main.kt"))
    }

    @Test
    fun `when extension is unknown - then the default strategy handles the document`() {
        // when - then
        assertEquals("default", routeOf("gradle.properties"))
    }

    @Test
    fun `when source has no extension - then the default strategy handles the document`() {
        // when - then
        assertEquals("default", routeOf("Dockerfile"))
    }

    @Test
    fun `when extension differs in case - then it still routes`() {
        // when - then
        assertEquals("md", routeOf("docs/NOTES.MD"))
    }

    @Test
    fun `when a directory name is dotted and the file has none - then the default handles it`() {
        // given — the dot lives in the directory, not the file name

        // when - then
        assertEquals("default", routeOf("src/main.kt.d/Makefile"))
    }

    @Test
    fun `when routed to structural chunking - then markdown sections survive`() {
        // given
        val real = ByExtensionChunking(
            default = TokenChunking(tokensPerChunk = 4),
            byExtension = mapOf("md" to StructuralChunking()),
        )
        val markdown = doc(text = "# Intro\nalpha beta", source = "README.md")

        // when
        val chunks = real.chunk(markdown)

        // then
        assertEquals(listOf("Intro"), chunks.map { it.metadata.section })
    }

    @Test
    fun `when routed to the default - then code is cut into token windows`() {
        // given
        val real = ByExtensionChunking(
            default = TokenChunking(tokensPerChunk = 2),
            byExtension = mapOf("md" to StructuralChunking()),
        )
        val code = doc(text = "fun main() { println() }", source = "Main.kt")

        // when
        val chunks = real.chunk(code)

        // then — token windows, structure-blind
        assertEquals(3, chunks.size)
        assertEquals(listOf(null, null, null), chunks.map { it.metadata.section })
    }
}
