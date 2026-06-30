KMP-проект на Compose Multiplatform под Android, iOS и Desktop (JVM), плюс JVM-консольный
**LLM-клиент** поверх общего provider-нейтрального доменного ядра.

## Модули

- **[/shared](./shared/src)** — код, общий для всех таргетов (Android, iOS, Desktop/JVM): и
  Compose Multiplatform UI, и provider-нейтральное **LLM-ядро**, на котором построен CLI —
  `llm/` (контракт `LlmApi`, каталоги провайдеров, клиенты Gemini/OpenRouter/Hugging Face),
  `context/` (rolling-summary компакция), `pricing/`, `memory/` (профиль + правила + task-FSM),
  `agent/` (одноходовый responder), `invariant/` (LLM-judge).
  - [commonMain](./shared/src/commonMain/kotlin) — общий для всех таргетов; платформенные папки
    (напр. [iosMain](./shared/src/iosMain/kotlin), [jvmMain](./shared/src/jvmMain/kotlin)) держат
    код, компилируемый только под названный папкой таргет.
- **[/cliJvmApp](./cliJvmApp)** — рабочая лошадка: JVM-консольный клиент, который шлёт промпт в
  chat-style LLM (Gemini / OpenRouter / Hugging Face), печатает ответ с footer'ом токенов/стоимости
  и персистит multi-turn REPL в локальный SQLite. Добавляет слой памяти (профиль / правила /
  task-FSM), агентов по стадиям, judge инвариантов, контекст-стратегии и MCP function calling.
  Полная дока: **[cliJvmApp/README.md](./cliJvmApp/README.md)**.
- **[/mcps](./mcps)** — каталог standalone MCP-серверов (Model Context Protocol) на официальном
  Kotlin MCP SDK, которые CLI дёргает через `-mcpServer` (повторяемый — несколько серверов сразу):
  - **[/mcps/openmeteo-mcp](./mcps/openmeteo-mcp)** — Open-Meteo weather-сервер + планировщик сбора
    погоды. Дока: [README](./mcps/openmeteo-mcp/README.md).
  - **[/mcps/localfs-mcp](./mcps/localfs-mcp)** — локальная ФС: накопить документ в буфере и записать
    на диск (`append_to_document` / `save_document`). Дока: [README](./mcps/localfs-mcp/README.md).
- **[/scheduling](./scheduling)** — переиспользуемое ядро планировщика без зависимостей
  (отложенные + периодические задачи), используется openmeteo-mcp и cliJvmApp.
  Дока: [scheduling/README.md](./scheduling/README.md).
- **[/cliTui](./cliTui)** — изолированная песочница для сравнения terminal-UI библиотек на JVM.
  Combo Kotter + Mordant, к которому она пришла, уже интегрирован в cliJvmApp (вид `-tui`). Дока:
  [cliTui/README.md](./cliTui/README.md).
- **[/iosApp](./iosApp/iosApp)** — точка входа iOS-приложения (SwiftUI-хост для общего Compose UI;
  SwiftUI-код добавлять сюда).
- **[/androidApp](./androidApp)**, **[/desktopApp](./desktopApp)** — таргеты Android- и
  Desktop-(JVM) приложений на Compose Multiplatform.

## Запуск приложений

Через run-конфигурации в run-виджете IDE или этими Gradle-командами:

- Android-приложение: `./gradlew :androidApp:assembleDebug`
- Desktop-приложение: `./gradlew :desktopApp:run` (hot reload: `./gradlew :desktopApp:hotRun --auto`)
- iOS-приложение: открыть каталог [/iosApp](./iosApp) в Xcode и запустить оттуда.
- CLI JVM-приложение: `./gradlew :cliJvmApp:installDist`, затем
  `./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -prompt "<текст>" [...флаги]` — флаги,
  каталоги моделей и recipes см. в **[cliJvmApp/README.md](./cliJvmApp/README.md)**.
- MCP-серверы: `./gradlew :mcps:openmeteo-mcp:installDist :mcps:localfs-mcp:installDist` — бинари
  под `mcps/<имя>/build/install/<имя>/bin/<имя>` (спавнятся клиентом, см. ниже).
- MCP-оркестрация (два сервера, кросс-серверная цепочка) — собрать оба сервера + cliJvmApp, затем:
  ```
  OM=$(pwd)/mcps/openmeteo-mcp/build/install/openmeteo-mcp/bin/openmeteo-mcp
  FS=$(pwd)/mcps/localfs-mcp/build/install/localfs-mcp/bin/localfs-mcp
  cliJvmApp -prompt "Узнай погоду в Москве, добавь её в документ и сохрани в файл moscow.md" \
            -mcpServer "$OM" -mcpServer "$FS"
  ```
  LLM сам строит цепочку `current_weather` [OM] → `append_to_document` [FS] → `save_document` [FS];
  файл → `~/.project01-localfs/documents/moscow.md`.

## Запуск тестов

Через кнопку в gutter'е редактора IDE или Gradle-таски:

- Android-тесты: `./gradlew :shared:testAndroidHostTest`
- Desktop + тесты доменного ядра (LLM API, pricing, context, memory): `./gradlew :shared:jvmTest`
- iOS-тесты: `./gradlew :shared:iosSimulatorArm64Test`
- Тесты CLI JVM-приложения (быстрые, без сети — провайдеры застаблены `FakeLlmApi`):
  `./gradlew :cliJvmApp:test`
- Тесты MCP-серверов (offline): `./gradlew :mcps:openmeteo-mcp:test :mcps:localfs-mcp:test`

---

Подробнее о [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…
