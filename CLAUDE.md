# Project01 — проектная память

KMP-проект (пакет `ru.den.writes.code.project01`).
Рабочая лошадка — **`cliJvmApp`**: JVM-консольный клиент к LLM (Gemini + OpenRouter).
**Отвечать по-русски.**

> **Этот файл — постоянная память проекта. Правится из любого worktree.**
> Чтобы параллельные worktree почти не давали merge-конфликтов (а редкие —
> были локальными и тривиальными):
> - Один факт = один короткий **независимый** пункт-строка, не сплошной текст.
> - Новое — **добавлять** отдельным пунктом; не переписывать и не
>   реформатировать соседние строки (именно это плодит конфликты).
> - `.gitattributes` помечает файл `merge=union` — git склеивает параллельные
>   добавления из разных веток автоматически, без конфликт-маркеров.
> - **Не дублировать то, что уже есть в коде** (цены, сигнатуры, версии) —
>   указатель на файл-источник.

## Карта модулей
**`shared` (commonMain, пакет `ru.den.writes.code.project01.shared.*`)** — портируемое ядро для всех таргетов (jvm/android/ios):
- `llm/` — `LlmApi` (нейтральный интерфейс + `Message`/`Role`/`GenerationParams`/`Usage`/`LlmResult`), `ModelProvider` (sealed-дискриминатор провайдера).
- `llm/gemini|openrouter|huggingface/` — по провайдеру: `*Api` (реализация `LlmApi`) + `*Dto` + `*Model` (typed каталог + `Custom`). `HttpClient` инжектится снаружи; ktor-движок (`Java`) — в cliJvmApp, не в shared.
- `context/` — `HistoryCompressor` (rolling-summary алгоритм, чистый, без `HistoryStore`) + `evenDown` (`EvenDown.kt`).
- `pricing/` — `ModelPricing`/`PricingRegistry`: цены/окна. **Single source of truth по ценам.**
- `memory/` — `Profile.kt` (`ProfileSection` style/format/constraints/context + `ProfileData` + `parseProfileData`/`renderProfileData`/`parseProfileCommand`/`isValidProfileName`), `MemoryLayer` (`composePreamble`/`composeSystem`), `MemoryMode` (PREAMBLE/SYSTEM), `MemoryModels.kt` (`RuleEntry`/`TaskNotes`), `TaskState.kt` (`TaskStage` clarification→planning→execution→validation→done + `TaskStateMachine`: `allowedNext`/`canTransition`/`parseStageSignal`).
- `util/Logging.kt` — `expect/actual logWarn` для retry-логов `*Api` (jvm/android → `System.err`, ios → `println`).
- BuildKonfig (ключи API) — тоже в `shared`, см. «Версии и ключи».

**`cliJvmApp` (пакет `ru.den.writes.code.project01.cliJvm.*`, зависит от `:shared`)** — JVM-консольный клиент:
- `main.kt` — bootstrap, Room init + миграции (v1→v4), `HttpClient(Java)`, dispatch по `CliArgs`.
- `CliArgs.kt` — sealed разбор аргументов (ListSessions/Clean/Inflate/Chat/OneShot/Memory) + `USAGE`. CLI-специфика, остаётся в cliJvmApp.
- `Agent.kt` — диалоговый цикл, footer (turn/context/session), REPL branch/memory-команды, session-summary.
- `ContextStrategy.kt` (full/window/summary, завязан на `HistoryStore`) + `StickyFacts.kt` (`FactsExtractor` + sticky-facts стратегия). `Summary` оборачивает shared-`HistoryCompressor`.
- `SessionStats.kt` (счётчики сессии, завязан на Room `MessageEntity`), `PromptSource.kt` (stdin/файловый ввод).
- `db/` — Room: `AppDatabase` (version=4), `MessageDao`, `HistoryStore`, entity'и (messages/summaries/facts).
- `memory/` — `MemoryStore` (markdown-файлы под `~/.project01-cli/memory/`: `profile.md`, `profiles/<name>.md`, `rules/NNN-*.md`, `tasks/<id>.md`), `MemoryProvider` (фасад: режим + taskId + activeProfileName).

