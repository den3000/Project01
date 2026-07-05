# Live-тесты

**Live-тест** — тест, который бьёт по **реальному внешнему сервису** (локальная Ollama, а дальше и
другие: удалённые API, БД, брокеры…). Он не входит в offline-сьют: обычный прогон тестов должен быть
быстрым, детерминированным и не зависеть от того, поднят ли сервис. Поэтому live-тесты **opt-in**.

## Конвенция

- **Имя класса оканчивается на `LiveTest`** (`OllamaLiveTest`, `DocsIndexingOllamaLiveTest`, …).
- Живут в `src/jvmTest` (нужен реальный клиент/движок — напр. `HttpClient` из `platform:network`).
- Каждый live-тест **сам себя страхует**: пробует достучаться до сервиса и, если тот недоступен,
  скипается через `org.junit.Assume.assumeTrue(...)`. Тогда прогон под флагом деградирует в `skipped`,
  а не краснеет из-за лежащего сервиса.

## Центральный гейт

Один блок в корневом [build.gradle.kts](build.gradle.kts) гейтит **все** модули разом:

```kotlin
subprojects {
    tasks.withType<Test>().configureEach {
        if (!project.hasProperty("liveTests")) {
            filter {
                isFailOnNoMatchingTests = false
                excludeTestsMatching("*LiveTest")
            }
        }
    }
}
```

- Ловит и `test` (JVM-приложения), и `jvmTest` (KMP-модули).
- Любой новый `*LiveTest` в любом модуле **автоматически** исключается из дефолтного прогона — без
  бойлерплейта в build-файле модуля.
- `isFailOnNoMatchingTests = false` — чтобы `--tests "*SomeLiveTest"` **без** флага не падал «No tests
  found» (он просто ничего не запустит).

## Как запускать

- **CLI:** `./gradlew :module:jvmTest -PliveTests` (или с `--tests "*OllamaLiveTest"` для одного класса).
- **Android Studio (click по gutter):** нужен тот же флаг. Варианты:
  - Прописать `liveTests=true` в **личном** `~/.gradle/gradle.properties` (не в git) — тогда исключение
    снимается на твоей машине, и тесты запускаются кликом.
  - Либо добавить `-PliveTests` в аргументы Gradle-run-конфигурации.
  - **Без флага** click-run по `*LiveTest` даст «0 tests» — гейт исключает даже явно выбранный `--tests`.
  - AS должна гонять тесты через Gradle (*Settings → Build Tools → Gradle → Run tests using: Gradle*).
- **НЕ** класть `liveTests=true` в **project** `gradle.properties` — иначе live-тесты включатся у всех и
  на CI (offline-сьют перестанет быть офлайновым).

## Как писать live-тест

1. Назвать класс `<...>LiveTest`, положить в `src/jvmTest`.
2. В начале — probe + `assumeTrue`, чтобы скипаться при недоступном сервисе.
3. Переиспользовать хелпер сервиса, если он есть.

Пример для Ollama — `liveOllamaTest(koin) { … }`
([OllamaLiveSupport.kt](agenticHubClient/features/rag/src/jvmTest/kotlin/ru/den/writes/code/agenticHub/features/rag/OllamaLiveSupport.kt)):
оборачивает `runTest`, пробует `GET /api/tags` и скипает, если Ollama не отвечает; `koin` из теста даёт
probe-`HttpClient`.

## Текущие live-тесты

- **`features:rag`** (нужны локальная Ollama + `ollama pull nomic-embed-text`):
  - `OllamaLiveTest` — реальный embed, семантическая близость, end-to-end retrieve.
  - `DocsIndexingOllamaLiveTest` — индексация реального корпуса (markdown репо) с сохранением
    JSON-индекса и метаданных; сравнение стратегий чанкинга.
- **`features:llm`** (нужна локальная Ollama + генеративная модель, по умолчанию `gemma4:26b`;
  тег переопределяется через `-Dollama.chat.model=<tag>`):
  - `LocalOllamaApiLiveTest` — генерация через `POST /api/chat` (простой вопрос + system-инструкция).
  - `LlmWithRagAnswerLiveTest` — «первый RAG-запрос»: **10 контрольных вопросов** по фиктивной базе
    (Project Zephyr handbook) с индексом vs без индекса, полный пайплайн rag (chunk→embed→index→retrieve).
    База, вопросы (с зафиксированными ожиданием+источником), сравнение и метрики (retrieval/grounding как
    доля, порог 8/10) — в самом файле. Два теста:
    - `…run through Ollama…` — генерация локально (`LocalOllamaApi`, default `gemma4:26b`).
    - `…run through Gemini…` — генерация через **реальный Gemini** (**жжёт токены**, нужен `GEMINI_API_KEY`,
      иначе skip); эмбеддинги всё равно локальные.
    Обоим нужен `ollama pull nomic-embed-text`. Общий probe/модель — в `LlmWithRagLiveSupport`.
