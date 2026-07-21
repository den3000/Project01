# :agenticHubClient:features:agent — портируемое ядро агента

KMP-модуль: один ход агента без сессионного состояния, judge-слой (инварианты-как-код) и per-stage
маршрутизация. Гоняется раннером из `lifecycle:session`; персистом/оркестрацией не занимается.
Memory-домен (профиль/правила/task-FSM/`MemoryLayer`) и компакция (`HistoryCompressor`) живут ниже, в
`features:memory` (`agent → memory`).

## Публичный API
- `AgentConfig` (`LlmApi` + `GenerationParams` + опц. `profileName` + `toolExecutor`) +
  `AgentResponder.respond` (один ход: wire-list `memoryLayer+baseContext+userTurn` → `LlmApi`, парс
  stage-сигнала; tool-loop ≤`MAX_TOOL_ROUNDS`) + `TurnOutcome`/`ExecutedToolCall`.
- Per-stage: `RoutedAgent`/`RoutedJudge` + parsed `StageAgentSpec`/`StageJudgeSpec` (+`TaskBinding`)
  + билдеры `buildRoutedAgents(stageAgents, client, params)`/`buildJudges(judgeAgents, client)`
  (`RoutedAgentBuilders.kt`).
- `invariant/` — `InvariantChecker` (вход целиком — `JudgeInput`) + `LlmInvariantJudge` (независимый
  LLM-вызов, fail-open) + `InvariantJudgePrompt` + `InvariantVerdict`/`InvariantViolation`.
- `ToolCallLog` — сессионное окно выполненных вызовов (ёмкость 12 = `MAX_TOOL_ROUNDS` × 2 попытки),
  считает вытесненные; инжектится в `TurnEngine`.

### Что видит судья (`JudgeInput`)

Три вида полей, и вид решает, что судья вправе с ними делать:

| Вид | Поля | Правило |
|---|---|---|
| **binding** | `rules`, `constraints` | только против них репортится нарушение |
| **context** | `stage`, `format`, `style` | никогда не нарушение сами по себе — они нужны, чтобы судья не принял требуемую форму за дефект |
| **untrusted** | `assistantReply`, `userMessage`, `toolCalls` | материал под аудитом: за забором, инструкции внутри игнорируются |

Истории диалога нет by design, секции `context` профиля — тоже (она говорит, КАК работать; судья
превратил бы совет в инвариант). `hasInvariants` — «есть что нарушать»: пусто → вызова к модели нет.

Вывод инструмента в промпте режется **в двух измерениях** (600 символов И 8 строк): по символам
рвётся строка, по строкам проходит гигантский дамп, а клип до первой строки убедил бы судью, что
поиск нашёл ровно одно совпадение. Отброшенное всегда названо числом.
- Memory-типы, которыми оперирует ход (`MemoryLayer`/`Profile`/`TaskStage`/`TaskBinding`/`RuleEntry`),
  берутся из `features:memory`.
- `di/`: `agentModule` — `List<RoutedAgent>` по (`stageAgents`, `params`) и `List<RoutedJudge>` по
  `judgeAgents` (`HttpClient` из графа; `LlmApi` строится билдером по каждому spec). Дока — [DI.md](../../DI.md).

## Зависимости
- `api(features:llm)` + `api(features:memory)` (публичный API протекает LLM- и memory-домен-типами),
  `implementation(platform:logging)` + `implementation(koin.core)` (для di). Потребители — `features:lifecycle:*`.

## Тесты
`./gradlew :agenticHubClient:features:agent:jvmTest` — `AgentResponderTest`, `InvariantJudgePromptTest`,
`LlmInvariantJudgeTest` (`FakeLlmScript`/`llmTestModule` из features:llm). Memory-домен-тесты уехали в `features:memory`.
Backtick-имена в commonTest **без** `()`/`,` — иначе iOS commonTest не компилится.

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
- **Вывод инструментов — канал prompt injection.** Текст пользователя попадает в хранилище (тикет
  создаётся из его слов) и возвращается поиском прямо в промпт судьи. Поэтому недоверенные секции
  обёрнуты тегами, а `sanitizeUntrusted` обезвреживает теги внутри них. Несущая защита — `passed`
  вычисляется из `violations.isEmpty()`, полю модели не верят: не «упрощать».
- **Критерий, пригодный судье, решается по тексту ответа и уликам.** Условие, которого во входе нет
  («если пользователь не найден…»), судья домысливает — два живых прогона потеряны именно так.
- **`Role.SYSTEM` инжектится только в wire-list** `TurnEngine.turn()` поверх baseContext (не
  персистится — см. `features:memory`); провайдер сам собирает system-блок.