## Тесты (offline, без сети)
- `shared/src/commonTest/…shared/` — тесты переехавшего ядра: `*ApiTest` (gemini/openrouter/huggingface), `HistoryCompressorTest`, `PricingRegistryTest`, `ProfileTest`, `MemoryLayerTest` + `FakeLlmApi`. Гонять `./gradlew :shared:jvmTest`.
- `cliJvmApp/src/test/…cliJvm/` — `TestDb` + своя копия `FakeLlmApi` + `*Test` для остающегося кода (db/`HistoryStore`/`Agent`/`MemoryStore`/`CliArgs`/`SessionStats`/`ContextStrategy`/`FactsExtractor`/…).

## Команды и verification loop
- **`./gradlew :cliJvmApp:test`** (cliJvmApp) + **`:shared:jvmTest`** (ядро) — быстрые offline-тесты.
  **Гонять после каждой правки**: `Agent`/`CliArgs`/`HistoryStore`/`MemoryStore`/`ContextStrategy` → `:cliJvmApp:test`;
  `*Api`/`HistoryCompressor`/`PricingRegistry`/`Profile`/`MemoryLayer` → `:shared:jvmTest`.
- `./gradlew :cliJvmApp:installDist` → `./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp …`.
  **ОБЯЗАТЕЛЬНО пересобирать после правки CLI-флагов** — иначе старый бинарь не знает новых
  флагов (симптом: новый флаг «прилипает» к значению предыдущего).
- Инспекция БД: `sqlite3 ~/.project01-cli/history.db ".schema"`.
- Real-network smoke жжёт токены — **спрашивать пользователя перед запуском**.

## Грабли (не повторять)
- **Ktor engine — `Java`, не CIO** (CIO рвёт длинные thinking-ответы Gemini).
- Gemini **stateless** — историю шлём целиком каждый ход (растёт линейно).
- **Thinking-токены биллятся как output** — основная статья расхода у сильных моделей.
- Имена Gemini: `gemini-3-flash-preview` = 3.1 Flash (без `.1`); `gemini-3.5-pro` и `-3.5-flash-lite` не существуют.
- **OpenRouter free-roster протухает быстро** — `:free` id мрут (404). Сверять с
  `https://openrouter.ai/api/v1/models` (`jq`, ключ не нужен). Каталог — `OpenRouterModel.kt`,
  цены — `ModelPricing.kt`. Дефолт `openrouter/auto` — роутер, НЕ `:free` → может быть платным.
- **HF Router маршрутизирует между провайдерами** (Cerebras/Together/Fireworks/…), реальный
  тариф per-provider — цены в `ModelPricing.kt` для HF-моделей это *приближения*. Free tier
  HF — $0.10/мес кредитов поверх. 503 при cold start серверлес-модели → одна retry с
  `Retry-After` (`HuggingFaceApi`). Живой каталог: `https://router.huggingface.co/v1/models`.
- zsh-глоббинг (`?` `*` `[` `!`) в `-prompt` ломается без кавычек — оборачивать.
- **Не печатать секреты** (значения ключей из `local.properties`/`BuildKonfig`) в транскрипт.
- `readlnOrNull()` глобально кэширует `System.in` — Agent читает через инжектируемый `PromptSource`.
- flash-lite TPM 4M — главный боттлнек нагрузочных прогонов (упираешься в rate-limit раньше, чем в переполнение контекста).
- **`Role.SYSTEM` НЕ персистится в `HistoryStore`** — memory-слой инжектится только в wire-list `Agent.send()` поверх `baseContext`. В `messages`-таблице всегда лежат только `USER`/`ASSISTANT`. Каждый провайдер сам собирает все `Role.SYSTEM` входа в нативный system-блок (Gemini → `SystemInstruction`, OpenAI-shape → один `role="system"` в начале списка); `endSequence` приклеивается к этому же блоку.
- **`-memory-mode <preamble|system>` — переключатель режима** memory-слоёв; без него memory-провайдер не создаётся и wire-list байт-в-байт совпадает с режимом без памяти. `-task <id>` без `-memory-mode` парсер отвергает.
- **Структурированный профиль** — `-memory profile <section> <text>` (section ∈ style|format|constraints|context) добавляет bullet, `<section> clear` чистит секцию, `clear` сносит весь unnamed профиль; legacy `-memory profile <free text>` всё ещё работает (если первый токен не keyword — пишет blob в `profile.md`). Аналогично REPL: `/profile <section> <text>` / `/profile clear`. `ProfileData(freeText="X")` рендерится в wire байт-в-байт как `[Profile]\nX` (legacy free-text режим).
- **Именованные профили** — `profiles/<name>.md` рядом с unnamed `profile.md`. CLI: `-profile <name>` (требует `-memory-mode`) выбирает стартовый активный; `-memory profile <name>` touch-создаёт; `-memory profile <name> <section> <text>` / `<section> clear` / `clear` редактируют именованный; `-memory profile-list` / `profile-show <name>` — просмотр. REPL: `/profile-use <name>` переключает активный, `/profile-list`, `/profile-show <name>`, `/profile <name> <section> <text>` / `/profile <name> clear`. Активный профиль выбирается **поверх** unnamed (fallback на `profile.md` если активного нет) — пользователи с unnamed профилем не сломаны.
- **Task state machine** — `TaskNotes.stage: TaskStage?` — автомат `clarification→planning→execution→validation→done` (`shared.memory.TaskState`: `TaskStateMachine.allowedNext`/`canTransition`, forward + один шаг назад, `done` терминальна). Переход **авто**: модель ставит в ответе маркер `[[stage:<next>]]`, `Agent.send` парсит (`parseStageSignal`), валидирует по таблице и применяет — нелегальный/отсутствующий игнорится (+warn в stderr). Пауза — ортогональный флаг `TaskNotes.paused` (REPL `/task-pause`/`/task-resume`, CLI `-memory task <id> pause|resume`); на паузе авто-переход подавлён. Новая задача (`/task`, `-memory task <id>`) стартует на `clarification`. FSM-грани рендерятся в блок `[Current Task]` (`Stage`/`Status`/`Expected action`/`Allowed next` + протокол маркера) — `Allowed next`+протокол только когда не на паузе и не терминал.

