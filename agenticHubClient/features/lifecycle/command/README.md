# :agenticHubClient:features:lifecycle:command — словарь запуска/конфига

JVM-модуль: нейтральный неразделимый command-домен, общий для `lifecycle:start` (исполняет) и
`lifecycle:session` (конфигурит runtime). Только типы-данные, без логики.

## Публичный API
- `StartCommand` (sealed): admin `ListSessions`/`CleanHistory`/`CleanSession`/`InflateSession`/
  `MemoryOp` + под-интерфейс `SessionInitialState` → `RunChat`/`RunOneShot` (`StartCommand.kt`).
- `SessionConfig` — session-lifetime конфиг = `RunChat.config` (feeds, chat-params, стратегия, task/
  profile, memory-mode, stages, judges, mcpServers, schedules) (`SessionConfig.kt`).
- `ScheduleSpec` (Collect/Agent), `MemoryAction` (sealed) (`ScheduleSpec.kt`/`MemoryAction.kt`).

## Зависимости
- `api(features:memory)` + `api(features:agent)` + `api(features:llm)` — типы `ContextStrategyKind`/
  `StageAgentSpec`/`MemoryMode`/`ModelProvider` протекают через публичный API. Потребители —
  `lifecycle:session`, `lifecycle:start`, `apps:cliJvmApp`.

## Тесты
Отдельных нет — парсинг во `StartCommand`/`SessionConfig` покрыт мапперами в `apps:cliJvmApp:test`.
