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

## Карта `cliJvmApp`
`src/main/kotlin/ru/den/writes/code/project01/cliJvm/`
- `main.kt` — bootstrap, Room init + миграции (v1→v4), dispatch по `CliArgs`.
- `CliArgs.kt` — sealed разбор аргументов (ListSessions/Clean/Inflate/Chat/OneShot) + `USAGE`.
- `Agent.kt` — диалоговый цикл, footer (turn/context/session), REPL branch-команды, session-summary.
- `LlmApi.kt` — нейтральный интерфейс + `Message`/`Role`/`GenerationParams`/`Usage`/`LlmResult`.
- `ModelProvider.kt`, `GeminiModel.kt`, `OpenRouterModel.kt` — typed провайдер + модель (+`Custom` escape hatch).
- `GeminiApi.kt`+`GeminiDto.kt`, `OpenRouterApi.kt`+`OpenRouterDto.kt` — реализации `LlmApi`.
- `HuggingFaceApi.kt`+`HuggingFaceDto.kt`+`HuggingFaceModel.kt` — третий провайдер: HF Inference Providers через OpenAI-совместимый Router.
- `ModelPricing.kt` — `PricingRegistry`: цены/окна. **Single source of truth по ценам.**
- `ContextStrategy.kt` (full/window/summary) + `StickyFacts.kt` (facts) + `HistoryCompressor.kt` — стратегии контекста.
- `SessionStats.kt`, `PromptSource.kt`.
- `db/` — Room: `AppDatabase` (version=4), `MessageDao`, `HistoryStore`, entity'и (messages/summaries/facts).
- `memory/` — модель памяти ассистента: `MemoryMode` (PREAMBLE/SYSTEM), `MemoryStore` (markdown-файлы под `~/.project01-cli/memory/`: `profile.md`, `profiles/<name>.md`, `rules/NNN-*.md`, `tasks/<id>.md`), `MemoryLayer` (композиция `composePreamble`/`composeSystem`), `MemoryProvider` (фасад с активным режимом + taskId + activeProfileName).
- `memory/Profile.kt` — структурированный профиль: `ProfileSection` (style/format/constraints/context) + `ProfileData` (4 списка bullet'ов + опц. `freeText` для legacy) + `parseProfileData`/`renderProfileData` (markdown round-trip) + `parseProfileCommand` (общий для CLI и REPL) + `isValidProfileName`.
- Тесты: `src/test/.../cliJvm/` — `FakeLlmApi`, `TestDb` + `*Test` (offline, без сети).

## Команды и verification loop
- **`./gradlew :cliJvmApp:test`** — быстрые offline-тесты. **Гонять после каждой правки**
  `Agent`/`CliArgs`/`HistoryStore`/`*Api`/strategy.
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
