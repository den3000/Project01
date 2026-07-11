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
  - `LlmWithRagAnswerLiveTest` — baseline (без второго этапа): **10 контрольных вопросов**, полный
    пайплайн rag (chunk→embed→index→retrieve). Сетка 2×2 с **пином** grounding, наглядно «чистый vs
    зашумлённый корпус» на обоих провайдерах:
    - `SMALL_HANDBOOK` (10 чистых секций), top-3 → пин **10/10** — через Ollama и через Gemini.
    - `BIG_HANDBOOK` (каждый ответ завален decoy), top-1 → пин **1/10** — через Ollama и через Gemini.
    Retrieval детерминирован → пины стабильны. Gemini-строки **жгут токены**, нужен `GEMINI_API_KEY`
    (иначе skip). Корпус/вопросы/метрики — в `RagLiveFixtures`.
  - `LlmWithRagRerankerAnswerLiveTest` — те же 10 вопросов на `BIG_HANDBOOK` двумя путями: plain top-K vs
    query-rewrite → over-retrieve top-N → `ModelReranker` (CrossEncoder) → top-K-after. Ассерт
    относительный: reranked не ниже baseline и `>= n - MAX_MISSES` (замер: Ollama 1/10 → 10/10; Gemini →
    9/10 — один компаундный вопрос уходит в отказ). Ответ идёт через `GroundedAnswerer`, поэтому на каждый
    вопрос печатаются маркеры `retrieval`/`grounded`/`known` (✓/✗) и строка `cite` (источник + дословная
    цитата): baseline цитирует **decoy** (`grounded ✗` при `known ✓` и `cite`), reranked — реальную секцию
    (`grounded ✓`). Маркеры мерят «нашли / ответ с фактом / ответил ли», а галлюцинацию ловит сверка
    `+ RAG` ↔ `cite`, а не маркеры. Два теста по провайдеру rewrite/rerank/ответа: `…through Ollama…`
    (бесплатно) и `…through Gemini…` (**жжёт токены** — вызов модели на каждый кандидат, нужен `GEMINI_API_KEY`).
  - `LlmWithRagCitationsAnswerLiveTest` — анти-галлюцинации на `SMALL_HANDBOOK` через `GroundedAnswerer`:
    каждый из 10 ответов обязан быть `known`, нести ≥1 цитату, и цитата должна **дословно** встречаться в
    найденном чанке (провенанс переписывается из реальных метаданных). Второй тест — офф-топик вопрос →
    гейт по порогу отдаёт «не знаю, уточните». Ollama-локально, бесплатно.
  - `LlmOptimizationLiveTest` — оптимизация локальной модели под кейс (Zephyr Q&A). Retrieval фиксирован
    (одни чанки обоим), меняются только knob'ы и промпт: baseline (дефолты) vs optimized (`temperature 0`
    · `top_p` · `seed` · `num_ctx` · token-cap · thinking-off + terse/cite-first SYSTEM). Grounding
    ассертится толерантно, а **латентность и tok/s замеряются и печатаются** (`--- timing ---`) — виден
    выигрыш по скорости. Второй тест: `seed` делает optimized-прогон воспроизводимым. Третий: перебор
    тегов моделей (per-tag skip по `/api/tags`) — тредофф качество/скорость от квантования. Всё локально,
    без токенов; запуск — `--tests "*LlmOptimizationLiveTest*"` (можно `-i` для таблиц в консоли).
    Всем нужен `ollama pull nomic-embed-text`. Общий probe/модель — в `LlmWithRagLiveSupport`.