## Стиль работы пользователя
- По-русски. **Делать только то, что попросили** — без попутных «улучшений»
  (особенно BuildKonfig в `:shared`, который он настроил сам).
- **НИКОГДА не ссылаться на «день N», «Day-N», «pre-Day-N», «учебный» и название
  челленджа** в коде, комментариях, docstring'ах, именах тестов, README, CLAUDE.md
  и любых других файлах проекта. Описывать фичу через её роль (`structured
  profile`, `named profiles`, `sliding window strategy`), а не через этап, на
  котором она добавлена. При правке кода — попутно убирать такие метки, если
  они встречаются рядом.
- Любит sealed interfaces, DI через конструктор, чистые тестируемые функции, точные комментарии/доки.
- Не любит speculative refactors / premature abstractions; за хорошо мотивированный рефакторинг — да.
- Plan-mode для крупных задач: план → разногласия → правки → одобрение → реализация. Диффы читает внимательно.
- **План — по атомарным коммитам**: каждый коммит независимо собирается и проходит тесты (green-to-green), тесты в том же коммите, что и код; порядок аддитивное → переключение → удаление. Методичка и формат — skill `atomic-plan`, применять при любом планировании (в т.ч. native plan mode).
- **Правит файлы и коммитит между ходами** — ВСЕГДА перечитывать файл перед `Edit`,
  проверять `git log`/`git status` (бывает чистое дерево там, где ждёшь правки).
- «Выведи текстом» → не создавать файлы. «Код не трогай» → менять только комментарии/доки.
- Бережёт API-токены.

## Версии и ключи
- Точные версии — `gradle/libs.versions.toml` (Kotlin 2.4.0, Ktor 3.0.3 engine Java, Room, KSP, buildkonfig…).
- Ключи: `BuildKonfig.GEMINI_API_KEY` / `OPENROUTER_API_KEY` (плагин `buildkonfig`, lowercase k, в `:shared`).
  Источник — `local.properties` (gitignored), при отсутствии ключа fallback на env-переменную того же имени
  (`shared/build.gradle.kts` → `getStringPropertyOrEnvVar`).
- Третий ключ — `BuildKonfig.HUGGINGFACE_API_KEY` (источник `HUGGINGFACE_API_KEY` в `local.properties`/env), для `-provider huggingface`.

## Worktree (Android Studio)
- Работаю в том worktree, что открыт в AS, и запускаюсь **из него**. Не из
  вложенного `.claude/worktrees/` — он ломает индекс AS.
- Память (этот файл + авто-память Claude) шарится между всеми worktree по git-репо —
  контекст не теряется при переезде.
- Run-конфиги AS — в `.run/` (Store as project file): в git, поэтому шарятся между
  worktree как и этот файл.
- Обустройство окружения (worktree-соседи, симлинк `local.properties`, скрипт
  `new-worktree.sh`, каталог `fixtures/` для больших тестовых файлов) — в
  `~/Documents/AiAdvenChallenge/README.md`.
