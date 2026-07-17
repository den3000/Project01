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
class SourceCorpusTest {

    // Один koin на класс; fileSystemTestModule на factory → каждый get() свежий фейк.
    private val koin = koinApplication { modules(fileSystemTestModule) }.koin

    @Test
    fun `when root holds docs and code - then both are collected with relative source`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "root readme")
        fs.writeText("/repo/shared/src/Task.kt", "data class Task")
        fs.writeText("/repo/build.gradle.kts", "plugins { }")

        // when
        val docs = sourceCorpus(fs, "/repo").sortedBy { it.source }

        // then
        assertEquals(listOf("README.md", "build.gradle.kts", "shared/src/Task.kt"), docs.map { it.source })
        assertEquals(listOf("README.md", "build.gradle.kts", "Task.kt"), docs.map { it.title })
        assertEquals("data class Task", docs.single { it.source == "shared/src/Task.kt" }.text)
    }

    @Test
    fun `when tree holds unindexable files - then only the wanted extensions are kept`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "keep")
        fs.writeText("/repo/Main.kt", "keep")
        fs.writeText("/repo/gradle.properties", "not source")
        fs.writeText("/repo/icon.png", "binary")

        // when
        val docs = sourceCorpus(fs, "/repo").sortedBy { it.source }

        // then
        assertEquals(listOf("Main.kt", "README.md"), docs.map { it.source })
    }

    @Test
    fun `when tree crosses noise dirs - then those files are skipped`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "keep")
        fs.writeText("/repo/build/generated/Gen.kt", "generated noise")
        fs.writeText("/repo/.git/HEAD.md", "vcs noise")
        fs.writeText("/repo/.gradle/cache.kt", "tool noise")

        // when
        val docs = sourceCorpus(fs, "/repo")

        // then
        assertEquals(listOf("README.md"), docs.map { it.source })
    }

    @Test
    fun `when extensions are narrowed - then only those are collected`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "docs")
        fs.writeText("/repo/Main.kt", "code")

        // when
        val docs = sourceCorpus(fs, "/repo", extensions = setOf("md"))

        // then
        assertEquals(listOf("README.md"), docs.map { it.source })
    }

    @Test
    fun `when root has nothing indexable - then empty`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/gradle.properties", "config")

        // then
        assertEquals(emptyList(), sourceCorpus(fs, "/repo"))
    }
}
