# Project01 — проектная память

KMP-проект. Корень пакета — `ru.den.writes.code.agenticHub.*` во ВСЕХ модулях (у каждого свой
уникальный сегмент; split-пакетов нет). Storage-пути (`~/.project01-cli/…`) и `applicationId`/
iOS-bundle-id остались на `project01` (это идентификаторы, не пакеты). Рабочая лошадка —
**cliJvmApp**: JVM-консольный клиент к LLM (Gemini + OpenRouter + Hugging Face).
**Отвечать по-русски.**

> **Этот файл — постоянная память проекта. Правится из любого worktree.**
> Чтобы параллельные worktree почти не давали merge-конфликтов (а редкие — были локальными):
> - Один факт = один короткий **независимый** пункт-строка, не сплошной текст.
> - Новое — **добавлять** отдельным пунктом; не переписывать/реформатировать соседние строки.
> - `.gitattributes` помечает файл `merge=union` — git склеивает параллельные добавления сам.
> - **Не дублировать то, что уже есть в коде** (цены, сигнатуры, версии) — указатель на файл.

## Модули (деталь — в README каждого модуля)

Обзор проекта — [README.md](README.md). Каждый gradle-модуль несёт свой README (роль, публичный API,
зависимости, тесты). **Модуль-специфичные грабли живут в разделе «Грабли» README своего модуля** —
здесь не дублируются.

- **features**: [llm](agenticHubClient/features/llm/README.md) ·
  [agent](agenticHubClient/features/agent/README.md) ·
  [memory](agenticHubClient/features/memory/README.md) ·
  [rag](agenticHubClient/features/rag/README.md) ·
  [composeApp](agenticHubClient/features/composeApp/README.md) ·
  [mcpClient](agenticHubClient/features/mcpClient/README.md) · lifecycle
  [command](agenticHubClient/features/lifecycle/command/README.md)/[session](agenticHubClient/features/lifecycle/session/README.md)/[start](agenticHubClient/features/lifecycle/start/README.md)
- **platform**: [logging](agenticHubClient/platform/logging/README.md) ·
  [config](agenticHubClient/platform/config/README.md) ·
  [database](agenticHubClient/platform/database/README.md) ·
  [fileSystem](agenticHubClient/platform/fileSystem/README.md) ·
  [network](agenticHubClient/platform/network/README.md) ·
  [greeting](agenticHubClient/platform/greeting/README.md)
- **apps**: [cliJvmApp](agenticHubClient/apps/cliJvmApp/README.md) ·
  [androidApp](agenticHubClient/apps/androidApp/README.md) ·
  [desktopApp](agenticHubClient/apps/desktopApp/README.md) · iosApp (Xcode-проект)
- **playground**: [cliTui](playground/cliTui/README.md) ·
  [openmeteo-mcp](playground/openmeteo-mcp/README.md) · [localfs-mcp](playground/localfs-mcp/README.md) ·
  [git-mcp](playground/git-mcp/README.md) · [support-mcp](playground/support-mcp/README.md) ·
  [atimelogger-mcp](playground/atimelogger-mcp/README.md) · [ticktick-mcp](playground/ticktick-mcp/README.md)
- **playground**: [projectfs-mcp](playground/projectfs-mcp/README.md) — файловые инструменты по дереву проекта (read-write, 5 штук; инфикс `_project_` — против коллизии имён с git-mcp, роутер падает на дубле на старте)
- **demo**: [ctt-support](demo/ctt-support/README.md) — ассистент поддержки CTT (роли гость/клиент/разработчик, FSM+судья, RAG доки+код, тикеты через support-mcp)
- **demo**: [weekly-review](demo/weekly-review/README.md) — недельный разбор продуктивности: план (ticktick `week_plan` — часы по тайм-блокам) vs факт (atimelogger `time_by_activity`) → агент cliJvmApp сравнивает по активностям (LLM сам мапит разные названия) и даёт рекомендации; работает вживую
- **demo**: [project-fs](demo/project-fs/README.md) — ассистент по файлам чужого проекта (2 сценария: usage-report/adr; профили fs-explorer/fs-reporter нарезаны по фазам = так настраивается судья, projectfs-mcp, глобальных правил ноль)
- **[scheduling](scheduling/README.md)** — ядро планировщика.
- **testUtils** (`agenticHubClient/testUtils`) — кросс-каттинг тест-утилиты (`@IgnoreIos` — expect/actual,
  на iOS = `kotlin.test.Ignore`; JVM/Android — no-op), `commonTest`-зависимость. Фейки тут НЕ живут.
- Тест-фейки живут **рядом с реальными реализациями** (не в отдельном модуле): `FakeLlmScript`/
  `LlmApiFake`+`llmTestModule` (features:llm), `LocalFileSystemFake`+`fileSystemTestModule`
  (platform:fileSystem), `TestDb`+`databaseTestModule` (platform:database), `EmbedderFake`+`ragTestModule`
  (features:rag). Модуль `:testing` снесён —
  [DI.md](agenticHubClient/DI.md).

## Команды (offline; сеть/токены/TTY — спрашивать перед запуском)

- Быстрые тесты: `./gradlew :agenticHubClient:apps:cliJvmApp:test` (runtime + golden) +
  `:agenticHubClient:features:llm:jvmTest :agenticHubClient:features:agent:jvmTest :agenticHubClient:features:memory:jvmTest` (ядро).
