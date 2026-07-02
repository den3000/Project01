# :agenticHubClient:features:memory — memory-домен + persistence + компакция

KMP-модуль, фундамент памяти (ниже `features:agent` в графе `llm ← memory ← agent`): доменные типы
памяти (профиль/правила/task-FSM/memory-layer), rolling-summary компакция, стратегии сборки контекста
хода и весь persistence (история поверх Room, файловая память). Скрывает Room/ФС-типы за портами.

## Публичный API
- **Домен памяти**: `Profile` (`ProfileSection`/`ProfileData`/`parseProfileData`/`renderProfileData`),
  `MemoryLayer` (`composePreamble`/`composeSystem`), `MemoryMode`, `MemoryModels` (`RuleEntry`/
  `TaskNotes`), `TaskState` (`TaskStage` clarification→…→done + `TaskStateMachine` + `TaskBinding`).
- **Компакция/контекст**: `HistoryCompressor` (rolling-summary, чистый) + `evenDown`;
  `ContextStrategy` (full/window/summary; `Summary` оборачивает `HistoryCompressor`) + `TurnContext`/
  `ContextStrategyKind`; `StickyFacts`/`FactsExtractor`.
- **Persistence**: `HistoryStore` (+снимки `SummarySnapshot`/`FactsSnapshot`) + `RoomHistoryStore`
  (`db/`, поверх `platform:database`); `SessionStats`(+`seedFrom`); `MemoryStore`/`FileMemoryStore`
  (markdown под `~/.project01-cli/memory/`: `profile.md`, `profiles/<name>.md`, `rules/NNN-*.md`,
  `tasks/<id>.md`, поверх `platform:fileSystem`); `MemoryProvider` (фасад: режим+taskId+activeProfileName).

## Зависимости
- `api(features:llm)` (Message/Usage/LlmApi/PricingRegistry протекают через порты) +
  `api(platform:database)`, `implementation(platform:fileSystem)`, `implementation(platform:logging)`,
  serialization-json. **НЕ зависит на `features:agent`** — наоборот, `agent → memory`. Потребители —
  `features:agent`, `lifecycle:*`, `apps:cliJvmApp`.

## Тесты
`./gradlew :agenticHubClient:features:memory:jvmTest` — `MemoryStoreTest`, `MemoryProviderProfileTest`.
Тесты домена/компакции (`HistoryCompressorTest`/`MemoryLayerTest`/`ProfileTest`/`TaskStateTest`)
живут в `features:agent` commonTest (используют его `FakeLlmApi`; agent → memory).

## Грабли
- **`Role.SYSTEM` НЕ персистится в `HistoryStore`** — в `messages`-таблице только `USER`/`ASSISTANT`;
  memory-слой инжектится лишь в wire-list `TurnEngine.turn()` поверх baseContext.
- **Модель файловой памяти**: unnamed `profile.md` + именованные `profiles/<name>.md` (активный
  поверх unnamed, fallback на unnamed); структурированные секции (style/format/constraints/context);
  правила `rules/NNN-*.md`; задачи `tasks/<id>.md`. Грамматика CLI-команд — в `apps:cliJvmApp`.
- **`System.err` → `logWarn`**: код KMP (commonMain), JVM-only API нельзя (iOS) — для лога компакции
  `logWarn` из `platform:logging`.
