# :agenticHubClient:features:memory — persistence + context-стратегии

KMP-модуль: весь persistence-концерн (история разговора поверх Room, файловая память профилей/
правил/задач) + стратегии сборки контекста хода. Скрывает Room/ФС-типы за нейтральными портами.

## Публичный API
- `HistoryStore` (+ снимки `SummarySnapshot`/`FactsSnapshot`) — порт истории на (session, branch);
  `RoomHistoryStore` (`db/`) — Room-реализация поверх `platform:database`; `SessionStats`(+`seedFrom`).
- `MemoryStore`/`FileMemoryStore` — markdown-память под `~/.project01-cli/memory/`
  (`profile.md`, `profiles/<name>.md`, `rules/NNN-*.md`, `tasks/<id>.md`) поверх `platform:fileSystem`;
  `MemoryProvider` — фасад (режим + taskId + activeProfileName).
- `ContextStrategy` (full/window/summary; `Summary` оборачивает `HistoryCompressor`) + `TurnContext`/
  `ContextStrategyKind` + `StickyFacts`/`FactsExtractor` (`ContextStrategy.kt`/`StickyFacts.kt`).

## Зависимости
- `api(features:agent)` + `api(platform:database)`, `implementation(platform:fileSystem)`,
  `implementation(platform:logging)`, serialization-json. Потребители — `lifecycle:*`, `apps:cliJvmApp`.

## Тесты
`./gradlew :agenticHubClient:features:memory:jvmTest` — `MemoryStoreTest`, `MemoryProviderProfileTest`.
Хранилище истории/миграции покрыто в `apps:cliJvmApp:test`.

## Грабли
- **`Role.SYSTEM` НЕ персистится в `HistoryStore`** — в `messages`-таблице только `USER`/`ASSISTANT`;
  memory-слой инжектится лишь в wire-list `TurnEngine.turn()` поверх baseContext.
- **Модель файловой памяти**: unnamed `profile.md` + именованные `profiles/<name>.md` (активный
  выбирается поверх unnamed, fallback на unnamed); структурированные секции профиля
  (style/format/constraints/context); правила `rules/NNN-*.md`; задачи `tasks/<id>.md`. Грамматика
  CLI-команд управления ими — в `apps:cliJvmApp` (флаги/`/`-команды).
- **`System.err` → `logWarn`**: код здесь KMP (commonMain), JVM-only API нельзя (иначе iOS не
  компилится) — для лога компакции используется `logWarn` из `platform:logging`.
