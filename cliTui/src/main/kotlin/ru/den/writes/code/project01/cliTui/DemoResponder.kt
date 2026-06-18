package ru.den.writes.code.project01.cliTui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Одна реплика в ленте чата. SELECTION — результат интерактивной команды (напр. смена профиля). */
data class Msg(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT, STATS, SELECTION }
}

/** Синтетический размер контекстного окна для footer всех трёх прототипов. */
internal const val CONTEXT_WINDOW = 1_000_000

/**
 * Детерминированная заглушка «LLM» для песочницы: без сети и токенов.
 *
 * Назначение прототипа — сравнить три TUI-библиотеки, а не общаться с моделью,
 * поэтому ответ синтетический. Эхо отдаётся [stream] по словам с паузой, чтобы
 * было видно, как каждый TUI перерисовывается во время «стриминга». Образец
 * подхода — детерминированный `FakeLlmApi` из offline-тестов `cliJvmApp`.
 */
class DemoResponder {

    /** Фейковый набор профилей для демонстрации интерактивного выбора. */
    val profiles: List<String> = listOf("default", "work", "research", "casual")

    /** Слова ответа на [prompt], эмитятся по одному с задержкой (имитация стрима). */
    fun stream(prompt: String): Flow<String> = flow {
        for (word in reply(prompt).split(" ")) {
            delay(STREAM_DELAY_MS)
            emit(word)
        }
    }

    /** Грубая оценка токенов (≈ 4 символа на токен), как ориентир для footer. */
    fun estimateTokens(text: String): Int = (text.trim().length / 4).coerceAtLeast(1)

    private fun reply(prompt: String): String =
        if (prompt.trim().equals("multiline", ignoreCase = true)) {
            // Ответ-маркер для проверки многострочного рендеринга в TUI.
            MULTILINE.joinToString("\n")
        } else {
            "Эхо: «${prompt.trim()}». " + FILLER.joinToString(" ")
        }

    private companion object {
        const val STREAM_DELAY_MS = 55L
        val FILLER = listOf("Ответ", "заглушки", "—", "без", "сети", "и", "токенов.")
        val MULTILINE = listOf(
            "Строка 1 — проверка многострочного ответа.",
            "Строка 2 — каждая идёт отдельной линией.",
            "Строка 3 — продолжения выровнены под префиксом.",
            "Строка 4 — конец демо-ответа.",
        )
    }
}
