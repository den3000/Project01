# :agenticHubClient:platform:config — API-ключи через BuildKonfig

Модуль без Kotlin-исходников: плагин `buildkonfig` генерирует объект `BuildKonfig` с ключами API
в пакет-корень `ru.den.writes.code.agenticHub` (см. `packageName` в `build.gradle.kts`).

## Публичный API
- `BuildKonfig.GEMINI_API_KEY` / `OPENROUTER_API_KEY` / `HUGGINGFACE_API_KEY` — генерируются на
  этапе сборки. Источник значений — `local.properties` (gitignored), при отсутствии — env-переменная
  того же имени. Подробнее — раздел «Версии и ключи» в корневом `CLAUDE.md`.

## Зависимости
Нет модульных. Потребитель — `apps:cliJvmApp` (`main.kt` читает ключи).

## Грабли
- **Не печатать значения ключей** в транскрипт/логи.
