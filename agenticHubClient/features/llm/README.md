# :agenticHubClient:features:llm — provider-нейтральное LLM-ядро

KMP-модуль: единый контракт к chat-style LLM + реализации по провайдерам (Gemini/OpenRouter/
Hugging Face/локальная Ollama) + tool-типы, ценовой реестр и generic tool-роутер. `HttpClient`
инжектится снаружи (движок в апп-модуле), так что ядро остаётся портируемым.

## Публичный API
- `LlmApi` + нейтральные `Message`/`Role`/`GenerationParams`(вкл. `thinkingBudget`, `topP`/`seed`/
  `contextWindow` — Ollama-only, облачные провайдеры их игнорируют)/`Usage`/
  `LlmResult` (`LlmApi.kt`); `ModelProvider` (sealed-дискриминатор, `ModelProvider.kt`).
- Провайдеры `gemini|openrouter|huggingface|ollama/` — `*Api` (реализация `LlmApi`) + `*Dto` + `*Model`
  (typed каталог + `Custom`). `ollama/LocalOllamaApi` — генеративный chat к локальной Ollama
  (`POST /api/chat`, без ключа, `baseUrl` настраивается); эмбеддинги живут в `features:rag`.
- `Tool.kt` — `ToolDefinition`/`ToolCall`/`fun interface ToolExecutor` (нейтральные tool-типы) +
  `McpToolRouter.kt` (generic над `ToolExecutor`: объединяет несколько executor'ов, роутит по имени).
- RAG-обвязка над `features:rag`: `RagContextMapper.ragChunksToContextMessage(List<ScoredChunk>)`
  (grounding-промпт из найденных чанков); `QueryRewriter` (fun interface + `Identity` + `ModelQueryRewriter`
  — перефраз запроса перед retrieval); `ModelReranker` — модельный CrossEncoder-`Reranker` (реализует
  rag-интерфейс через per-candidate скоринг «отвечает ли пассаж на запрос» + порог + topK-after).
- Grounded-ответ (обязательные источники + цитаты + анти-галлюцинация): `GroundedAnswer(answer,
  citations[source,section,chunkId,quote], isKnown)`; `groundedAnswerPrompt` (строгий JSON-контракт:
  ответ ТОЛЬКО из контекста, дословная цитата на источник, `known=false`+уточнение если ответа нет);
  `parseGroundedAnswer` (терпит fenced/prose, битый JSON → safe not-known); `GroundedAnswer.groundedIn(
  chunks)` — переписывает провенанс из реальных чанков и режет галлюцинированные цитаты; `GroundedAnswerer`
  — оркестратор с **code-гейтом** «релевантность < порога → не знаю без вызова модели».
- `pricing/ModelPricing.kt` — `ModelPricing`/`PricingRegistry` (**single source of truth по ценам**).
- `LlmFactories.kt` — `buildLlmApi`/`buildModelProvider`(+`ModelProviderError`).
- `di/`: `llmModule` — `factory<LlmApi> { (mp: ModelProvider) -> buildLlmApi(mp, get()) }` (`ModelProvider`
  runtime, `HttpClient` из графа). Рядом `llmTestModule` (val) — `factory<LlmApi> { (script) ->
  LlmApiFake(script ?: get()) }` + `factory { FakeLlmScript() }`: сетевой вызов не делается, скрипт
  ответов/инспекция в публичном `FakeLlmScript` (тест создаёт, `queueText`, передаёт через
  `parametersOf`; оба фейка в `LlmApiFake.kt`). Общая дока — [DI.md](../../DI.md).

## Зависимости
- `api(ktor.client.core)` + `api(kotlinx.coroutinesCore)` (протекают через публичные `*Api`),
  `implementation(platform:logging)` + `implementation(koin.core)` (для di).
- `api(features:rag)` — `RagContextMapper.ragChunksToContextMessage(List<ScoredChunk>)` (сборка
  grounding-промпта для RAG-ответа) торчит `ScoredChunk` в публичной сигнатуре. Цикла нет: `features:rag`
  не зависит от llm. `jvmTest` дополнительно тянет `platform:config` (BuildKonfig для Gemini live-теста).

## Тесты
- Offline (по умолчанию): `./gradlew :agenticHubClient:features:llm:jvmTest` — `*ApiTest`
  (gemini/openrouter/huggingface/ollama, застаблено), `GeminiFunctionCallTest`, `PricingRegistryTest`,
  `LlmFactoriesTest`, `OllamaChatDtoTest`; live-тесты (`*LiveTest`) исключены центральным гейтом.
- Live (`-PliveTests`, `jvmTest`): `LocalOllamaApiLiveTest` (генерация в локальную Ollama),
  `LlmWithRagAnswerLiveTest` (baseline без реранка, сетка 2×2 с пином grounding: `SMALL_HANDBOOK` top-3
  → 10/10, `BIG_HANDBOOK` top-1 → 1/10, каждое через Ollama и **реальный Gemini**) и
  `LlmWithRagRerankerAnswerLiveTest` (те же 10 вопросов на `BIG_HANDBOOK`: plain top-K vs
  rewrite+CrossEncoder-реранк поднимает 1/10 → 10/10; ответ через `GroundedAnswerer` — в выводе
  source+дословная цитата, baseline цитирует decoy; Ollama и Gemini) и
  `LlmWithRagCitationsAnswerLiveTest` (`GroundedAnswerer` на `SMALL_HANDBOOK`: каждый ответ known +
  цитата дословно из чанка + факт; офф-топик → гейт «не знаю»). Gemini жжёт токены и требует
  `GEMINI_API_KEY`; ретривел всегда через локальную Ollama. Общий корпус/метрики — `RagLiveFixtures`.
  Механизм — [LIVE_TESTS.md](../../../LIVE_TESTS.md).

## Грабли
- **Ktor engine — `Java`, не CIO** (CIO рвёт длинные thinking-ответы Gemini). Движок задаёт апп.
- **Gemini stateless** — историю шлём целиком каждый ход (растёт линейно).
- **Thinking-токены биллятся как output** — основная статья расхода у сильных моделей.
- **Gemini `Content.parts` — required → краш на пустом кандидате** (БАГ): при MAX_TOKENS-обрыве
  (thinking-модель + малый `-maxTokens`) `candidates[0].content` без `parts` → `MissingFieldException`
  (`gemini/GeminiDto.kt`). Фикс: `parts = emptyList()` + мягкая обработка в `GeminiApi.send`.
- **Имена Gemini**: `gemini-3-flash-preview` = 3.1 Flash (без `.1`); `gemini-3.5-pro`/`-3.5-flash-lite`
  не существуют.
- **OpenRouter `:free`-roster протухает** — `:free` id мрут (404). Сверять с
  `https://openrouter.ai/api/v1/models`. Дефолт `openrouter/auto` — роутер, может быть платным.
- **HF Router** маршрутизирует между провайдерами — цены в `ModelPricing.kt` для HF *приближения*;
  503 при cold start → одна retry с `Retry-After`. Каталог: `https://router.huggingface.co/v1/models`.
- **Transient-retry несёт каждый провайдер сам** (`platform:network` намеренно без `HttpRequestRetry`):
  что считать transient и как ждать — знает только провайдер (body-level `error` у OpenRouter, server-hint
  backoff у Gemini). Матрица (явная, не случайная): **Gemini** — 429/timeout однократно + **503 многоходово**
  (общий бюджет `MAX_RETRIES=3` через `spendRetryBudget`; сырой connection-drop не ретраится); **HF** — 503
  однократно; **OpenRouter/Ollama** — retry нет.
- **flash-lite TPM 4M** — боттлнек нагрузочных прогонов (rate-limit раньше переполнения контекста).
- **Ollama**: токен-кап зовётся `num_predict` (не `max_tokens`), context window — `num_ctx`;
  `temperature`/`top_p`/`seed`/`stop`/`num_ctx` — внутри `options`;
  теги (`OllamaModel`) зависят от локально спуленных моделей → основной путь `Custom`, не `Known`. В
  `PricingRegistry` не заводим (локально бесплатно; lookup→null).
- **Ollama `stream` без дефолта — намеренно**: при `encodeDefaults=false` (наш JSON) поле, равное
  дефолту, выпадает с wire → Ollama стримит NDJSON → `NoTransformationFound`. Поле без дефолта уходит
  всегда (регресс — `OllamaChatDtoTest`).
- **Ollama thinking-модели** (gemma4/qwen3.5): reasoning идёт в отдельное поле `message.thinking`, при
  малом `num_predict` весь бюджет уходит туда, а `content` пуст. `GenerationParams.thinkingBudget`
  маппится в top-level `think` (0 → off, >0 → on, null → дефолт модели).
- **`McpToolRouter`: коллизия имён инструментов между серверами — fail-fast `require`** на старте
  (модель не должна видеть неоднозначный каталог).
- **`*Api` не печатают `>>>>`-debug в stdout** — прямой `println` рушил Kotter в TUI и порядок в plain.
