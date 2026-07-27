# :agenticHubClient:features:memory — memory-домен + persistence + компакция

KMP-модуль, фундамент памяти (ниже `features:agent` в графе `llm ← memory ← agent`): доменные типы
памяти (профиль/правила/task-FSM/memory-layer), rolling-summary компакция, стратегии сборки контекста
хода и весь persistence (история поверх Room, файловая память). Скрывает Room/ФС-типы за портами.

## Публичный API
- **Домен памяти**: `Profile` (`ProfileSection`/`ProfileData`/`parseProfileData`/`renderProfileData`),
  `MemoryLayer` (`composePreamble`/`composeSystem`), `MemoryMode`, `MemoryModels` (`RuleEntry`/
  `TaskNotes`), `TaskState` (`TaskStage` clarification→…→done + `TaskStateMachine` + `TaskBinding`).
  `TaskNotes.goal`/`notes` задаются из REPL (`/task goal "<text>"` / `/task note "<text>"`) и
  рендерятся в `MemoryLayer` каждый ход — держат цель диалога и уточнения.
- **Компакция/контекст**: `HistoryCompressor` (rolling-summary, чистый) + `evenDown`;
  `ContextStrategy` (full/window/summary; `Summary` оборачивает `HistoryCompressor`) + `TurnContext`/
  `ContextStrategyKind`; `StickyFacts`/`FactsExtractor`.
- **Persistence**: `HistoryStore` (+снимки `SummarySnapshot`/`FactsSnapshot`) + `RoomHistoryStore`
  (`db/`, поверх `platform:database`); `SessionStats`(+`seedFrom`); `MemoryStore`/`FileMemoryStore`
  (markdown под `~/.project01-cli/memory/`: `profile.md`, `profiles/<name>.md`, `rules/NNN-*.md`,
  `tasks/<id>.md`, поверх `platform:fileSystem`); `MemoryProvider` (фасад: режим+taskId+activeProfileName).
  `FileMemoryStore(root, fs)` — `fs: LocalFileSystem` **обязателен** (из графа; дефолт-фабрики больше нет).
- `di/`: `memoryModule` — `MemoryStore`/`MemoryProvider` по `root` (fs из `fileSystemModule`),
  `HistoryStore` по `sessionId`/`branch` (dao из `databaseModule`). Общая дока — [DI.md](../../DI.md).

## Зависимости
- `api(features:llm)` (Message/Usage/LlmApi/PricingRegistry протекают через порты) +
  `api(platform:database)`, `implementation(platform:fileSystem)`, `implementation(platform:logging)`,
  serialization-json, `implementation(koin.core)` (для di). **НЕ зависит на `features:agent`** —
  наоборот, `agent → memory`. Потребители — `features:agent`, `lifecycle:*`, `apps:cliJvmApp`.

## Тесты
`./gradlew :agenticHubClient:features:memory:jvmTest` — домен/компакция/persistence: `MemoryStoreTest`/
`MemoryProviderProfileTest`, `HistoryCompressorTest`/`MemoryLayerTest`/`ProfileTest`/`TaskStateTest`,
`ContextStrategyTest`/`FactsExtractorTest`/`HistoryStoreTest`/`HistoryStoreBranchTest`/`SessionStatsTest`.
jvmTest (не commonTest — TestDb JVM + обход iOS backtick-грабли); фейки: `FakeLlmScript`/`llmTestModule` (features:llm), `TestDb` (platform:database).

## Грабли
- **`TaskStage.expectedAction` не должна требовать вход, которого не будет** — прежняя формулировка
  clarification («Ask before assuming») на слабой модели побеждала автономный промпт («действуй сам,
  вопросов не задавай»): модель залипала на первой стадии, повторяя «I cannot continue without
  requirements. Please provide them.», пока не кончались ходы. Сейчас — «если цель ясна или просят
  действовать самому, заяви допущения и двигайся; спрашивай, только если реально заблокирован».
  Урок общий: инструкция стадии читается моделью НАРАВНЕ с промптом запуска — противоречие между ними
  слабая модель разрешает не в ту сторону, и это лечится текстом стадии, а не движком.
- **`Role.SYSTEM` НЕ персистится в `HistoryStore`** — в `messages`-таблице только `USER`/`ASSISTANT`;
  memory-слой инжектится лишь в wire-list `TurnEngine.turn()` поверх baseContext.
- **Модель файловой памяти**: unnamed `profile.md` + именованные `profiles/<name>.md` (активный
  поверх unnamed, fallback на unnamed); структурированные секции (style/format/constraints/context);
  правила `rules/NNN-*.md`; задачи `tasks/<id>.md`. Грамматика CLI-команд — в `apps:cliJvmApp`.
- **`System.err` → `logWarn`**: код KMP (commonMain), JVM-only API нельзя (iOS) — для лога компакции
  `logWarn` из `platform:logging`.
