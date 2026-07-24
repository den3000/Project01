# :agenticHubClient:platform:config — API-ключи (BuildKonfig + разрешение из env)

Плагин `buildkonfig` генерирует объект `BuildKonfig` с ключами API в пакет-корень
`ru.den.writes.code.agenticHub` (см. `packageName` в `build.gradle.kts`). Плюс модуль несёт
маленький словарь имён ключей и функцию их разрешения (`commonMain`, пакет
`ru.den.writes.code.agenticHub.platform.config`).

## Публичный API
- `enum ApiKey(val envVar: String)` — `GEMINI` / `OPEN_ROUTER` / `HUGGING_FACE`; `envVar` — имя
  env-переменной и поля `BuildKonfig` (`GEMINI_API_KEY` и т.д.). **Единственный источник этих
  строк** — в остальном коде они не пишутся литералами (только через `ApiKey`).
- `data class ApiKeys(gemini, openRouter, huggingFace)` — тройка значений ключей; инжектится
  туда, где строится модель-провайдер (composition root в `cliJvmApp`).
- `fun resolveKey(key: ApiKey, baked: String, env: (String) -> String?): String` — значение из
  `env` бьёт `baked` (то, что `BuildKonfig` вшил на этапе сборки), пустой/пробельный env
  считается отсутствующим. `env` — параметр (на JVM зовущая сторона передаёт `System::getenv`),
  поэтому функция чистая и живёт в `commonMain` (без JVM-only дефолта).
- `BuildKonfig.GEMINI_API_KEY` / `OPENROUTER_API_KEY` / `HUGGINGFACE_API_KEY` — генерируются на
  этапе сборки. Источник значений — `local.properties` (gitignored), при отсутствии — env
  того же имени. Подробнее — раздел «Версии и ключи» в корневом `CLAUDE.md`.

## Зависимости
Нет модульных. Потребители: `features:llm` (`ApiKey.envVar` в `MissingApiKey`) и `apps:cliJvmApp`
(`resolveKey`/`ApiKeys`/`BuildKonfig` в composition root).

## Грабли
- **Не печатать значения ключей** в транскрипт/логи.
- Три строки имён ключей в `build.gradle.kts` (`buildConfigField(..., "GEMINI_API_KEY", ...)`) в
  `ApiKey` **не переводятся**: build-скрипт компилируется отдельным classpath и не видит классы
  своего же модуля, а сами строки там — имена генерируемых полей `BuildKonfig` и ключи
  property/env для чтения `local.properties`.
