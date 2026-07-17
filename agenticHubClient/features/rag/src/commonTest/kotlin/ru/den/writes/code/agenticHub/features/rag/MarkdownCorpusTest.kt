package ru.den.writes.code.agenticHub.features.rag

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule
import ru.den.writes.code.agenticHub.testutils.IgnoreIos
import kotlin.test.Test
import kotlin.test.assertEquals

// @IgnoreIos: fileSystemTestModule shares a file with the eager, iOS-TODO
// fileSystemModule val, which Kotlin/Native initializes on first touch.
@IgnoreIos
class MarkdownCorpusTest {

    // Один koin на класс; fileSystemTestModule на factory → каждый get() свежий фейк.
    private val koin = koinApplication { modules(fileSystemTestModule) }.koin

    @Test
    fun `when root holds nested markdown - then one document per file with relative source`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "root readme")
        fs.writeText("/repo/PLANS/01_plan.md", "a plan")

        // when
        val docs = markdownCorpus(fs, "/repo").sortedBy { it.source }

        // then
        assertEquals(listOf("PLANS/01_plan.md", "README.md"), docs.map { it.source })
        assertEquals(listOf("01_plan.md", "README.md"), docs.map { it.title })
        assertEquals("root readme", docs.single { it.source == "README.md" }.text)
    }

    @Test
    fun `when tree mixes non-markdown and noise dirs - then only clean markdown is kept`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "keep")
        fs.writeText("/repo/Main.kt", "code, not md")
        fs.writeText("/repo/build/report.md", "generated noise")
        fs.writeText("/repo/.git/HEAD.md", "vcs noise")

        // when
        val docs = markdownCorpus(fs, "/repo")

        // then
        assertEquals(listOf("README.md"), docs.map { it.source })
    }

    @Test
    fun `when root has no markdown - then empty`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/Main.kt", "code")

        // then
        assertEquals(emptyList(), markdownCorpus(fs, "/repo"))
    }
}
