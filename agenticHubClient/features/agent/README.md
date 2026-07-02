# :agenticHubClient:features:agent — портируемое ядро агента

KMP-модуль: один ход агента без сессионного состояния + memory-слой (профиль/правила/task-FSM),
judge-слой (инварианты-как-код) и per-stage маршрутизация. Гоняется раннером из
`lifecycle:session`; персистом/оркестрацией не занимается.

## Публичный API
- `AgentConfig` (`LlmApi` + `GenerationParams` + опц. `profileName` + `toolExecutor`) +
  `AgentResponder.respond` (один ход: wire-list `memoryLayer+baseContext+userTurn` → `LlmApi`, парс
  stage-сигнала; tool-loop ≤`MAX_TOOL_ROUNDS`) + `TurnOutcome`/`ExecutedToolCall`.
- Per-stage: `RoutedAgent`/`RoutedJudge` + parsed `StageAgentSpec`/`StageJudgeSpec` (+`TaskBinding`)
  + билдеры `buildRoutedAgents(stageAgents, client, params)`/`buildJudges(judgeAgents, client)`
  (`RoutedAgentBuilders.kt`).
- `context/` — `HistoryCompressor` (rolling-summary, чистый) + `evenDown`.
- `invariant/` — `InvariantChecker` + `LlmInvariantJudge` (независимый LLM-вызов, fail-open) +
  `InvariantJudgePrompt` + `InvariantVerdict`/`InvariantViolation`.
- `memory/` — `Profile` (`ProfileSection`/`ProfileData`/парс-рендер), `MemoryLayer`
  (`composePreamble`/`composeSystem`), `MemoryMode`, `MemoryModels` (`RuleEntry`/`TaskNotes`),
  `TaskState` (`TaskStage` clarification→planning→execution→validation→done + `TaskStateMachine`).

## Зависимости
- `api(features:llm)` (публичный API протекает LLM-типами), `implementation(platform:logging)`.
  Потребители — `features:memory`, `features:lifecycle:*`.

## Тесты
`./gradlew :agenticHubClient:features:agent:jvmTest` — `AgentResponderTest`, `HistoryCompressorTest`,
`InvariantJudgePromptTest`/`LlmInvariantJudgeTest`, `ProfileTest`/`MemoryLayerTest`/`TaskStateTest`
(+ `FakeLlmApi`). Backtick-имена тестов **без** `()`/`,` — иначе iOS commonTest не компилится.

## Грабли
- **Stage-маркер `[[stage:...]]` не вырезается из ответа** (БАГ): `AgentResponder.respond` парсит
  сигнал, но возвращает сырой `result.text` — маркер протекает в показ и в persist; Gemini stateless
  → ре-шлётся, модель плодит новые. Фикс: срезать распознанный `[[stage:]]` перед persist/показом.
- **Task state machine** — `TaskStage` clarification→planning→execution→validation→done, forward +
  один шаг назад, `done` терминальна; переход авто по маркеру, валидирует `TurnEngine` (session).
- **MCP function-calling** — tool-loop в `AgentResponder.respond`: ответ с `toolCalls` → `executor.
  execute` → дописать в wire → снова, до финального текста. FC только Gemini. Tool-обмен эфемерный.
- **LLM-judge на thinking-модели режет вердикт** — thinking ест тот же `maxTokens` → JSON обрывается
  → fail-open молча пропускает. Лечится `thinkingBudget=0` + малый maxtokens judge.
- **`Role.SYSTEM` инжектится только в wire-list** `TurnEngine.turn()` поверх baseContext (не
  персистится — см. `features:memory`); провайдер сам собирает system-блок.
