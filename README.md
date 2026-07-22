KMP-проект на Compose Multiplatform (Android, iOS, Desktop/JVM) плюс JVM-консольный **LLM-клиент**
поверх общего provider-нейтрального доменного ядра. Корень пакета — `ru.den.writes.code.agenticHub.*`
(у каждого модуля свой уникальный сегмент). Рабочая лошадка —
[**cliJvmApp**](./agenticHubClient/apps/cliJvmApp/README.md).

## Модули

Всё под `agenticHubClient/{features,platform,apps}` + `playground/` + корневой `scheduling`.
У каждого модуля — свой README (деталь рядом с кодом).

**`agenticHubClient/features/`** — доменное ядро:
- [features:llm](./agenticHubClient/features/llm) — provider-нейтральное LLM-ядро (Gemini/OpenRouter/
  Hugging Face/локальная Ollama), tool-типы, ценовой реестр, `McpToolRouter`.
- [features:memory](./agenticHubClient/features/memory) — фундамент памяти: домен (профиль/правила/
  task-FSM/memory-layer), rolling-summary компакция, context-стратегии, persistence (Room + файловая).
- [features:agent](./agenticHubClient/features/agent) — одноходовый responder, judge инвариантов,
  per-stage маршрутизация (зависит на `memory`).
- [features:composeApp](./agenticHubClient/features/composeApp) — Compose-MP демо-UI + iOS-фреймворк
  `ComposeApp`.
- [features:mcpClient](./agenticHubClient/features/mcpClient) — `McpToolClient` (MCP-сервер как `ToolExecutor`).
- [features:rag](./agenticHubClient/features/rag) — RAG-ядро: чанкинг (fixed/token/structural),
  эмбеддинги (`Embedder` + `OllamaEmbedder`), векторный индекс с косинусным поиском, JSON-персист.
- lifecycle: [command](./agenticHubClient/features/lifecycle/command) (словарь запуска/конфига) ·
  [session](./agenticHubClient/features/lifecycle/session) (MVI-runtime) ·
  [start](./agenticHubClient/features/lifecycle/start) (первоначальный запуск).

**`agenticHubClient/platform/`** — платформенные порты:
- [logging](./agenticHubClient/platform/logging) · [config](./agenticHubClient/platform/config)
  (API-ключи через BuildKonfig) · [database](./agenticHubClient/platform/database) (Room-KMP) ·
  [fileSystem](./agenticHubClient/platform/fileSystem) · [network](./agenticHubClient/platform/network)
  (общий `HttpClient`) · [greeting](./agenticHubClient/platform/greeting)
  (демо-домен).

**`agenticHubClient/apps/`** — приложения:
- [cliJvmApp](./agenticHubClient/apps/cliJvmApp/README.md) — JVM-консольный LLM-клиент (полная дока).
- [androidApp](./agenticHubClient/apps/androidApp) · [desktopApp](./agenticHubClient/apps/desktopApp)
  — Compose-MP таргеты; **iosApp** (`apps/iosApp`) — Xcode-проект (SwiftUI-хост для `ComposeApp`).

**`playground/`** — песочницы и standalone MCP-серверы (CLI дёргает их через `-mcpServer`, повторяемый):
- [cliTui](./playground/cliTui) — сравнение TUI-библиотек;
- MCP-серверы: [openmeteo-mcp](./playground/openmeteo-mcp) (погода) ·
  [localfs-mcp](./playground/localfs-mcp) (локальная ФС) · [git-mcp](./playground/git-mcp)
  (git-инспекция: ветка/файлы/дифф) · [support-mcp](./playground/support-mcp) (users/tickets) ·
  [atimelogger-mcp](./playground/atimelogger-mcp) (трекинг времени aTimeLogger) ·
  [ticktick-mcp](./playground/ticktick-mcp) (задачи TickTick).

**`demo/`** — сценарии поверх MCP-серверов и агента cliJvmApp:
- [ctt-support](./demo/ctt-support) — ассистент поддержки (роли, FSM+судья, RAG, тикеты через support-mcp);
- [weekly-review](./demo/weekly-review) — недельный разбор продуктивности: план (ticktick-mcp `week_plan`)
  vs факт времени (atimelogger-mcp `time_by_activity`) → рекомендации от LLM.

**[scheduling](./scheduling)** — переиспользуемое ядро планировщика (без зависимостей), используется
openmeteo-mcp и cliJvmApp.

**[testUtils](./agenticHubClient/testUtils)** — кросс-каттинг тест-утилиты (напр. `@IgnoreIos`),
`commonTest`-зависимость. Фейки тут НЕ живут — они рядом со своими реализациями.

**DI** — граф собирается через Koin; конвенции, composition root и **тест-модули** (фейки живут рядом
с реальными реализациями, `:testing` снесён) — [agenticHubClient/DI.md](./agenticHubClient/DI.md).

## Запуск

- Desktop: `./gradlew :agenticHubClient:apps:desktopApp:run`
  (hot: `:agenticHubClient:apps:desktopApp:hotRun --auto`)
- Android: `./gradlew :agenticHubClient:apps:androidApp:assembleDebug`
- iOS: открыть `agenticHubClient/apps/iosApp` в Xcode.
- CLI: `./gradlew :agenticHubClient:apps:cliJvmApp:installDist`, затем
  `./agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -prompt "<текст>" [...флаги]`
  — флаги/каталоги моделей см. [cliJvmApp/README.md](./agenticHubClient/apps/cliJvmApp/README.md).
- MCP-серверы: `./gradlew :playground:openmeteo-mcp:installDist :playground:localfs-mcp:installDist`.
- MCP-оркестрация (два сервера, кросс-серверная цепочка):
  ```
  OM=$(pwd)/playground/openmeteo-mcp/build/install/openmeteo-mcp/bin/openmeteo-mcp
  FS=$(pwd)/playground/localfs-mcp/build/install/localfs-mcp/bin/localfs-mcp
  cliJvmApp -prompt "Узнай погоду в Москве, добавь её в документ и сохрани в moscow.md" \
            -mcpServer "$OM" -mcpServer "$FS"
  ```
  LLM сам строит цепочку `current_weather` [OM] → `append_to_document` [FS] → `save_document` [FS];
  файл → `~/.project01-localfs/documents/moscow.md`.

## Тесты

- Доменное ядро: `./gradlew :agenticHubClient:features:llm:jvmTest
  :agenticHubClient:features:agent:jvmTest :agenticHubClient:features:memory:jvmTest`
- CLI-приложение (offline, провайдеры застаблены): `./gradlew :agenticHubClient:apps:cliJvmApp:test`
- MCP-серверы: `./gradlew :playground:openmeteo-mcp:test :playground:localfs-mcp:test`
- Планировщик: `./gradlew :scheduling:test`
- iOS/Android доменных таргетов: `compileKotlinIosArm64` соответствующих KMP-модулей (features/platform).
- **Live-тесты** (бьют по реальному сервису, напр. локальной Ollama) — opt-in, класс `*LiveTest`,
  флаг `-PliveTests`; механизм — [LIVE_TESTS.md](./LIVE_TESTS.md).

---

Подробнее о [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
и [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform).
