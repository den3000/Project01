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
- **[/mcpLab](./mcpLab)** — песочница для экспериментов с MCP (Model Context Protocol) на
  официальном Kotlin MCP SDK: клиент-проба, печатающая инструменты любого stdio-сервера, и свой
  Open-Meteo weather-сервер (`--serve`), который CLI дёргает через `-mcpServer`. Дока:
  [mcpLab/README.md](./mcpLab/README.md).
- **[/scheduling](./scheduling)** — переиспользуемое ядро планировщика без зависимостей
  (отложенные + периодические задачи), задуманное под mcpLab и cliJvmApp сразу; интеграция в них
  ещё не сделана. Дока: [scheduling/README.md](./scheduling/README.md).
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
- MCP-песочница: `./gradlew :mcpLab:installDist && ./mcpLab/build/install/mcpLab/bin/mcpLab`.

## Запуск тестов

Через кнопку в gutter'е редактора IDE или Gradle-таски:

- Android-тесты: `./gradlew :shared:testAndroidHostTest`
- Desktop + тесты доменного ядра (LLM API, pricing, context, memory): `./gradlew :shared:jvmTest`
- iOS-тесты: `./gradlew :shared:iosSimulatorArm64Test`
- Тесты CLI JVM-приложения (быстрые, без сети — провайдеры застаблены `FakeLlmApi`):
  `./gradlew :cliJvmApp:test`
- Тесты MCP-песочницы (offline): `./gradlew :mcpLab:test`

---

Подробнее о [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…
