# :agenticHubClient:platform:logging — платформенный лог предупреждений

KMP-модуль с единственной точкой: платформенно-нейтральный хук для транзиентных
предупреждений (например `[retry] …`, которые печатают `*Api` при бэкоффе). Отделён от обычного
`println`, чтобы сохранить stdout/stderr-разделение на таргетах, где stderr есть.

## Публичный API
- `logWarn(message)` — `expect/actual` (`Logging.kt` + `Logging.{jvm,android,ios}.kt`). JVM/Android
  пишут в `System.err`, iOS — `println` (stderr там нет).

## Зависимости
Нет (лист). Потребители: `features:llm`, `features:agent`, `features:memory` (`implementation`).

## Тесты
Нет (тривиальный expect/actual).
