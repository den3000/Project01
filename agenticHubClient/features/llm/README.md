# :agenticHubClient:features:llm — provider-нейтральное LLM-ядро

KMP-модуль: единый контракт к chat-style LLM + реализации по провайдерам (Gemini/OpenRouter/
Hugging Face) + tool-типы, ценовой реестр и generic tool-роутер. `HttpClient` инжектится снаружи
(движок в апп-модуле), так что ядро остаётся портируемым.

## Публичный API
- `LlmApi` + нейтральные `Message`/`Role`/`GenerationParams`(вкл. `thinkingBudget`)/`Usage`/
  `LlmResult` (`LlmApi.kt`); `ModelProvider` (sealed-дискриминатор, `ModelProvider.kt`).
- Провайдеры `gemini|openrouter|huggingface/` — `*Api` (реализация `LlmApi`) + `*Dto` + `*Model`
  (typed каталог + `Custom`).
- `Tool.kt` — `ToolDefinition`/`ToolCall`/`fun interface ToolExecutor` (нейтральные tool-типы) +
  `McpToolRouter.kt` (generic над `ToolExecutor`: объединяет несколько executor'ов, роутит по имени).
- `pricing/ModelPricing.kt` — `ModelPricing`/`PricingRegistry` (**single source of truth по ценам**).
- `LlmFactories.kt` — `buildLlmApi`/`buildModelProvider`(+`ModelProviderError`).
- `di/`: `llmModule` — `factory<LlmApi> { (mp: ModelProvider) -> buildLlmApi(mp, get()) }` (`ModelProvider`
  runtime, `HttpClient` из графа). Рядом `llmTestModule()` (функция) — биндит `LlmApi` на `internal
  ScriptedLlmApi` + публичный holder `FakeLlmScript` (скрипт ответов/инспекция; настраивается через
  `koin.get<FakeLlmScript>()`; оба в `ScriptedLlmApi.kt`, для integration-графов вместо `llmModule`).
  Общая дока — [DI.md](../../DI.md).

## Зависимости
- `api(ktor.client.core)` + `api(kotlinx.coroutinesCore)` (протекают через публичные `*Api`),
  `implementation(platform:logging)` + `implementation(koin.core)` (для di). Лист в доменном слое (`agent`→`llm`).

## Тесты
`./gradlew :agenticHubClient:features:llm:jvmTest` — `*ApiTest` (gemini/openrouter/huggingface,
offline, застаблено), `GeminiFunctionCallTest`, `PricingRegistryTest`.

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
- **flash-lite TPM 4M** — боттлнек нагрузочных прогонов (rate-limit раньше переполнения контекста).
- **`McpToolRouter`: коллизия имён инструментов между серверами — fail-fast `require`** на старте
  (модель не должна видеть неоднозначный каталог).
- **`*Api` не печатают `>>>>`-debug в stdout** — прямой `println` рушил Kotter в TUI и порядок в plain.