- Бинарь: `:agenticHubClient:apps:cliJvmApp:installDist` →
  `./agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp …`.
  **ОБЯЗАТЕЛЬНО пересобирать после правки CLI-флагов** (иначе новый флаг «прилипает» к предыдущему).
- Полный build (обход pre-existing iOS-грабель — см. follow-up): `./gradlew build
  -x compileTestKotlinIosArm64 -x compileTestKotlinIosSimulatorArm64
  -x linkReleaseFrameworkIosArm64 -x linkReleaseFrameworkIosSimulatorArm64`.
- Инспекция БД: `sqlite3 ~/.project01-cli/history.db ".schema"`.
- MCP-демо / TUI-прогон / real-network smoke — жгут токены или нужен настоящий TTY: **спрашивать**
  (детали — [cliJvmApp README](agenticHubClient/apps/cliJvmApp/README.md)).
- **Live-тесты** (бьют по реальному сервису, напр. локальной Ollama) — opt-in, класс `*LiveTest`,
  исключены из дефолтного прогона; запуск флагом `-PliveTests`. Механизм/конвенция — [LIVE_TESTS.md](LIVE_TESTS.md).
- **Демо гоняют УСТАНОВЛЕННЫЙ бинарь** (`build/install/…`), а не текущие исходники: перед прогоном демо
  после любых правок кода — `installDist`, иначе проверяешь прошлую сборку (не только про CLI-флаги).

## Кросс-каттинг

- **Не печатать секреты** (значения ключей из `local.properties`/`BuildKonfig`) в транскрипт.
- **DI — Koin** (`koin-core`, KMP): каждый модуль несёт свой Koin-модуль в пакете `<root>.di`;
  конвенции (платформенный `expect/actual`→`Module` vs универсальный `module{}`, `parametersOf`,
  holder при >5 арг, composition root `startKoin` в cliJvmApp) — [agenticHubClient/DI.md](agenticHubClient/DI.md).

## Незакрытый follow-up (кросс-модульный)

- iOS: проверка сборки в Xcode (iosApp `import ComposeApp` + фреймворк `ComposeApp`); release-линковка
  iOS-фреймворка Compose падает по памяти на полном `./gradlew build` (env-OOM; обход — флаги `-x` выше;
  детали — composeApp README).
- **iOS commonTest `features:agent` не компилится** на backtick-именах тестов с `()`/`,` (ограничение
  Kotlin/Native) — pre-existing; обход теми же `-x compileTestKotlinIos*`.
- KMP-изация модулей сделана (lifecycle command/start/session, scheduling, mcpClient[jvm+android,
  без iOS — SDK без iOS-klib]). Осталось: реальные android/ios `actual` (сейчас `TODO()`) —
  `platform:database` (builder/TestDb), `platform:fileSystem` (LocalFileSystem/homeDirectory),
  `lifecycle:session` (`SessionPlatform` часы/диспетчер). См. README модулей.
- `.run/androidApp.run.xml` держит старое имя модуля (`Project01.androidApp`) — AS переразрешит на sync.

## Стиль работы пользователя
- По-русски. **Делать только то, что попросили** — без попутных «улучшений» (особенно BuildKonfig в
  `platform:config`, который он настроил сам).
- **НИКОГДА не ссылаться на «день N», «Day-N», «учебный» и название челленджа** в коде, комментариях,
  docstring'ах, именах тестов, README, CLAUDE.md. Описывать фичу через её роль (`structured profile`,
  `sliding window strategy`), а не через этап. При правке — попутно убирать такие метки рядом.
- Любит sealed interfaces, DI через конструктор, чистые тестируемые функции, точные комментарии/доки.
- Не любит speculative refactors / premature abstractions; за хорошо мотивированный рефакторинг — да.
- Plan-mode для крупных задач: план → разногласия → правки → одобрение → реализация. Диффы читает внимательно.
- **План — по атомарным коммитам**: каждый коммит независимо собирается и проходит тесты
  (green-to-green), тесты в том же коммите; порядок аддитивное → переключение → удаление. Skill `atomic-plan`.
- **Правит файлы и коммитит между ходами** — ВСЕГДА перечитывать файл перед `Edit`, проверять
  `git log`/`git status`.
- «Выведи текстом» → не создавать файлы. «Код не трогай» → менять только комментарии/доки.
- Бережёт API-токены.

## Версии и ключи
- Точные версии — `gradle/libs.versions.toml` (Kotlin 2.4.0, Ktor 3.0.3 engine Java, Room, KSP, buildkonfig…).
- Ключи: `BuildKonfig.GEMINI_API_KEY` / `OPENROUTER_API_KEY` / `HUGGINGFACE_API_KEY` (плагин
  `buildkonfig`, в `platform:config`). Источник — `local.properties` (gitignored), при отсутствии —
  env-переменная того же имени. Дока — [platform:config README](agenticHubClient/platform/config/README.md).

## Worktree (Android Studio)
- Работаю в том worktree, что открыт в AS, и запускаюсь **из него**. Не из вложенного
  `.claude/worktrees/` — он ломает индекс AS.
- Память (этот файл + авто-память Claude) шарится между всеми worktree по git-репо.
- Run-конфиги AS — в `.run/` (Store as project file): в git, шарятся между worktree.
- Обустройство окружения (worktree-соседи, симлинк `local.properties`, `new-worktree.sh`, `fixtures/`) —
  в `~/Documents/AiAdvenChallenge/README.md`.
