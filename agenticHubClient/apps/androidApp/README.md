# :agenticHubClient:apps:androidApp — Android-приложение

Тонкий Android-таргет Compose-MP демо: единственная `MainActivity`, которая рендерит общий
`App(Greeting())` (composition-root инжектит `Greeting`).

## Публичный API
- `MainActivity` (`MainActivity.kt`) + `@Preview AppAndroidPreview()`.

## Зависимости
- `implementation(features:composeApp)` + `implementation(platform:greeting)`.
- Android `namespace = …agenticHub.android`; `applicationId` остался `…project01` (distribution-id, не пакет).

## Запуск
`./gradlew :agenticHubClient:apps:androidApp:assembleDebug` (или run-конфиг в AS).
