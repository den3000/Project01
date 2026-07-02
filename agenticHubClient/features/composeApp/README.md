# :agenticHubClient:features:composeApp — Compose Multiplatform демо-UI

Compose-MP модуль (jvm/android/ios) с демо-экраном визарда + iOS-фреймворком. Выделен из бывшего
`:shared` вместе с `platform:greeting`; `Greeting` приходит по DI, а не создаётся внутри.

## Публичный API
- `App(greeting: Greeting)` (`App.kt`) — Composable-корень; `@Preview AppPreview()` — обёртка.
- `MainViewController()` (iosMain, `MainViewController.kt`) — точка входа iOS (`ComposeUIViewController`).
- iOS-фреймворк `baseName = "ComposeApp"` (Swift: `import ComposeApp`, `MainViewControllerKt.MainViewController()`).

## Зависимости
- `implementation(platform:greeting)` + Compose (runtime/foundation/material3/ui/resources/lifecycle).
  Потребители — `apps:desktopApp`, `apps:androidApp` (передают `App(Greeting())`), `apps:iosApp` (линкует фреймворк).

## Грабли
- **release-линковка iOS-фреймворка падает по памяти (OOM)** на полном `./gradlew build` —
  env-ограничение; debug-фреймворк (его использует iosApp) линкуется. Обход: `-x linkReleaseFrameworkIos*`.
- Ресурсы: `compose.resources { packageOfResClass = "…features.composeapp.generated.resources" }`.
