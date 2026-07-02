# :agenticHubClient:apps:desktopApp — Desktop (JVM) приложение

Тонкий Desktop-таргет Compose-MP демо: `main()` открывает окно и рендерит общий `App(Greeting())`
(composition-root инжектит `Greeting`).

## Публичный API
- `main()` (`main.kt`) — `mainClass = …agenticHub.desktop.MainKt`.

## Зависимости
- `implementation(features:composeApp)` + `implementation(platform:greeting)` + compose.desktop.

## Запуск
`./gradlew :agenticHubClient:apps:desktopApp:run` (hot reload:
`./gradlew :agenticHubClient:apps:desktopApp:hotRun --auto`). Есть run-конфиг `.run/desktopApp […]`.
